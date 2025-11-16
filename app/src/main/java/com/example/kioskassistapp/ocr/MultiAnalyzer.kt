// File: MultiAnalyzer.kt
package com.example.kioskassistapp.ocr

import android.content.ContentValues.TAG
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.utils.MatrixExt.postRotate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MultiAnalyzer(
    private val context: Context,  // 👈 Context for HandLandmarker
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

    // ▼▼▼ [통합] HandLandmarkerHelper (제공된 코드 기반, IMAGE mode로 설정)
    private val handLandmarkerHelper = HandLandmarkerHelper(
        minHandDetectionConfidence = 0.5f,
        minHandTrackingConfidence = 0.5f,
        minHandPresenceConfidence = 0.5f,
        maxNumHands = 1,
        currentDelegate = HandLandmarkerHelper.DELEGATE_CPU,  // GPU는 테스트 후 변경
        runningMode = RunningMode.IMAGE,
        context = context
    )
    // ▲▲▲

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
                // 🔹 OCR Task (기존 그대로)
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

                // 🔹 Hand Task (HandLandmarkerHelper 사용)
                val bitmap = imageProxy.toBitmap()  // 👈 Extension 사용
                val matrix = Matrix().apply {
                    postRotate(rotation.toFloat())  // ImageProxy rotation 적용
                    // isFrontCamera면 플립 추가: postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
                }
                val rotatedBitmap = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                )
                val resultBundle = handLandmarkerHelper.detectImage(rotatedBitmap)  // detectImage 호출
                if (resultBundle != null && resultBundle.results.isNotEmpty()) {
                    val handResult = resultBundle.results[0]  // 첫 번째 손 결과
                    val landmarks = handResult.landmarks()  // List<NormalizedLandmark>
                    if (landmarks.isNotEmpty()) {
                        // 오른손 검지 끝 (index 8: RIGHT_INDEX_FINGER_TIP)
                        val rightIndexLandmark = landmarks[0][8]  // landmarks[hand][landmarkIndex]
                        // Normalized → Pixel 변환 (confidence 체크 제거)
                        val x = rightIndexLandmark.x() * imageWidth
                        val y = rightIndexLandmark.y() * imageHeight
                        fingerTip = PointF(x, y)
                        Log.d(TAG, "Detected Finger Tip: ($x, $y)")
                    } else {
                        Log.w(TAG, "No landmarks detected")
                    }
                } else {
                    Log.w(TAG, "No hand results")
                }
                latch.countDown()  // 👈 동기 detect이니 직접 countDown()

            } catch (e: Exception) {
                Log.e(TAG, "Analysis Error", e)
                latch.countDown()
                latch.countDown()
            }

            // 🔹 모든 Task 완료 대기
            try {
                if (latch.await(7, TimeUnit.SECONDS)) {  // Hand Landmarker 무거움 → 7초
                    // ▼▼▼ 모든 결과를 단일 콜백으로 전달 ▼▼▼
                    onAnalysisComplete(detectedTexts, fingerTip, imageWidth, imageHeight)
                    Log.d("OverlayDebug", "Original fingerPoint: $fingerTip (image: ${imageWidth}x${imageHeight})")
                } else {
                    Log.w(TAG, "Timeout on tasks")
                }
            } catch (e: InterruptedException) {
                Log.e(TAG, "Latch await interrupted", e)
            } finally {
                imageProxy.close()
                // Bitmap 해제 (메모리 누수 방지)
                // bitmap?.recycle()  // 필요 시 추가
            }
        }
    }

    // ▼▼▼ [임베드] HandLandmarkerHelper 클래스 (제공된 코드 기반, Listener 제거 – IMAGE mode라 불필요)
    private class HandLandmarkerHelper(
        var minHandDetectionConfidence: Float = DEFAULT_HAND_DETECTION_CONFIDENCE,
        var minHandTrackingConfidence: Float = DEFAULT_HAND_TRACKING_CONFIDENCE,
        var minHandPresenceConfidence: Float = DEFAULT_HAND_PRESENCE_CONFIDENCE,
        var maxNumHands: Int = DEFAULT_NUM_HANDS,
        var currentDelegate: Int = DELEGATE_CPU,
        var runningMode: RunningMode = RunningMode.IMAGE,
        val context: Context
    ) {
        private var handLandmarker: HandLandmarker? = null
        init {
            setupHandLandmarker()
        }

        fun clearHandLandmarker() {
            handLandmarker?.close()
            handLandmarker = null
        }

        fun isClose(): Boolean = handLandmarker == null

        fun setupHandLandmarker() {
            val baseOptionBuilder = BaseOptions.builder()
            when (currentDelegate) {
                DELEGATE_CPU -> baseOptionBuilder.setDelegate(Delegate.CPU)
                DELEGATE_GPU -> baseOptionBuilder.setDelegate(Delegate.GPU)
            }
            baseOptionBuilder.setModelAssetPath(MP_HAND_LANDMARKER_TASK)
            try {
                val baseOptions = baseOptionBuilder.build()
                val optionsBuilder = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinHandDetectionConfidence(minHandDetectionConfidence)
                    .setMinTrackingConfidence(minHandTrackingConfidence)
                    .setMinHandPresenceConfidence(minHandPresenceConfidence)
                    .setNumHands(maxNumHands)
                    .setRunningMode(runningMode)
                val options = optionsBuilder.build()
                handLandmarker = HandLandmarker.createFromOptions(context, options)
            } catch (e: Exception) {
                Log.e(TAG, "Hand Landmarker failed to initialize: ${e.message}")
            }
        }

        // IMAGE mode용 detectImage (제공된 코드 기반)
        fun detectImage(image: Bitmap): ResultBundle? {
            if (runningMode != RunningMode.IMAGE) {
                throw IllegalArgumentException("RunningMode must be IMAGE")
            }
            val startTime = SystemClock.uptimeMillis()
            val mpImage = BitmapImageBuilder(image).build()
            handLandmarker?.detect(mpImage)?.also { landmarkResult ->
                val inferenceTimeMs = SystemClock.uptimeMillis() - startTime
                return ResultBundle(
                    listOf(landmarkResult),
                    inferenceTimeMs,
                    image.height,
                    image.width
                )
            }
            return null
        }

        companion object {
            private const val MP_HAND_LANDMARKER_TASK = "hand_landmarker.task"  // 👈 assets/ 파일
            const val DELEGATE_CPU = 0
            const val DELEGATE_GPU = 1
            const val DEFAULT_HAND_DETECTION_CONFIDENCE = 0.5f
            const val DEFAULT_HAND_TRACKING_CONFIDENCE = 0.5f
            const val DEFAULT_HAND_PRESENCE_CONFIDENCE = 0.5f
            const val DEFAULT_NUM_HANDS = 1
        }

        data class ResultBundle(
            val results: List<HandLandmarkerResult>,
            val inferenceTime: Long,
            val inputImageHeight: Int,
            val inputImageWidth: Int
        )
    }
    // ▲▲▲
}