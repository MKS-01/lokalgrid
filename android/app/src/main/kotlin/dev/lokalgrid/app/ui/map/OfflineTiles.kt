package dev.lokalgrid.app.ui.map

import android.content.Context
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/**
 * Downloading a basemap for offline use.
 *
 * This is the map half of the product's whole premise: the node works with no
 * carrier and no internet, so a basemap that only exists while you have internet
 * is the one part of the app that stops working exactly when you need it. You
 * fetch the area on WiFi at home, and it is there in the field.
 *
 * Deliberately explicit, never automatic: a background download of a few hundred
 * megabytes over someone's mobile data is precisely the kind of thing this
 * project does not do. The user picks the area, sees an estimate first, and starts
 * it (§6 — config staged, then written; the same rule applies to bandwidth).
 *
 * Node-served PMTiles (§ Phase 06b) is the better answer later. This works today
 * with the tiles already being used for rendering.
 */
class OfflineTiles(context: Context) {

    /**
     * Null when MapLibre has not been initialised, which used to be a crash on
     * opening the Map tab: `OfflineManager` reaches for the file source, and the
     * file source refuses to exist before `MapLibre.getInstance`. It is called at
     * startup now — this stays defensive because the feature going missing is a
     * far better failure than the tab taking the app down.
     */
    private val manager = runCatching { OfflineManager.getInstance(context) }.getOrNull()

    val available: Boolean get() = manager != null

    /** Where a download is, as a state that names itself rather than a spinner. */
    sealed interface Progress {
        data object Idle : Progress
        data class Estimating(val tiles: Long) : Progress
        data class Running(val done: Long, val total: Long, val bytes: Long) : Progress
        data class Done(val tiles: Long, val bytes: Long) : Progress
        data class Failed(val reason: String) : Progress
    }

    /**
     * Rough tile count for a bounds and zoom span — shown *before* the download
     * starts, because "this will fetch 4,300 tiles" is a decision the user should
     * get to make. The real figure comes from the download itself.
     */
    fun estimateTiles(bounds: LatLngBounds, minZoom: Int, maxZoom: Int): Long {
        var total = 0L
        for (z in minZoom..maxZoom) {
            val n = 1 shl z
            val xSpan = ((bounds.longitudeEast - bounds.longitudeWest) / 360.0 * n)
            val latSpan = Math.toRadians(bounds.latitudeNorth - bounds.latitudeSouth)
            val ySpan = latSpan / (2 * Math.PI) * n /
                Math.cos(Math.toRadians((bounds.latitudeNorth + bounds.latitudeSouth) / 2))
            total += (Math.ceil(Math.abs(xSpan)) + 1).toLong() *
                (Math.ceil(Math.abs(ySpan)) + 1).toLong()
        }
        return total
    }

    /**
     * Start a download for [bounds]. [onProgress] is called on the main thread.
     *
     * Every failure path reports a *reason* — a download that silently stops is
     * indistinguishable from one that never started, and the user has no way to
     * know whether to walk out of the house trusting the map.
     */
    fun download(
        context: Context,
        basemap: Basemap,
        bounds: LatLngBounds,
        minZoom: Int,
        maxZoom: Int,
        onProgress: (Progress) -> Unit,
    ) {
        val definition = OfflineTilePyramidRegionDefinition(
            basemap.styleUri(context),
            bounds,
            minZoom.toDouble(),
            maxZoom.toDouble(),
            context.resources.displayMetrics.density,
        )

        val metadata = """{"name":"${basemap.name}","minZoom":$minZoom,"maxZoom":$maxZoom}"""
            .toByteArray()

        onProgress(Progress.Estimating(estimateTiles(bounds, minZoom, maxZoom)))

        val mgr = manager ?: run {
            onProgress(Progress.Failed("the map library is not initialised — offline saving is unavailable"))
            return
        }
        mgr.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(region: OfflineRegion) {
                    region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            if (status.isComplete) {
                                onProgress(
                                    Progress.Done(
                                        status.completedResourceCount,
                                        status.completedResourceSize,
                                    )
                                )
                                // Stop the observer's work once complete: MapLibre
                                // keeps the region active otherwise, which holds a
                                // wake path open for no reason.
                                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            } else {
                                onProgress(
                                    Progress.Running(
                                        status.completedResourceCount,
                                        maxOf(status.requiredResourceCount, status.completedResourceCount),
                                        status.completedResourceSize,
                                    )
                                )
                            }
                        }

                        override fun onError(error: OfflineRegionError) {
                            onProgress(Progress.Failed("${error.reason}: ${error.message}"))
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            // The library's own ceiling. Naming the number is the
                            // difference between "too big" and "pick a smaller area
                            // than 6000 tiles".
                            onProgress(
                                Progress.Failed(
                                    "area is over the $limit-tile limit — zoom in and try a smaller piece"
                                )
                            )
                        }
                    })
                    region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    onProgress(Progress.Failed(error))
                }
            },
        )
    }

    /** What is already downloaded, so the UI can say "you have this area" rather
     *  than offering to fetch it again. */
    fun listRegions(onResult: (Int, Long) -> Unit) {
        val mgr = manager ?: return onResult(0, 0)
        mgr.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(regions: Array<OfflineRegion>?) {
                val list = regions ?: emptyArray()
                if (list.isEmpty()) {
                    onResult(0, 0)
                    return
                }
                var replied = 0
                var bytes = 0L
                for (r in list) {
                    // Nullable parameters: these callbacks come from a Kotlin
                    // interface that declares them so, and a non-null override
                    // silently fails to override anything.
                    r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            bytes += status?.completedResourceSize ?: 0L
                            if (++replied == list.size) onResult(list.size, bytes)
                        }

                        override fun onError(error: String?) {
                            if (++replied == list.size) onResult(list.size, bytes)
                        }
                    })
                }
            }

            override fun onError(error: String) = onResult(0, 0)
        })
    }
}
