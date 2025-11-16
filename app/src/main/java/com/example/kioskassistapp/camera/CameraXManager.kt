package com.example.kioskassistapp.camera

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.util.Size // ◀◀◀ import 추가
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraXManager(
    private val context: Context,
    private val previewView: PreviewView
) {
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null

    fun startCamera(analyzer: ImageAnalysis.Analyzer) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)

                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, analyzer)
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    context as androidx.lifecycle.LifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e("CameraXManager", "카메라 시작 실패: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("RestrictedApi")
    fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
            cameraExecutor.shutdown()
        } catch (e: Exception) {
            Log.e("CameraXManager", "카메라 정지 오류: ${e.message}")
        }
    }
}