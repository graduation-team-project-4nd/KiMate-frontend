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
    // "웬만하면 손이 있다"고 판단하기 위해 신뢰도(Confidence)를 0.3으로 설정했습니다.
    private val handLandmarkerHelper = HandLandmarkerHelper(
        minHandDetectionConfidence = 0.5f,
        minHandTrackingConfidence = 0.5f,
        minHandPresenceConfidence = 0.001f,
        maxNumHands = HandLandmarkerHelper.DEFAULT_NUM_HANDS,
        currentDelegate = HandLandmarkerHelper.DELEGATE_CPU,
        runningMode = RunningMode.IMAGE,
        context = context
    )

    private var lastAnalysisTime = 0L
    // 텍스트와 숫자를 분리하기 위한 정규식 (숫자가 아닌 것 / 숫자(콤마 포함))
    // 예: "불고기버거6,500" -> "불고기버거", "6,500"
    private val splitPattern = Regex("([^0-9]+)([0-9,]+)|([0-9,]+)([^0-9]+)")
    // ▼▼▼ [핵심] 인식률 향상을 위한 확대 배율 (1.5배 추천) ▼▼▼
    private val SCALE_FACTOR = 2f

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        // 분석 주기: 10ms는 너무 빠를 수 있어 500ms~1000ms 정도를 권장하지만,
        // 요청하신 코드의 10ms 로직이 필요하다면 유지합니다. (여기서는 안전하게 500ms로 조정하거나 원본 유지 가능)
        // 일단 원본 흐름을 유지하되, 너무 잦은 호출 방지를 위해 최소한의 텀은 필요할 수 있습니다.
        if (currentTime - lastAnalysisTime < 10) {
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
        val scaledWidth = (originalBitmap.width * SCALE_FACTOR).toInt()
        val scaledHeight = (originalBitmap.height * SCALE_FACTOR).toInt()

        val scaledBitmap = Bitmap.createScaledBitmap(
            originalBitmap,
            scaledWidth,
            scaledHeight,
            true
        )

        val processWidth = scaledWidth
        val processHeight = scaledHeight

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
                        // Block 단위가 아니라 Line 단위로 더 세밀하게 봅니다.
                        for (block in visionText.textBlocks) {
                            for (line in block.lines) {
                                val lineBox = line.boundingBox ?: continue
                                val lineText = line.text

                                // 1. 텍스트에 숫자와 문자가 섞여 있는지 확인
                                // (순수 텍스트거나 순수 숫자면 분리할 필요 없음)
                                if (lineText.any { it.isDigit() } && lineText.any { !it.isDigit() && it != ',' && it != '.' }) {

                                    // 2. 섞여 있다면 분리 로직 실행 (좌표까지 추정해서 나눔)
                                    val splitResults = splitTextAndRect(lineText, lineBox)
                                    detectedTexts.addAll(splitResults)

                                    splitResults.forEach {
                                        Log.d(TAG, "Split Result: '${it.first}' at ${it.second}")
                                    }

                                } else {
                                    // 3. 섞여있지 않다면 원본 그대로 추가
                                    detectedTexts.add(lineText to lineBox)
                                    Log.d(TAG, "OCR Text: '$lineText' at $lineBox")
                                }
                            }
                        }
                    }
                    .addOnFailureListener { Log.e(TAG, "OCR Failure", it) }
                    .addOnCompleteListener { latch.countDown() }

                // 🔹 Hand Task
                val resultBundle = handLandmarkerHelper.detectImage(scaledBitmap)

                if (resultBundle != null && resultBundle.results.isNotEmpty()) {
                    val handResult = resultBundle.results[0]
                    val landmarks = handResult.landmarks()

                    if (landmarks.isNotEmpty()) {
                        val handLandmarks = landmarks[0]

                        // ▼▼▼ [수정됨] 5개 손가락 끝(Tip) 좌표를 모두 가져옴 ▼▼▼
                        // 4:엄지, 8:검지, 12:중지, 16:약지, 20:소지
                        val fingerTips = listOf(
//                            handLandmarks[4],  // Thumb
                            handLandmarks[8],  // Index
//                            handLandmarks[12], // Middle
//                            handLandmarks[16], // Ring
//                            handLandmarks[20]  // Pinky
                        )

                        // 5개의 손가락 중 화면 가장 위쪽(Y좌표가 가장 작은 값)에 있는 손가락을 선택
                        // (키오스크를 누르거나 가리킬 때 가장 튀어나온 손가락을 인식)
                        val activeFinger = fingerTips.minByOrNull { it.y() }

                        if (activeFinger != null) {
                            // Normalized(0~1) -> Pixel 변환 (원본 크기 기준)
                            val originalX = activeFinger.x() * originalBitmap.width
                            val originalY = activeFinger.y() * originalBitmap.height

                            // ▼▼▼ [좌표 보정] OCR 결과(확대됨)와 좌표계를 맞추기 위해 확대 배율을 곱함 ▼▼▼
                            val scaledX = originalX * SCALE_FACTOR
                            val scaledY = originalY * SCALE_FACTOR

                            fingerTip = PointF(scaledX, scaledY)
                            Log.d(TAG, "Finger Tip (Active): ($scaledX, $scaledY)")
                        }
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
                // 비트맵 메모리 해제 시도 (필요 시 주석 해제)
                // originalBitmap.recycle()
                // scaledBitmap.recycle()
            }
        }
    }
    private fun splitTextAndRect(text: String, originalRect: Rect): List<Pair<String, Rect>> {
        val results = mutableListOf<Pair<String, Rect>>()

        // 정규식으로 숫자와 비숫자 경계 찾기
        // (?<=\D)(?=\d) : 숫자가 아닌 것 뒤에 숫자가 오는 지점
        // (?<=\d)(?=\D) : 숫자 뒤에 숫자가 아닌 것이 오는 지점
        val parts = text.split(Regex("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)"))

        var currentX = originalRect.left.toFloat()
        val totalLength = text.length
        val totalWidth = originalRect.width()

        for (part in parts) {
            val trimmedPart = part.trim()
            if (trimmedPart.isEmpty()) continue

            // 비율대로 박스 너비 계산 (단순 글자 수 비례)
            // 예: "메뉴6500" (총6자) -> "메뉴"(2자)는 33%, "6500"(4자)는 66% 너비 차지
            val partRatio = part.length.toFloat() / totalLength
            val partWidth = totalWidth * partRatio

            val partRect = Rect(
                currentX.toInt(),
                originalRect.top,
                (currentX + partWidth).toInt(),
                originalRect.bottom
            )

            results.add(trimmedPart to partRect)

            // 다음 파트 시작 지점 갱신
            currentX += partWidth
        }

        return results
    }
    // ▼▼▼ Helper 클래스 ▼▼▼
    private class HandLandmarkerHelper(
        var minHandDetectionConfidence: Float = DEFAULT_HAND_DETECTION_CONFIDENCE,
        var minHandTrackingConfidence: Float = DEFAULT_HAND_TRACKING_CONFIDENCE,
        var minHandPresenceConfidence: Float = DEFAULT_HAND_PRESENCE_CONFIDENCE,
        var maxNumHands: Int = DEFAULT_NUM_HANDS,
        var currentDelegate: Int = DELEGATE_CPU,
        var runningMode: RunningMode = RunningMode.LIVE_STREAM,
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
//            if (runningMode != RunningMode.IMAGE) {
//                throw IllegalArgumentException("RunningMode must be IMAGE")
//            }
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
            private const val MP_HAND_LANDMARKER_TASK = "hand_landmarker_full.task"
            const val DELEGATE_CPU = 0
            const val DELEGATE_GPU = 1


            const val DEFAULT_HAND_DETECTION_CONFIDENCE = 0.5f
            const val DEFAULT_HAND_TRACKING_CONFIDENCE = 0.5f
            const val DEFAULT_HAND_PRESENCE_CONFIDENCE = 0.001f
            const val DEFAULT_NUM_HANDS = 2
        }

        data class ResultBundle(
            val results: List<HandLandmarkerResult>,
            val inferenceTime: Long,
            val inputImageHeight: Int,
            val inputImageWidth: Int
        )
    }
}