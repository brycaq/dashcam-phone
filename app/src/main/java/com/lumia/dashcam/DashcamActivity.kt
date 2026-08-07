package com.lumia.dashcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashcamActivity : AppCompatActivity() {

    private lateinit var mainRoadViewFinder: PreviewView
    private lateinit var cabinPipViewFinder: PreviewView
    private lateinit var btnRecordDash: ImageButton
    private lateinit var txtTimestamp: TextView

    private var roadVideoCapture: VideoCapture<Recorder>? = null
    private var cabinVideoCapture: VideoCapture<Recorder>? = null

    private var activeRoadRecording: Recording? = null
    private var activeCabinRecording: Recording? = null

    private var roadCamera: Camera? = null
    private var isCameraStarted = false

    private val handler = Handler(Looper.getMainLooper())
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashcam)

        mainRoadViewFinder = findViewById(R.id.frontViewFinder)
        cabinPipViewFinder = findViewById(R.id.rearViewFinder)
        btnRecordDash = findViewById(R.id.btnRecordDash)
        txtTimestamp = findViewById(R.id.txtTimestamp)

        mainRoadViewFinder.scaleType = PreviewView.ScaleType.FILL_CENTER

        startTimestampClock()

        btnRecordDash.setOnClickListener {
            toggleDashcamRecording()
        }

        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (hasPermissions() && !isCameraStarted) {
            startDualCamera()
        }
    }

    private fun checkAndRequestPermissions() {
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                101
            )
        } else {
            startDualCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startDualCamera()
            } else {
                Toast.makeText(this, "Camera & Audio permissions required for Dashcam", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startTimestampClock() {
        handler.post(object : Runnable {
            override fun run() {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                txtTimestamp.text = sdf.format(Date())
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun startDualCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                cameraProvider.availableConcurrentCameraInfos.isNotEmpty()
            ) {
                bindConcurrentCameras(cameraProvider)
            } else {
                bindSingleCamera(cameraProvider)
            }

            applyFisheyeWideAngle()
            isCameraStarted = true

        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindConcurrentCameras(cameraProvider: ProcessCameraProvider) {
        val roadPreview = Preview.Builder().build().also {
            it.setSurfaceProvider(mainRoadViewFinder.surfaceProvider)
        }

        val cabinPreview = Preview.Builder().build().also {
            it.setSurfaceProvider(cabinPipViewFinder.surfaceProvider)
        }

        val roadRecorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
        val cabinRecorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()

        roadVideoCapture = VideoCapture.withOutput(roadRecorder)
        cabinVideoCapture = VideoCapture.withOutput(cabinRecorder)

        try {
            cameraProvider.unbindAll()

            val roadGroup = CameraConfiguration(
                CameraSelector.DEFAULT_FRONT_CAMERA,
                UseCaseGroup.Builder().addUseCase(roadPreview).addUseCase(roadVideoCapture!!).build()
            )

            val cabinGroup = CameraConfiguration(
                CameraSelector.DEFAULT_BACK_CAMERA,
                UseCaseGroup.Builder().addUseCase(cabinPreview).addUseCase(cabinVideoCapture!!).build()
            )

            val cameras = cameraProvider.bindToLifecycle(
                this,
                listOf(roadGroup, cabinGroup)
            )

            if (cameras.isNotEmpty()) {
                roadCamera = cameras[0]
            }

        } catch (e: Exception) {
            Toast.makeText(this, "Concurrent dual capture unavailable on hardware", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindSingleCamera(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(mainRoadViewFinder.surfaceProvider)
        }
        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build()
        roadVideoCapture = VideoCapture.withOutput(recorder)

        try {
            cameraProvider.unbindAll()
            roadCamera = cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                roadVideoCapture
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start camera", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyFisheyeWideAngle() {
        roadCamera?.cameraInfo?.zoomState?.observe(this) { zoomState ->
            val minZoom = zoomState.minZoomRatio
            roadCamera?.cameraControl?.setZoomRatio(minZoom)
        }
    }

    private fun toggleDashcamRecording() {
        if (isRecording) {
            activeRoadRecording?.stop()
            activeCabinRecording?.stop()
            activeRoadRecording = null
            activeCabinRecording = null
            isRecording = false
            btnRecordDash.setColorFilter(null)
            Toast.makeText(this, "Dashcam clips saved", Toast.LENGTH_SHORT).show()
            return
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val roadFile = File(getExternalFilesDir(null), "DASH_ROAD_$timeStamp.mp4")
        val cabinFile = File(getExternalFilesDir(null), "DASH_CABIN_$timeStamp.mp4")

        roadVideoCapture?.let { cap ->
            val options = FileOutputOptions.Builder(roadFile).build()
            activeRoadRecording = cap.output
                .prepareRecording(this, options)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this)) {}
        }

        cabinVideoCapture?.let { cap ->
            val options = FileOutputOptions.Builder(cabinFile).build()
            activeCabinRecording = cap.output
                .prepareRecording(this, options)
                .start(ContextCompat.getMainExecutor(this)) {}
        }

        isRecording = true
        btnRecordDash.setColorFilter(0xFFFF0000.toInt())
        Toast.makeText(this, "Recording Dashcam Dual Stream...", Toast.LENGTH_SHORT).show()
    }

    private fun hasPermissions() = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ).all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
}
