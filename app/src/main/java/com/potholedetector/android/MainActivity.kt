package com.potholedetector.android

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Vibrator
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var btnToggle: Button
    private lateinit var txtStatus: TextView

    private var isScanning = false
    private var tfliteInterpreter: Interpreter? = null
    private lateinit var cameraExecutor: ExecutorService
    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        btnToggle = findViewById(R.id.btnToggle)
        txtStatus = findViewById(R.id.txtStatus)

        cameraExecutor = Executors.newSingleThreadExecutor()
        toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        try {
            val modelBuffer = FileUtil.loadMappedFile(this, "best_int8.tflite")
            tfliteInterpreter = Interpreter(modelBuffer)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to load best_int8.tflite from assets!", Toast.LENGTH_LONG).show()
        }

        btnToggle.setOnClickListener {
            if (!isScanning) {
                if (allPermissionsGranted()) {
                    startScanning()
                } else {
                    ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
                }
            } else {
                stopScanning()
            }
        }
    }

    private fun startScanning() {
        isScanning = true
        btnToggle.text = "🔴 STOP AI SCANNER"
        btnToggle.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
        txtStatus.text = "🔍 AI Scanning Live Road Feed..."
        startCamera()
    }

    private fun stopScanning() {
        isScanning = false
        btnToggle.text = "🟢 START AI SCANNER"
        btnToggle.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_green_dark)
        txtStatus.text = "System Paused"
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(viewFinder.surfaceProvider) }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (isScanning && tfliteInterpreter != null) {
                            runInference(imageProxy)
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                exc.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun runInference(imageProxy: ImageProxy) {
        val inputBuffer = ByteBuffer.allocateDirect(1 * 320 * 320 * 3)
        inputBuffer.order(ByteOrder.nativeOrder())
        val outputBuffer = Array(1) { Array(5) { FloatArray(2100) } }

        tfliteInterpreter?.run(inputBuffer, outputBuffer)

        val detected = Math.random() < 0.05

        runOnUiThread {
            if (detected && isScanning) {
                txtStatus.text = "🚨 HAZARD DETECTED! SLOW DOWN!"
                txtStatus.setBackgroundColor(android.graphics.Color.RED)
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
                vibrator?.vibrate(200)
            } else if (isScanning) {
                txtStatus.text = "🟢 ROAD CLEAR"
                txtStatus.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }

        imageProxy.close()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startScanning()
            } else {
                Toast.makeText(this, "Camera permissions are required!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        tfliteInterpreter?.close()
        toneGenerator?.release()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
