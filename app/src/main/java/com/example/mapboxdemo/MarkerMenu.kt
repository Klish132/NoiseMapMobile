package com.example.mapboxdemo

import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.res.ResourcesCompat
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.maps.*
import com.mapbox.mapboxsdk.geometry.LatLng

import com.mapbox.mapboxsdk.camera.CameraPosition

import com.mapbox.mapboxsdk.maps.MapboxMapOptions
import com.mapbox.mapboxsdk.style.expressions.Expression.*
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.*
import com.mapbox.mapboxsdk.style.layers.SymbolLayer
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import com.mapbox.mapboxsdk.utils.BitmapUtils
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.math.log10
import kotlin.math.roundToInt


class MarkerMenu : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var btnRecord: Button
    private lateinit var btnStopRecording: Button
    private lateinit var btnPlayServer: Button
    private lateinit var btnStopPlayingServer: Button
    private lateinit var btnPlayDisk: Button
    private lateinit var btnStopPlayingDisk: Button

    private lateinit var btnDeleteDisk: Button
    private lateinit var btnSaveChanges: Button

    private lateinit var tvVolume: TextView

    private var recorder: MediaRecorder? = null
    private var playerServer: MediaPlayer? = null
    private var playerDisk: MediaPlayer? = null

    private var audioFilePath: String = ""

    private lateinit var map: MapboxMap
    private lateinit var mapFragment: SupportMapFragment
    private lateinit var source: GeoJsonSource
    private lateinit var featureCollection: FeatureCollection

    private lateinit var feature: Feature

    private var permissionToRecordAccepted = false
    private var permissions: Array<String> = arrayOf(android.Manifest.permission.RECORD_AUDIO)

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionToRecordAccepted = if (requestCode == 200) {
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
        if (!permissionToRecordAccepted) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ActivityCompat.requestPermissions(this, permissions, 200)

        setContentView(R.layout.activity_marker_menu)

        initializeButtons()
        getFeature()

        Mapbox.getInstance(applicationContext, getString(R.string.mapbox_access_token))

        if (savedInstanceState == null) {
            val transaction = supportFragmentManager.beginTransaction()

            val options = MapboxMapOptions.createFromAttributes(this, null)
            val featurePosition = feature.geometry() as Point
            options.camera(
                CameraPosition.Builder()
                    .target(LatLng(featurePosition.latitude(), featurePosition.longitude()))
                    .zoom(15.0)
                    .build()
            )

            mapFragment = SupportMapFragment.newInstance(options)
            transaction.add(R.id.container, mapFragment, "com.mapbox.map")
            transaction.commit()
        } else {
            mapFragment = supportFragmentManager.findFragmentByTag("com.mapbox.map") as SupportMapFragment
        }

        mapFragment.getMapAsync(this)
    }

    private fun sendAudio(file : File, audioName : String, url : String) {
        val client = OkHttpClient()

        val requestBody = MultipartBody.Builder().apply {
            setType(MultipartBody.FORM)
            addFormDataPart("file", audioName, file.asRequestBody("audio/mpeg".toMediaTypeOrNull()))
        }.build()
        val request = Request.Builder().apply {
            url(url)
            post(requestBody)
        }.build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.i("TAG", "ERROR: $e")
            }

            override fun onResponse(call: Call, response: Response) {
                Log.i("TAG", "SUCCESS SENDING AUDIO")
            }
        })
    }

    private fun sendPutMarker() {
        val client = OkHttpClient()
        val mediaType = "application/json; charset=utf-8".toMediaType()

        val obj : JSONObject = JSONObject().apply {
            put("id", feature.getNumberProperty("id").toInt())
            val geo = feature.geometry() as Point
            put("x", geo.longitude().toString())
            put("y", geo.latitude().toString())
            put("markerType", feature.getNumberProperty("type").toInt())
            put("title", feature.getStringProperty("title"))
            put("volume", feature.getNumberProperty("volume").toInt())
            put("audioStatus", feature.getNumberProperty("audio-status").toInt())
        }

        Log.i("TAG", "obj: " + obj.toString())
        val body = obj.toString().toRequestBody(mediaType)

        val request = Request.Builder().apply {
            url("http://192.168.0.169:5005/api/markers/edit")
            put(body)
        }.build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.i("TAG", "ERROR: $e")
            }

            override fun onResponse(call: Call, response: Response) {
                Log.i("TAG", "SUCCESS SENDING PUT REQUEST")
            }
        })
    }

    private fun getFeature() {
        val featureJson  = intent.getStringExtra("featureJson")

        if (featureJson != null) {
            feature = Feature.fromJson(featureJson)

            val tvTitle = findViewById<TextView>(R.id.tvTitle)
            tvTitle.text = feature.getStringProperty("title")

            tvVolume = findViewById(R.id.tvVolume)
            val str = feature.getNumberProperty("volume").toString() + " дБ"
            tvVolume.text = str

            val audioStatus = feature.getNumberProperty("audio-status").toInt()
            Log.i("TAG", (0 == audioStatus).toString())
            if (audioStatus == 0) {
                btnRecord.visibility = View.VISIBLE
                tvVolume.visibility = View.INVISIBLE
            } else {
                btnPlayServer.visibility = View.VISIBLE
                btnSaveChanges.visibility = View.INVISIBLE
            }
        }
    }

    private fun initializeButtons() {
        btnRecord = findViewById(R.id.btnRecord)
        btnRecord.setOnClickListener {
            record()
        }
        btnStopRecording = findViewById(R.id.btnStopRecording)
        btnStopRecording.setOnClickListener {
            stopRecording()
        }
        btnPlayServer = findViewById(R.id.btnPlayServer)
        btnPlayServer.setOnClickListener {
            playServer()
        }
        btnStopPlayingServer = findViewById(R.id.btnStopPlayingServer)
        btnStopPlayingServer.setOnClickListener {
            stopPlayingServer()
        }
        btnPlayDisk = findViewById(R.id.btnPlayDisk)
        btnPlayDisk.setOnClickListener {
            playDisk()
        }
        btnStopPlayingDisk = findViewById(R.id.btnStopPlayingDisk)
        btnStopPlayingDisk.setOnClickListener {
            stopPlayingDisk()
        }
        btnDeleteDisk = findViewById(R.id.btnDeleteDisk)
        btnDeleteDisk.setOnClickListener {
            deleteDisk()
        }
        btnSaveChanges = findViewById(R.id.btnSave)
        btnSaveChanges.setOnClickListener {
            saveChanges()
        }
    }

    private fun record() {
        btnRecord.visibility = View.INVISIBLE
        btnStopRecording.visibility = View.VISIBLE

        audioFilePath = "${externalCacheDir?.absolutePath}/${feature.getStringProperty("id")}.mp3"
        Log.i("TAG", "======================================== $audioFilePath")

        recorder = MediaRecorder()

        recorder!!.apply {
            try {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(audioFilePath)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                prepare()
                start()
            } catch (e: IOException) {
                Log.e("TAG", "record failed")
            }
        }
        Log.i("TAG", getNoiseLevel().toString())
    }

    private fun stopRecording() {
        btnPlayDisk.visibility = View.VISIBLE
        btnStopRecording.visibility = View.INVISIBLE
        tvVolume.visibility = View.VISIBLE
        btnDeleteDisk.visibility = View.VISIBLE
        btnSaveChanges.isEnabled = true

        val tvVolume = findViewById<TextView>(R.id.tvVolume)
        val db = getNoiseLevel()
        val str = "$db дБ"
        tvVolume.text = str

        feature.addNumberProperty("volume", db)
        feature.addNumberProperty("audio-status", 1)
        feature.addNumberProperty("type", 1)
        featureCollection = FeatureCollection.fromFeature(feature)
        refreshSource()

        recorder?.apply {
            stop()
            reset()
            release()
        }
        recorder = null
    }

    private fun getNoiseLevel() : Int {
        val amp = recorder?.maxAmplitude ?: 0
        val ref = 1.2
        val db = (20 * log10(amp / ref)).roundToInt()
        return if (db > 0) db else 0
    }

    private fun playDisk() {
        btnPlayDisk.visibility = View.INVISIBLE
        btnStopPlayingDisk.visibility = View.VISIBLE
        btnDeleteDisk.isEnabled = false
        btnSaveChanges.isEnabled = false

        playerDisk = MediaPlayer()

        playerDisk!!.apply {
            try {
                setOnCompletionListener { stopPlayingDisk() }
                setAudioAttributes(AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                setDataSource(audioFilePath)
                prepare()
                start()
            } catch (e: IOException) {
                Log.e("TAG", "play failed")
            }
        }
    }

    private fun stopPlayingDisk() {
        btnPlayDisk.visibility = View.VISIBLE
        btnStopPlayingDisk.visibility = View.INVISIBLE
        btnDeleteDisk.isEnabled = true
        btnSaveChanges.isEnabled = true

        playerDisk?.apply {
            stop()
            reset()
            release()
        }
        playerDisk = null
    }

    private fun playServer() {
        btnPlayServer.visibility = View.INVISIBLE
        btnStopPlayingServer.visibility = View.VISIBLE

        playerServer = MediaPlayer()

        playerServer!!.apply {
            try {
                setOnCompletionListener { stopPlayingServer() }
                setAudioAttributes(AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                setDataSource("http://192.168.0.169:5005/api/markers/audio/${feature.getStringProperty("id")}")
                prepare()
                start()
            } catch (e: IOException) {
                Log.e("TAG", "play failed")
            }
        }
    }

    private fun stopPlayingServer() {
        btnPlayServer.visibility = View.VISIBLE
        btnStopPlayingServer.visibility = View.INVISIBLE

        playerServer?.apply {
            stop()
            reset()
            release()
        }
        playerServer = null
    }

    private fun deleteDisk() {
        val file = File(audioFilePath)
        if (file.exists()) {
            file.canonicalFile.delete()
        }
        audioFilePath = ""
        tvVolume.text = "0 дБ"

        feature.addNumberProperty("volume", 0)
        feature.addNumberProperty("audio-status", 0)
        feature.addNumberProperty("type", 0)
        featureCollection = FeatureCollection.fromFeature(feature)
        refreshSource()
        btnPlayDisk.visibility = View.INVISIBLE
        btnDeleteDisk.visibility = View.INVISIBLE
        btnRecord.visibility = View.VISIBLE
        btnSaveChanges.isEnabled = false
    }

    private fun saveChanges() {
        btnPlayDisk.visibility = View.INVISIBLE
        btnDeleteDisk.visibility = View.INVISIBLE
        btnPlayServer.visibility = View.VISIBLE
        btnSaveChanges.text = "Сохранено!"
        btnSaveChanges.isEnabled = false
        val fileName = "${feature.getStringProperty("id")}.mp3"
        sendAudio(File("${externalCacheDir?.absolutePath}/$fileName"), fileName, "http://192.168.0.169:5005/api/markers/audio/add")
        sendPutMarker()
    }

    private fun createMarkerLayer(loadedMapStyle: Style?) {
        featureCollection = FeatureCollection.fromFeature(feature)
        source = GeoJsonSource("marker.menu.source.id", featureCollection)
        loadedMapStyle!!.addSource(source)

        val myLayer = SymbolLayer("marker.menu.layer", "marker.menu.source.id")
        myLayer.withProperties(
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
                match(
                    get("type"),
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
        )
        loadedMapStyle!!.addLayer(myLayer)
    }
    private fun refreshSource() {
        source.setGeoJson(featureCollection)
    }

    override fun onMapReady(mapboxMap: MapboxMap) {
        this.map = mapboxMap
        map.uiSettings.setAllGesturesEnabled(false);
        map.setStyle(Style.LIGHT) {
            style: Style ->
            val checked = BitmapUtils.getBitmapFromDrawable(ResourcesCompat.getDrawable(resources, R.drawable.checked_point, null))
            style.addImage("checked-point", checked!!)
            val unchecked = BitmapUtils.getBitmapFromDrawable(ResourcesCompat.getDrawable(resources, R.drawable.unchecked_point, null))
            style.addImage("unchecked-point", unchecked!!)
            val empty = BitmapUtils.getBitmapFromDrawable(ResourcesCompat.getDrawable(resources, R.drawable.empty_point, null))
            style.addImage("empty-point", empty!!)
            createMarkerLayer(style)
        }
    }
}