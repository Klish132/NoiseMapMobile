package com.example.mapboxdemo

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.PersistableBundle
import android.widget.Toast
import com.mapbox.android.core.permissions.PermissionsListener
import com.mapbox.android.core.permissions.PermissionsManager
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.location.LocationComponent
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.expressions.Expression.*
import com.mapbox.mapboxsdk.style.layers.SymbolLayer
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import android.graphics.PointF
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.res.ResourcesCompat
import com.google.gson.GsonBuilder
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.style.layers.HeatmapLayer
import com.mapbox.mapboxsdk.style.layers.Property
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.*
import com.mapbox.mapboxsdk.utils.BitmapUtils
import okhttp3.*
import java.io.IOException


class MainActivity : AppCompatActivity(), OnMapReadyCallback, PermissionsListener {
    companion object {
        lateinit var instance : MainActivity
    }

    private lateinit var mapView: MapView
    private lateinit var map: MapboxMap
    private lateinit var source: GeoJsonSource
    private lateinit var featureCollection: FeatureCollection

    private lateinit var permissionManager: PermissionsManager
    private lateinit var locationComponent: LocationComponent

    private lateinit var signalRListener: SignalRListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        signalRListener = SignalRListener.getInstance()
        signalRListener.startConnection()

        Mapbox.getInstance(applicationContext, getString(R.string.mapbox_access_token))

        setContentView(R.layout.activity_main)
        mapView = findViewById(R.id.mpMainView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)
    }

    private fun getResponse(url : String, type: String) {
        val client = OkHttpClient()

        val request = Request.Builder()
            .get()
            .url(url)
            .build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.i("TAG", "ERROR: $e")
            }

            override fun onResponse(call: Call, response: Response) {
                //Log.i("TAG", "RESPONSE: ${response.body?.string()}")
                val responseStr = response.body?.string() ?: ""
                runOnUiThread {
                    analyzeResponse(responseStr, type)
                }
            }
        })
    }

    private fun analyzeResponse(response: String, type : String) {
        when (type) {
            "MARKERS" -> {
                val responseStr = response
                Log.i("TAG", "MARKERS RESPONSE: $responseStr")
                val gson = GsonBuilder().create()
                val model = gson.fromJson(responseStr, Array<CustomMarker>::class.java).toList()
                val featureList = mutableListOf<Feature>()
                model.forEach { marker ->
                    featureList.add(customMarkerToFeature(marker))
                }
                featureListToCollection(featureList)
            }
            "MARKER" -> {
                val responseStr = response
                Log.i("TAG", "MARKER RESPONSE: $responseStr")
                val gson = GsonBuilder().create()
                val model = gson.fromJson(responseStr, CustomMarker::class.java)
                featureCollection.features()?.add(customMarkerToFeature(model))
                refreshSource()
            }
            else -> Log.i("TAG", "ERROR ANALYZING")
        }
    }

    ///
    /// MARKER OPERATIONS
    ///

    data class CustomMarker(val id: Int, val x: String, val y: String, val markerType: Int, val title: String?, val volume: Int, val audioStatus : Int)

    fun removeMarkerById(id : Int) {
        try {
            val iterator = featureCollection.features()?.iterator()
            while (iterator!!.hasNext()) {
                if (iterator.next().getNumberProperty("id").toInt() == id) iterator.remove()
            }
            refreshSource()
        }
        catch(e : Exception) {
            Log.i("TAG", "REMOVING ERROR ${e}")
        }
    }

    fun updateMarkerById(id : Int) {
        removeMarkerById(id)
        requestMarkerById(id)
    }

    fun requestMarkerById(id : Int) {
        getResponse("http://192.168.0.169:5005/api/markers/${id}", "MARKER")
    }

    private fun requestAllMarkers() {
        getResponse("http://192.168.0.169:5005/api/markers/all", "MARKERS")
    }

    private fun customMarkerToFeature(marker : CustomMarker) : Feature {
        val geometry = Point.fromLngLat(marker.x.toDouble(), marker.y.toDouble())
        val feature = Feature.fromGeometry(geometry)
        feature.addNumberProperty("id", marker.id)
        if (marker.title != null)
            feature.addStringProperty("title", marker.title)
        else
            feature.addStringProperty("title", "New marker")
        feature.addNumberProperty("type", marker.markerType)
        feature.addNumberProperty("volume", marker.volume)
        feature.addNumberProperty("audio-status", marker.audioStatus)
        // TEMP
        feature.addStringProperty("poi", "cinema")

        return feature
    }

    private fun createMarkerLayer(loadedMapStyle: Style?) {
        featureCollection = FeatureCollection.fromFeatures(mutableListOf<Feature>())
        source = GeoJsonSource("my.data.source", featureCollection)
        loadedMapStyle!!.addSource(source)

        val myLayer = SymbolLayer("marker.layer", "my.data.source")
        myLayer
        val expr =
            myLayer.withProperties(
                //textField(get("volume")),
                textField(
                    match(
                        get("audio-status"),
                        literal("?"),
                        stop(0, "?"),
                        stop(1, get("volume"))
                    )
                ),
                textSize(
                    interpolate(
                        linear(), zoom(),
                        stop(5, 6),
                        stop(15, 18)
                    )
                ),
                iconImage(
                    match(get("type"),
                        literal("empty-point"),
                        stop(0, "empty-point"),
                        stop(1, "unchecked-point"),
                        stop(2, "checked-point")
                    )
                ),
                iconSize(
                    interpolate(
                        linear(), zoom(),
                        stop(5, 0.05),
                        stop(15, 0.15)
                    )
                ),
                iconAllowOverlap(true),
                textAllowOverlap(true),
                symbolZOrder(Property.SYMBOL_Z_ORDER_AUTO),
                symbolSortKey(get("id"))
            )
        loadedMapStyle!!.addLayer(myLayer)
        addClickListeners()
        Log.i("TAG", "MARKER LAYER READY")
    }

    private fun createHeatmapLayer(loadedMapStyle: Style?) {
        val myLayer = HeatmapLayer("heatmap.layer", "my.data.source")
        myLayer.sourceLayer = "my.data.source"
        myLayer.maxZoom = 20f
        val expr =
            myLayer.setProperties(
                heatmapColor(
                    interpolate(
                        linear(),
                        heatmapDensity(),
                        literal(0),
                        rgba(33.0, 102.0, 172.0, 0.0),
                        literal(0.2),
                        rgb(103.0, 169.0, 207.0),
                        literal(0.4),
                        rgb(209.0, 229.0, 240.0),
                        literal(0.6),
                        rgb(253.0, 219.0, 240.0),
                        literal(0.8),
                        rgb(239.0, 138.0, 98.0),
                        literal(1),
                        rgb(178.0, 24.0, 43.0)
                    )
                ),
                heatmapWeight(
                    interpolate(
                        linear(), get("volume"),
                        stop(0, 0),
                        stop(100, 1)
                    )
                ),
                heatmapIntensity(
                    interpolate(
                        linear(), zoom(),
                        stop(0, 1),
                        stop(15, 2)
                    )
                ),
                heatmapRadius(
                    interpolate(
                        linear(), zoom(),
                        stop(0, 2),
                        stop(5, 10),
                        stop(15, 80)
                    )
                ),
                heatmapOpacity(
                    interpolate(
                        linear(), zoom(),
                        stop(10, 1),
                        stop(20, 0)
                    )
                )
            )
        loadedMapStyle!!.addLayerBelow(myLayer, "marker.layer")
        Log.i("TAG", "HEATMAP LAYER READY")
    }

    private fun featureListToCollection(featureList: MutableList<Feature>) {
        featureCollection = FeatureCollection.fromFeatures(featureList)
        refreshSource()
    }

    ///
    ///
    ///

    override fun onMapReady(mapboxMap: MapboxMap) {
        this.map = mapboxMap
        val locale = resources.configuration.locale
        map.setStyle(Style.LIGHT) {
            style: Style ->
            val checked = BitmapUtils.getBitmapFromDrawable(ResourcesCompat.getDrawable(resources, R.drawable.checked_point, null))
            style.addImage("checked-point", checked!!)
            val unchecked = BitmapUtils.getBitmapFromDrawable(ResourcesCompat.getDrawable(resources, R.drawable.unchecked_point, null))
            style.addImage("unchecked-point", unchecked!!)
            val empty = BitmapUtils.getBitmapFromDrawable(ResourcesCompat.getDrawable(resources, R.drawable.empty_point, null))
            style.addImage("empty-point", empty!!)
            createMarkerLayer(style)
            createHeatmapLayer(style)
            enableLocationComponent(style)
            requestAllMarkers()
        }
    }

    private fun addClickListeners() {
        map.addOnMapClickListener { point ->
            val screenPoint = map.projection.toScreenLocation(point)
            val features = map.queryRenderedFeatures(screenPoint, "marker.layer")
            if (features.isNotEmpty()) {
                val selectedFeature = features[0]
                val symbolScreenPoint: PointF = map.projection.toScreenLocation(convertToLatLng(selectedFeature))
                handleMapClick(selectedFeature, screenPoint, symbolScreenPoint)
            }
            true
        }
    }

    private fun handleMapClick(selectedFeature : Feature, screenPoint : PointF, symbolScreenPoint : PointF) {
        val featureList = featureCollection.features()!!
        for (feature in featureList) {
            if (feature.getNumberProperty("id").toInt() == selectedFeature.getNumberProperty("id").toInt()) {
                refreshSource()
                createMarkerMenuActivity(feature)
            }
        }
    }

    private fun createMarkerMenuActivity(feature : Feature) {
        val markerMenuActivityIntent = Intent(this, MarkerMenu::class.java).apply {
            putExtra("featureJson", feature.toJson())
        }
        resultLauncher.launch(markerMenuActivityIntent)
    }

    var resultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // There are no request codes
            val data: Intent? = result.data
            if (data != null) {
                Log.i("TAG", "================= " + data.getStringExtra("featureJson"))
            }
        }
    }

    private fun refreshSource() {
        source.setGeoJson(featureCollection)
    }

    private fun convertToLatLng(feature: Feature): LatLng {
        val symbolPoint: Point = feature.geometry() as Point
        return LatLng(symbolPoint.latitude(), symbolPoint.longitude())
    }

    @SuppressWarnings("MissingPermission")
    private fun enableLocationComponent(loadedMapStyle: Style?) {
        // Check if permissions are enabled
        if (PermissionsManager.areLocationPermissionsGranted(this)) {
            // Allow the MapboxMap LocationComponent to show user location
            // Adding in LocationComponentOption is also an optional parameter
            locationComponent = map.locationComponent
            locationComponent.activateLocationComponent(this, loadedMapStyle!!)
            locationComponent.isLocationComponentEnabled = true

            // Set component's camera mode
            // locationComponent.cameraMode = CameraMode.TRACKING
            //locationComponent.renderMode = RenderMode.COMPASS
        }
        else {
            permissionManager = PermissionsManager(this)
            permissionManager.requestLocationPermissions(this)
        }
    }

    override fun onExplanationNeeded(permissionsToExplain: MutableList<String>?) {
        Toast.makeText(this, R.string.user_location_permission_explanation, Toast.LENGTH_SHORT).show()
    }

    override fun onPermissionResult(granted: Boolean) {
        if (granted) {
            enableLocationComponent(map.style)
        }
        else {
            Toast.makeText(this, R.string.user_location_permission_not_granted, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        Log.i("TAG", "STOPPED MAIN ACTIVITY")
        mapView.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle, outPersistentState: PersistableBundle) {
        super.onSaveInstanceState(outState, outPersistentState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }
}