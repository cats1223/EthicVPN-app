package com.ethic.vpn.ui

import android.graphics.Color as AndroidColor
import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.net.URL
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

/** A labelled point on the map; [active] pins are drawn in the accent color. */
data class MapPin(val lat: Double, val lon: Double, val label: String, val active: Boolean)

/** OpenFreeMap vector styles: free, no key; attribution is shown by the map. */
private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"

/**
 * A vector globe (MapLibre with OpenFreeMap tiles in the globe projection) that can be spun,
 * pinched down to street level and rotated. It
 * flies to [centerLat], [centerLon] when the place changes; [line] joins two pins along the
 * route through the VPN. Labels are Compose views kept over their points, so flags render.
 */
@Composable
fun WorldMap(
    centerLat: Double,
    centerLon: Double,
    pins: List<MapPin>,
    line: Pair<MapPin, MapPin>?,
    dark: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
) {
    val context = LocalContext.current
    val tap by rememberUpdatedState(onTap)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    // Bumped on every camera move so the labels follow their points.
    var cameraTick by remember { mutableIntStateOf(0) }
    val accent = Brand.Cyan.toArgb()
    val green = Brand.Green.toArgb()

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // Leaving the home screen: wind the map down in order before destroying it.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.apply {
                isCompassEnabled = true
                compassGravity = Gravity.TOP or Gravity.END
                setCompassMargins(0, 220, 32, 0)
                attributionGravity = Gravity.TOP or Gravity.START
                logoGravity = Gravity.TOP or Gravity.START
                setAttributionMargins(220, 220, 0, 0)
                setLogoMargins(32, 220, 0, 0)
            }
            m.cameraPosition = CameraPosition.Builder().target(LatLng(centerLat, centerLon)).zoom(1.6).build()
            m.addOnCameraMoveListener { cameraTick++ }
            m.addOnMapClickListener {
                tap()
                false
            }
            map = m
        }
    }

    // A new style replaces the layers, so they are added again for it.
    LaunchedEffect(map, dark) {
        val m = map ?: return@LaunchedEffect
        style = null
        // The OpenFreeMap style with the globe projection added: a sphere when zoomed out, a flat
        // street map when zoomed in. If the style cannot be fetched here, MapLibre loads it itself.
        val url = if (dark) DARK_STYLE else LIGHT_STYLE
        val json = withContext(Dispatchers.IO) {
            runCatching { JSONObject(URL(url).readText()).put("projection", JSONObject().put("type", "globe")).toString() }.getOrNull()
        }
        val builder = if (json != null) Style.Builder().fromJson(json) else Style.Builder().fromUri(url)
        m.setStyle(builder) { s ->
            s.addSource(GeoJsonSource(ROUTE_SOURCE))
            s.addSource(GeoJsonSource(PINS_SOURCE))
            s.addSource(GeoJsonSource(PACKETS_SOURCE))
            s.addSource(GeoJsonSource(HALO_SOURCE))
            s.addLayer(
                // A faint solid track; the comets moving along it carry the motion.
                LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor(accent),
                    PropertyFactory.lineWidth(3f),
                    PropertyFactory.lineOpacity(0.45f),
                ),
            )
            // Comets that run along the route from the phone to the server, like traffic: each is a
            // white head with a fading tail; size and opacity come from the features.
            s.addLayer(
                CircleLayer(PACKETS_LAYER, PACKETS_SOURCE).withProperties(
                    PropertyFactory.circleRadius(Expression.get("r")),
                    PropertyFactory.circleOpacity(Expression.get("o")),
                    PropertyFactory.circleColor(Expression.toColor(Expression.get("c"))),
                    PropertyFactory.circleBlur(0.4f),
                ),
            )
            // A ring that keeps spreading out from the active pin.
            s.addLayer(
                CircleLayer(HALO_LAYER, HALO_SOURCE).withProperties(
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleColor(green),
                    PropertyFactory.circleOpacity(0f),
                ),
            )
            s.addLayer(
                CircleLayer(PINS_LAYER, PINS_SOURCE).withProperties(
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                    PropertyFactory.circleStrokeColor(AndroidColor.WHITE),
                    PropertyFactory.circleStrokeWidth(2f),
                ),
            )
            style = s
            cameraTick++
        }
    }

    LaunchedEffect(style, pins, line) {
        val s = style ?: return@LaunchedEffect
        val points = pins.map { pin ->
            Feature.fromGeometry(Point.fromLngLat(pin.lon, pin.lat)).apply {
                addStringProperty("color", colorString(if (pin.active) green else AndroidColor.WHITE))
            }
        }
        s.getSourceAs<GeoJsonSource>(PINS_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(points))
        val route = line?.let { (a, b) ->
            listOf(Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(a.lon, a.lat), Point.fromLngLat(b.lon, b.lat)))))
        }.orEmpty()
        s.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(route))
    }

    // Moves the traffic dots and the pulsing ring on every frame while there is something to show.
    LaunchedEffect(style, line, pins) {
        val s = style ?: return@LaunchedEffect
        val active = pins.firstOrNull { it.active }
        s.getSourceAs<GeoJsonSource>(HALO_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(active?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) })),
        )
        val packets = s.getSourceAs<GeoJsonSource>(PACKETS_SOURCE)
        val halo = s.getLayerAs<CircleLayer>(HALO_LAYER)
        if (line == null) packets?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        if (line == null && active == null) return@LaunchedEffect
        while (true) {
            val now = withFrameMillis { it }
            line?.let { (a, b) ->
                val t = (now % PACKET_TRIP_MS) / PACKET_TRIP_MS.toFloat()
                val dots = (0 until PACKET_COUNT).flatMap { k ->
                    (0..PACKET_TAIL).map { rank ->
                        val f = t + k.toFloat() / PACKET_COUNT - rank * 0.025f
                        val (lat, lon) = alongMercator(a, b, (f - kotlin.math.floor(f)).toDouble())
                        Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
                            addNumberProperty("r", if (rank == 0) 6f else 5f - rank)
                            addNumberProperty("o", if (rank == 0) 1f else 0.85f - rank * 0.17f)
                            addStringProperty("c", if (rank == 0) "#ffffff" else colorString(accent))
                        }
                    }
                }
                packets?.setGeoJson(FeatureCollection.fromFeatures(dots))
            }
            if (active != null) {
                val p = (now % HALO_MS) / HALO_MS.toFloat()
                halo?.setProperties(PropertyFactory.circleRadius(8f + 22f * p), PropertyFactory.circleOpacity(0.5f * (1 - p)))
            }
        }
    }

    // Fly to the place only when it changes, so the user's own panning is left alone.
    LaunchedEffect(map, centerLat, centerLon) {
        // Far enough out that the globe stays a sphere with both places on it.
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(centerLat, centerLon), 2.4), 1600)
    }

    BoxWithConstraints(modifier.background(Color.Black)) {
        AndroidView({ mapView }, Modifier.fillMaxSize())
        val m = map
        if (m != null && style != null) {
            val density = LocalDensity.current
            val points = remember(cameraTick, pins, m) { pins.map { m.projection.toScreenLocation(LatLng(it.lat, it.lon)) } }
            pins.zip(points).forEach { (pin, p) ->
                Box(Modifier.offset { IntOffset(p.x.toInt(), p.y.toInt() - with(density) { 40.dp.roundToPx() }) }) {
                    Text(
                        pin.label,
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .layout { measurable, constraints ->
                                // Centre the label over the point.
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height) { placeable.place(-placeable.width / 2, 0) }
                            }
                            .background(
                                if (pin.active) Color(green).copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.7f),
                                RoundedCornerShape(12.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

private const val PINS_SOURCE = "pins"
private const val PINS_LAYER = "pins-layer"
private const val ROUTE_SOURCE = "route"
private const val ROUTE_LAYER = "route-layer"
private const val PACKETS_SOURCE = "packets"
private const val PACKETS_LAYER = "packets-layer"
private const val HALO_SOURCE = "halo"
private const val HALO_LAYER = "halo-layer"
private const val PACKET_COUNT = 3
private const val PACKET_TAIL = 4
private const val PACKET_TRIP_MS = 4500L
private const val HALO_MS = 1600L

/**
 * The point a fraction [f] of the way from [a] to [b] along the straight line the map draws
 * between them, which is straight in Web Mercator rather than in degrees.
 */
private fun alongMercator(a: MapPin, b: MapPin, f: Double): Pair<Double, Double> {
    fun y(lat: Double) = ln(tan(Math.PI / 4 + Math.toRadians(lat) / 2))
    val my = y(a.lat) + (y(b.lat) - y(a.lat)) * f
    val lat = Math.toDegrees(2 * atan(exp(my)) - Math.PI / 2)
    return lat to a.lon + (b.lon - a.lon) * f
}

private fun colorString(argb: Int): String =
    "rgba(%d,%d,%d,1)".format(AndroidColor.red(argb), AndroidColor.green(argb), AndroidColor.blue(argb))
