package dev.lokalgrid.app.ui

import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.lokalgrid.app.ui.theme.Lg
import dev.lokalgrid.protocol.TrackRecord
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonSource
import com.google.gson.JsonObject
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val SRC_DOT = "lg-dot"
private const val SRC_ACC = "lg-acc"
private const val SRC_PEERS_DOT = "lg-peers-dot"
private const val SRC_PEERS_ACC = "lg-peers-acc"

/** Keyless raster basemaps — no API key, no quota. The offline PMTiles basemap
 *  (§6) replaces these later; for now the user can switch source at runtime. */
private data class Basemap(val name: String, val url: String, val attribution: String, val maxZoom: Int)

private val BASEMAPS = listOf(
    Basemap("Streets", "https://tile.openstreetmap.org/{z}/{x}/{y}.png", "© OpenStreetMap contributors", 19),
    Basemap("Sat", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}", "© Esri, Maxar, Earthstar Geographics", 19),
    Basemap("Dark", "https://basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png", "© OpenStreetMap, © CARTO", 20),
    Basemap("Topo", "https://tile.opentopomap.org/{z}/{x}/{y}.png", "© OpenTopoMap (CC-BY-SA)", 17),
)

private fun styleJson(b: Basemap): String = """
{
  "version": 8,
  "sources": { "base": {
    "type": "raster", "tiles": ["${b.url}"], "tileSize": 256,
    "maxzoom": ${b.maxZoom}, "attribution": "${b.attribution}"
  } },
  "layers": [ { "id": "base", "type": "raster", "source": "base" } ]
}
"""

private class MapHolder {
    var map: MapLibreMap? = null
    var dot: GeoJsonSource? = null
    var acc: GeoJsonSource? = null
    var peersDot: GeoJsonSource? = null
    var peersAcc: GeoJsonSource? = null
    var centeredOnce = false
}

/** Add the position dot + HDOP accuracy ring on top of a freshly-set style.
 *  Called on first load and again after every basemap switch (setStyle clears
 *  custom layers). Immediately pushes the latest fix so the dot never blinks out. */
private fun applyStyle(map: MapLibreMap, b: Basemap, holder: MapHolder, latest: TrackRecord?, peers: List<Peer>) {
    map.setStyle(Style.Builder().fromJson(styleJson(b))) { style ->
        // Peers first, so your own dot always draws on top of them.
        val peersAcc = GeoJsonSource(SRC_PEERS_ACC)
        val peersDot = GeoJsonSource(SRC_PEERS_DOT)
        style.addSource(peersAcc)
        style.addSource(peersDot)
        val peerColor = Expression.toColor(Expression.get("color"))
        style.addLayer(
            FillLayer("lg-peers-acc-fill", SRC_PEERS_ACC).withProperties(
                PropertyFactory.fillColor(peerColor),
                PropertyFactory.fillOpacity(0.10f)
            )
        )
        style.addLayer(
            LineLayer("lg-peers-acc-line", SRC_PEERS_ACC).withProperties(
                PropertyFactory.lineColor(peerColor),
                PropertyFactory.lineWidth(1.2f),
                PropertyFactory.lineDasharray(arrayOf(2f, 2f))
            )
        )
        style.addLayer(
            CircleLayer("lg-peers-dot", SRC_PEERS_DOT).withProperties(
                PropertyFactory.circleRadius(5f),
                PropertyFactory.circleColor(peerColor),
                PropertyFactory.circleStrokeColor(Color.parseColor("#0A0F0E")),
                PropertyFactory.circleStrokeWidth(2f)
            )
        )
        holder.peersDot = peersDot
        holder.peersAcc = peersAcc

        val acc = GeoJsonSource(SRC_ACC)
        val dot = GeoJsonSource(SRC_DOT)
        style.addSource(acc)
        style.addSource(dot)
        style.addLayer(
            FillLayer("lg-acc-fill", SRC_ACC).withProperties(
                PropertyFactory.fillColor(Color.parseColor("#4EC2A6")),
                PropertyFactory.fillOpacity(0.12f)
            )
        )
        style.addLayer(
            LineLayer("lg-acc-line", SRC_ACC).withProperties(
                PropertyFactory.lineColor(Color.parseColor("#4EC2A6")),
                PropertyFactory.lineWidth(1.5f),
                PropertyFactory.lineDasharray(arrayOf(3f, 2f))
            )
        )
        style.addLayer(
            CircleLayer("lg-dot", SRC_DOT).withProperties(
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleColor(Color.parseColor("#4EC2A6")),
                PropertyFactory.circleStrokeColor(Color.parseColor("#0A0F0E")),
                PropertyFactory.circleStrokeWidth(2f)
            )
        )
        holder.dot = dot
        holder.acc = acc
        latest?.let { pushFix(holder, it) }
        pushPeers(holder, peers)
    }
}

private fun pushFix(holder: MapHolder, r: TrackRecord) {
    holder.dot?.setGeoJson(Point.fromLngLat(r.lonDeg, r.latDeg))
    holder.acc?.setGeoJson(geoCircle(r.latDeg, r.lonDeg, accuracyMeters(r.hd)))
}

private fun pushPeers(holder: MapHolder, peers: List<Peer>) {
    holder.peersDot?.setGeoJson(FeatureCollection.fromFeatures(peers.map {
        Feature.fromGeometry(Point.fromLngLat(it.lonDeg, it.latDeg), colorProp(it))
    }))
    holder.peersAcc?.setGeoJson(FeatureCollection.fromFeatures(peers.map {
        Feature.fromGeometry(geoCircle(it.latDeg, it.lonDeg, it.accuracyM), colorProp(it))
    }))
}

private fun colorProp(p: Peer) = JsonObject().apply { addProperty("color", p.colorHex) }

@Composable
fun MapLibreView(latest: TrackRecord?, peers: List<Peer> = emptyList(), modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val holder = remember { MapHolder() }
    var basemap by remember { mutableStateOf(BASEMAPS.first()) }
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            mapView.onDestroy()
        }
    }

    Box(modifier) {
        AndroidView(
            factory = {
                mapView.getMapAsync { map ->
                    holder.map = map
                    with(map.uiSettings) {
                        isZoomGesturesEnabled = true
                        isScrollGesturesEnabled = true
                        isRotateGesturesEnabled = true
                        isTiltGesturesEnabled = true
                        isDoubleTapGesturesEnabled = true
                        // hide the built-in +/- since we provide our own
                        isCompassEnabled = true
                    }
                    applyStyle(map, basemap, holder, latest, peers)
                }
                mapView
            },
            modifier = Modifier.fillMaxSize(),
            update = {
                pushPeers(holder, peers)
                val r = latest ?: return@AndroidView
                pushFix(holder, r)
                val map = holder.map ?: return@AndroidView
                if (!holder.centeredOnce) {
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(r.latDeg, r.lonDeg)).zoom(16.0).build()
                    holder.centeredOnce = true
                }
            }
        )

        // Basemap switcher — top-right chips.
        Row(
            Modifier.align(Alignment.TopEnd).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (b in BASEMAPS) {
                StyleChip(b.name, active = b.name == basemap.name) {
                    basemap = b
                    holder.map?.let { applyStyle(it, b, holder, latest, peers) }
                }
            }
        }

        // Zoom + recenter — bottom-right stack.
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MapButton("⌖") {
                val r = latest ?: return@MapButton
                holder.map?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(r.latDeg, r.lonDeg), 17.0)
                )
            }
            MapButton("+") { holder.map?.animateCamera(CameraUpdateFactory.zoomIn()) }
            MapButton("−") { holder.map?.animateCamera(CameraUpdateFactory.zoomOut()) }
        }
    }
}

@Composable
private fun StyleChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) Lg.Lock else Lg.Ink2,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (active) Lg.LockBg else Lg.Deep.copy(alpha = 0.85f))
            .border(1.dp, if (active) Lg.Lock else Lg.Rule, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    )
}

@Composable
private fun MapButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Lg.Deep.copy(alpha = 0.9f))
            .border(1.dp, Lg.Rule, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Lg.Lock, fontFamily = FontFamily.Monospace, fontSize = 20.sp)
    }
}
