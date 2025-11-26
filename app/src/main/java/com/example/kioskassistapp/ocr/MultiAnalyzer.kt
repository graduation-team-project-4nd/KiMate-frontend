// File: MultiAnalyzer.kt
package com.example.kioskassistapp.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MultiAnalyzer(
    private val context: Context,
    /**
     * 모든 분석(OCR, Pose)이 완료되었을 때 호출되는 단일 콜백
     * @param textsAndBoxes (텍스트 String, 박스 Rect) 쌍의 리스트 (확대된 좌표 기준)
     * @param fingerTip 감지된 손가락 좌표 (없으면 null) (확대된 좌표 기준)
     * @param imageWidth 분석에 사용된 이미지의 너비 (확대된 너비)
     * @param imageHeight 분석에 사용된 이미지의 높이 (확대된 높이)
     */
    private val onAnalysisComplete: (List<Pair<String, Rect>>, PointF?, Int, Int) -> Unit
) : ImageAnalysis.Analyzer {

    private val executor = Executors.newFixedThreadPool(2)
    private val TAG = "MultiAnalyzer"

    // 한글 인식기 옵션
    private val ocrRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    // HandLandmarkerHelper 초기화
    private val handLandmarkerHelper = HandLandmarkerHelper(
        minHandDetectionConfidence = HandLandmarkerHelper.DEFAULT_HAND_DETECTION_CONFIDENCE,
        minHandTrackingConfidence = HandLandmarkerHelper.DEFAULT_HAND_TRACKING_CONFIDENCE,
        minHandPresenceConfidence = HandLandmarkerHelper.DEFAULT_HAND_PRESENCE_CONFIDENCE,
        maxNumHands = HandLandmarkerHelper.DEFAULT_NUM_HANDS,
        currentDelegate = HandLandmarkerHelper.DELEGATE_CPU,
        runningMode = RunningMode.IMAGE,
        context = context
    )

    private var lastAnalysisTime = 0L

    // ▼▼▼ [핵심] 인식률 향상을 위한 확대 배율 (1.5배 추천) ▼▼▼
    private val SCALE_FACTOR = 1.5f

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnalysisTime < 1500) {
            imageProxy.close()
            return
        }
        lastAnalysisTime = currentTime

        // 1. ImageProxy를 Bitmap으로 변환 (회전 정보 적용)
        val bitmap = imageProxy.toBitmap()
        val rotation = imageProxy.imageInfo.rotationDegrees
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
        }

        // 회전이 적용된 원본 Bitmap
        val originalBitmap = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )

        // 2. OCR 인식률을 높이기 위해 이미지 확대 (Upscaling)
        // createScaledBitmap의 마지막 인자 true는 안티앨리어싱(부드럽게 처리) 적용
        val scaledWidth = (originalBitmap.width * SCALE_FACTOR).toInt()
        val scaledHeight = (originalBitmap.height * SCALE_FACTOR).toInt()

        val scaledBitmap = Bitmap.createScaledBitmap(
            originalBitmap,
            scaledWidth,
            scaledHeight,
            true
        )

        // 분석 기준 크기는 이제 '확대된 이미지' 크기입니다.
        val processWidth = scaledWidth
        val processHeight = scaledHeight

        // 이미지 변환이 끝났으므로 imageProxy는 닫아도 됩니다. (메모리 절약)
        imageProxy.close()

        val latch = CountDownLatch(2)

        // 결과를 담을 임시 변수
        val detectedTexts = mutableListOf<Pair<String, Rect>>()
        var fingerTip: PointF? = null

        executor.execute {
            try {
                // 🔹 OCR Task (확대된 scaledBitmap 사용)
                val inputImage = InputImage.fromBitmap(scaledBitmap, 0)

                ocrRecognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        for (block in visionText.textBlocks) {
                            block.boundingBox?.let { box ->
                                // box는 scaledBitmap 기준의 좌표 (이미 확대된 상태)
                                detectedTexts.add(block.text to box)
                                Log.d(TAG, "OCR Text: '${block.text}' at $box")
                            }
                        }
                    }
                    .addOnFailureListener { Log.e(TAG, "OCR Failure", it) }
                    .addOnCompleteListener { latch.countDown() }

                // 🔹 Hand Task (속도를 위해 originalBitmap 사용 권장)
                // 손 인식은 해상도보다 특징점이 중요하므로 원본을 써도 충분합니다.
                val resultBundle = handLandmarkerHelper.detectImage(originalBitmap)

                if (resultBundle != null && resultBundle.results.isNotEmpty()) {
                    val handResult = resultBundle.results[0]
                    val landmarks = handResult.landmarks()
                    if (landmarks.isNotEmpty()) {
                        // 오른손 검지 끝 (index 8: RIGHT_INDEX_FINGER_TIP)
                        val rightIndexLandmark = landmarks[0][8]

                        // Normalized(0~1) -> Pixel 변환 (원본 크기 기준)
                        val originalX = rightIndexLandmark.x() * originalBitmap.width
                        val originalY = rightIndexLandmark.y() * originalBitmap.height

                        // ▼▼▼ [좌표 보정] OCR 결과(확대됨)와 좌표계를 맞추기 위해 확대 배율을 곱함 ▼▼▼
                        val scaledX = originalX * SCALE_FACTOR
                        val scaledY = originalY * SCALE_FACTOR

                        fingerTip = PointF(scaledX, scaledY)
                        Log.d(TAG, "Finger Tip: ($scaledX, $scaledY)")
                    }
                }
                latch.countDown()

            } catch (e: Exception) {
                Log.e(TAG, "Analysis Error", e)
                latch.countDown()
                latch.countDown()
            }

            // 🔹 모든 Task 완료 대기
            try {
                if (latch.await(5, TimeUnit.SECONDS)) {
                    // UI에는 확대된 크기(processWidth, processHeight)와 그에 맞는 좌표들을 전달
                    onAnalysisComplete(detectedTexts, fingerTip, processWidth, processHeight)
                } else {
                    Log.w(TAG, "Timeout on tasks")
                }
            } catch (e: InterruptedException) {
                Log.e(TAG, "Latch await interrupted", e)
            } finally {
                // 비트맵 메모리 해제 시도 (선택 사항)
                // originalBitmap.recycle()
                // scaledBitmap.recycle()
            }
        }
    }

    // ▼▼▼ Helper 클래스 (기존 유지) ▼▼▼
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
                Log.e("HandLandmarkerHelper", "Hand Landmarker failed to initialize: ${e.message}")
            }
        }

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
            private const val MP_HAND_LANDMARKER_TASK = "hand_landmarker.task"
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
}