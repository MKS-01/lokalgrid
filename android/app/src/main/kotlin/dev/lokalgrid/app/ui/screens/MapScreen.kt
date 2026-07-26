package dev.lokalgrid.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.lokalgrid.app.LiveState
import dev.lokalgrid.app.ui.DoneState
import dev.lokalgrid.app.ui.EmptyState
import dev.lokalgrid.app.ui.ErrorState
import dev.lokalgrid.app.ui.InfoRow
import dev.lokalgrid.app.ui.LgButton
import dev.lokalgrid.app.ui.MapLibreView
import dev.lokalgrid.app.ui.OnResumeEffect
import dev.lokalgrid.app.ui.Pill
import dev.lokalgrid.app.ui.PillKind
import dev.lokalgrid.app.ui.ReasonRow
import dev.lokalgrid.app.ui.SectionLabel
import dev.lokalgrid.app.ui.WaitingState
import dev.lokalgrid.app.ui.gpsKind
import dev.lokalgrid.app.ui.gpsLabel
import dev.lokalgrid.app.ui.map.Basemap
import dev.lokalgrid.app.ui.map.OfflineTiles
import dev.lokalgrid.app.ui.myPeer
import dev.lokalgrid.app.ui.peersOf
import dev.lokalgrid.app.ui.relative
import dev.lokalgrid.app.ui.staleness
import dev.lokalgrid.app.ui.theme.Lg
import org.maplibre.android.geometry.LatLngBounds

/**
 * Everyone on one map — the product in one screen. Peers come from the node's
 * `peer` frames; your own dot comes from your own GNSS.
 *
 * The panel underneath also holds the offline basemap download, because the map
 * is the one part of this app that otherwise stops working exactly when the rest
 * of it starts mattering: no carrier, no internet, no tiles.
 */
@Composable
fun MapScreen(state: LiveState, onSharePosition: () -> Unit = {}) {
    val context = LocalContext.current
    val peers = peersOf(state)
    val me = myPeer(state)

    val offline = remember { OfflineTiles(context) }
    var camera by remember { mutableStateOf<Triple<Basemap, LatLngBounds, Int>?>(null) }
    var progress by remember { mutableStateOf<OfflineTiles.Progress>(OfflineTiles.Progress.Idle) }
    var haveRegions by remember { mutableStateOf(0) }
    var haveBytes by remember { mutableStateOf(0L) }

    fun refreshRegions() = offline.listRegions { n, bytes -> haveRegions = n; haveBytes = bytes }
    OnResumeEffect { refreshRegions() }

    Column(Modifier.fillMaxSize().background(Lg.Paper)) {
        MapLibreView(
            latest = state.latest,
            peers = peers,
            track = state.track,
            me = me,
            onCamera = { basemap, bounds, zoom -> camera = Triple(basemap, bounds, zoom) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 14.dp)
        ) {
            SectionLabel("people · ${peers.size + 1} on this node")
            InfoRow("you (${state.selfName})") {
                Pill(if (state.positionsShared > 0) "shared ×${state.positionsShared}" else "here", PillKind.OK)
            }
            InfoRow("your gps") { Pill(gpsLabel(state), gpsKind(state)) }

            val from: Pair<Double, Double>? = me?.let { it.latDeg to it.lonDeg }
                ?: state.latest?.let { it.latDeg to it.lonDeg }
            for (p in peers) {
                InfoRow(if (p.ghost) "${p.name} · ghost" else p.name) {
                    Pill(from?.let { (lat, lon) -> relative(lat, lon, p) } ?: "—", p.staleness())
                }
            }
            if (peers.isEmpty()) {
                EmptyState(
                    title = "nobody else is sharing",
                    reason = if (state.connected) {
                        "you are the only client that has shared a position with this node."
                    } else {
                        "not connected to a node, so there is nobody to hear from."
                    },
                )
            }
            if (me == null && state.latest != null) {
                InfoRow("distances from", "the node — your phone has no fix yet")
            }
            state.lastPeerSkip?.let { ReasonRow("last skip", it) }
            state.lastShareSource?.let { ReasonRow("last shared", it) }
            LgButton("Share my position", primary = true, enabled = state.connected, onClick = onSharePosition)

            OfflineSection(
                camera = camera,
                progress = progress,
                haveRegions = haveRegions,
                haveBytes = haveBytes,
                estimate = { basemap, bounds, zoom ->
                    offline.estimateTiles(bounds, zoom, minOf(zoom + 3, basemap.maxZoom))
                },
                onDownload = { basemap, bounds, zoom ->
                    offline.download(
                        context = context,
                        basemap = basemap,
                        bounds = bounds,
                        minZoom = zoom,
                        maxZoom = minOf(zoom + 3, basemap.maxZoom),
                    ) { p ->
                        progress = p
                        if (p is OfflineTiles.Progress.Done) refreshRegions()
                    }
                },
            )
        }
    }
}

/**
 * Download the visible area for offline use.
 *
 * Explicit, and with the cost shown first: the estimate appears before the button
 * does anything, because a few thousand tiles over someone's mobile data is not a
 * decision the app gets to make (§6 — staged, then written).
 */
@Composable
private fun OfflineSection(
    camera: Triple<Basemap, LatLngBounds, Int>?,
    progress: OfflineTiles.Progress,
    haveRegions: Int,
    haveBytes: Long,
    estimate: (Basemap, LatLngBounds, Int) -> Long,
    onDownload: (Basemap, LatLngBounds, Int) -> Unit,
) {
    SectionLabel("offline map")

    if (haveRegions > 0) {
        InfoRow("already saved", "$haveRegions area${if (haveRegions == 1) "" else "s"} · ${mib(haveBytes)}")
    }

    if (camera == null) {
        WaitingState(
            title = "waiting for the map",
            reason = "the area to save comes from what the map is showing — pan or zoom once and it appears here.",
        )
        return
    }

    val (basemap, bounds, zoom) = camera
    val maxZoom = minOf(zoom + 3, basemap.maxZoom)
    val tiles = estimate(basemap, bounds, zoom)

    InfoRow("area", "z$zoom–$maxZoom · ${basemap.name}")
    InfoRow("about", "$tiles tiles, roughly ${mib(tiles * 25_000)}")

    when (progress) {
        is OfflineTiles.Progress.Idle -> {
            ReasonRow(
                "do this on internet wifi",
                "the node's own wifi has no internet — join a network that does, save the area, " +
                    "then come back to the node. Saved tiles stay on the phone.",
            )
            LgButton("Save this area for offline use", primary = true) {
                onDownload(basemap, bounds, zoom)
            }
        }

        is OfflineTiles.Progress.Estimating ->
            WaitingState("starting", "${progress.tiles} tiles queued — the first ones are on their way.")

        is OfflineTiles.Progress.Running -> {
            val pct = if (progress.total > 0) (progress.done * 100 / progress.total).toInt() else 0
            WaitingState(
                title = "saving · $pct%",
                reason = "${progress.done} of ${progress.total} tiles, ${mib(progress.bytes)} on the phone so far.",
            )
        }

        is OfflineTiles.Progress.Done ->
            DoneState("area saved", "${progress.tiles} tiles, ${mib(progress.bytes)}. It renders with no internet now.")

        is OfflineTiles.Progress.Failed ->
            ErrorState(
                title = "download stopped",
                detail = progress.reason,
                actionLabel = "Try again",
                onAction = { onDownload(basemap, bounds, zoom) },
            )
    }
}

private fun mib(bytes: Long): String = when {
    bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
    else -> "%.1f MiB".format(bytes / 1024.0 / 1024.0)
}
