package dev.lokalgrid.app.ui.map

import android.content.Context
import java.io.File

/**
 * The two basemaps this app keeps.
 *
 * Streets and Dark, and nothing else: satellite imagery and topo were there
 * because they were free, not because the product needs them — and every extra
 * style is another tile pyramid to download for offline use, which is the feature
 * that actually matters when you are out of range (§6, node-served PMTiles later).
 *
 * Keyless raster sources, so there is no API key and no quota. PMTiles served
 * from the node replaces these eventually; until then the offline download below
 * is what makes a basemap survive having no internet.
 */
data class Basemap(
    val name: String,
    val url: String,
    val attribution: String,
    val maxZoom: Int,
) {
    /** MapLibre's offline manager takes a style *URL*, not inline JSON, so each
     *  style is written to app storage and referenced from there. Same file for
     *  rendering and for downloading — one definition, no chance of the offline
     *  pack being cut for a style the map does not actually show. */
    fun styleFile(context: Context): File =
        File(context.filesDir, "style-${name.lowercase()}.json")

    fun styleJson(): String = """
        {
          "version": 8,
          "sources": { "base": {
            "type": "raster", "tiles": ["$url"], "tileSize": 256,
            "maxzoom": $maxZoom, "attribution": "$attribution"
          } },
          "layers": [ { "id": "base", "type": "raster", "source": "base" } ]
        }
    """.trimIndent()

    fun styleUri(context: Context): String {
        val f = styleFile(context)
        val json = styleJson()
        // Rewrite only when it changed: the offline packs are keyed on this URL,
        // and rewriting identical bytes on every launch would be pointless churn.
        if (!f.exists() || f.readText() != json) f.writeText(json)
        return "file://${f.absolutePath}"
    }
}

val BASEMAPS = listOf(
    Basemap(
        name = "Streets",
        url = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        attribution = "© OpenStreetMap contributors",
        maxZoom = 19,
    ),
    Basemap(
        name = "Dark",
        url = "https://basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
        attribution = "© OpenStreetMap, © CARTO",
        maxZoom = 20,
    ),
)
