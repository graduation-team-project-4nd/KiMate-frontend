// File: MultiAnalyzer.kt
package com.example.kioskassistapp.ocr

import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MultiAnalyzer(
    /**
     * 모든 분석(OCR, Pose)이 완료되었을 때 호출되는 단일 콜백
     * @param textsAndBoxes (텍스트 String, 박스 Rect) 쌍의 리스트
     * @param fingerTip 감지된 손가락 좌표 (없으면 null)
     * @param imageWidth 분석에 사용된 이미지의 너비
     * @param imageHeight 분석에 사용된 이미지의 높이
     */
    private val onAnalysisComplete: (List<Pair<String, Rect>>, PointF?, Int, Int) -> Unit
) : ImageAnalysis.Analyzer {

    private val executor = Executors.newFixedThreadPool(2)
    private val TAG = "MultiAnalyzer"

    private val ocrRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val poseOptions = AccuratePoseDetectorOptions.Builder()
        .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
        .build()
    private val poseDetector = PoseDetection.getClient(poseOptions)
    // ▼▼▼ 마지막 분석 시간을 저장할 변수 추가 ▼▼▼
    private var lastAnalysisTime = 0L

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        // ▼▼▼ 3초 간격 체크 로직 추가 ▼▼▼
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnalysisTime < 3000) { // 3000ms = 3초
            imageProxy.close() // 3초가 안 지났으면 프레임 닫고 즉시 종료
            return
        }
        // 3초가 지났으면, 현재 시간을 마지막 분석 시간으로 기록
        lastAnalysisTime = currentTime
        // ▲▲▲
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        // ▼▼▼ 이미지 크기를 콜백으로 전달하기 위해 저장 ▼▼▼
        val imageWidth = inputImage.width
        val imageHeight = inputImage.height
        // ▲▲▲

        val latch = CountDownLatch(2)

        // 결과를 담을 임시 변수
        val detectedTexts = mutableListOf<Pair<String, Rect>>()
        var fingerTip: PointF? = null

        executor.execute {
            try {
                // 🔹 OCR Task
                ocrRecognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        for (block in visionText.textBlocks) {
                            block.boundingBox?.let { box ->
                                // 텍스트와 Rect를 Pair로 묶어 리스트에 추가
                                detectedTexts.add(block.text to box)
                                Log.d(TAG, "Detected Text: '${block.text}' at $box")
                            }
                        }
                    }
                    .addOnFailureListener { Log.e(TAG, "OCR Failure", it) }
                    .addOnCompleteListener { latch.countDown() }

                // 🔹 Pose Task
                poseDetector.process(inputImage)
                    .addOnSuccessListener { pose: Pose ->
                        for (landmark in pose.allPoseLandmarks) {
                            if (landmark.landmarkType == PoseLandmark.RIGHT_INDEX) {
                                val confidence = landmark.inFrameLikelihood  // 👈 이거 추가!
                                if (confidence > 0.5f) {  // 임계값 설정
                                    fingerTip = landmark.position
                                    Log.d(TAG, "Detected Finger Tip: (${fingerTip!!.x}, ${fingerTip!!.y}) with confidence: $confidence")
                                } else {
                                    Log.w(TAG, "Low confidence for RIGHT_INDEX: $confidence – ignoring")
                                    fingerTip = null
                                }
                                break
                            }
                        }
                        if (fingerTip != null) {
                            Log.d(TAG, "Detected Finger Tip: (${fingerTip!!.x}, ${fingerTip!!.y})")
                        }
                    }
                    .addOnFailureListener { Log.e(TAG, "Pose Failure", it) }
                    .addOnCompleteListener { latch.countDown() }

            } catch (e: Exception) {
                Log.e(TAG, "Analysis Error", e)
                latch.countDown()
                latch.countDown()
            }

            // 🔹 모든 Task 완료 대기
            try {
                if (latch.await(5, TimeUnit.SECONDS)) {
                    // ▼▼▼ 모든 결과를 단일 콜백으로 전달 ▼▼▼
                    onAnalysisComplete(detectedTexts, fingerTip, imageWidth, imageHeight)
                    Log.d("OverlayDebug", "Original fingerPoint: $fingerTip (image: $$ {imageWidth}x $${imageHeight})")
                } else {
                    Log.w(TAG, "Timeout on tasks")
                }
            } catch (e: InterruptedException) {
                Log.e(TAG, "Latch await interrupted", e)
            } finally {
                imageProxy.close()
            }
        }
    }
}