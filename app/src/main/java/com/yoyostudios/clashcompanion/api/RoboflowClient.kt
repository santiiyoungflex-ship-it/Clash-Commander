package com.yoyostudios.clashcompanion.api

import android.util.Log
import com.google.gson.JsonParser
import com.yoyostudios.clashcompanion.BuildConfig
import com.yoyostudios.clashcompanion.detection.ArenaDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * API client for Roboflow Hosted Inference.
 *
 * Sends base64-encoded arena screenshots to Roboflow YOLO models
 * for enemy troop detection. Returns bounding boxes with class names.
 *
 * API: POST https://detect.roboflow.com/{modelId}?api_key={key}&confidence={conf}
 * Body: raw base64 image string
 * Content-Type: application/x-www-form-urlencoded
 */
object RoboflowClient {

    private const val TAG = "ClashCompanion"
    private const val BASE_URL = "https://serverless.roboflow.com"

    /**
     * Roboflow model ID. Format: "{project_id}/{version}".
     * Change this to test different models:
     *   - MinesBot: "minesbot-ynr4d/1" (instance segmentation, 4997 images)
     *   - Nejc Zavodnik: "clash-royale-esvmi/1" (107 classes, 1300 images)
     *   - AngelFire: "clash-royale-cylln/1" (cards/HP bars, not troop positions)
     */
    var modelId = "clash-royale-esvmi/1"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()

    /**
     * Run object detection on a base64-encoded image.
     *
     * @param imageBase64 Base64-encoded JPEG image (no data: prefix)
     * @param confidence Minimum confidence threshold (0.0-1.0)
     * @return List of detections with class names and bounding boxes
     */
    suspend fun detect(
        imageBase64: String,
        confidence: Float = 0.4f
    ): List<ArenaDetector.Detection> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.ROBOFLOW_API_KEY
        if (apiKey.isBlank()) {
            Log.w(TAG, "ROBOFLOW: API key not set")
            return@withContext emptyList()
        }

        val url = "$BASE_URL/$modelId" +
                "?api_key=$apiKey" +
                "&confidence=$confidence"

        val requestBody = imageBase64.toRequestBody(FORM_MEDIA_TYPE)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        val startTime = System.currentTimeMillis()

        try {
            val response = client.newCall(request).execute()
            val elapsed = System.currentTimeMillis() - startTime
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "ROBOFLOW: Error ${response.code} (${elapsed}ms): ${body.take(200)}")
                return@withContext emptyList()
            }

            Log.d(TAG, "ROBOFLOW: Response in ${elapsed}ms (${body.length} chars)")

            // Parse: { predictions: [{ x, y, width, height, class, confidence }], image: { width, height } }
            val json = JsonParser.parseString(body).asJsonObject
            val predictions = json.getAsJsonArray("predictions") ?: return@withContext emptyList()

            val detections = predictions.mapNotNull { elem ->
                try {
                    val pred = elem.asJsonObject
                    ArenaDetector.Detection(
                        className = pred.get("class")?.asString ?: return@mapNotNull null,
                        confidence = pred.get("confidence")?.asFloat ?: 0f,
                        centerX = pred.get("x")?.asInt ?: 0,
                        centerY = pred.get("y")?.asInt ?: 0,
                        width = pred.get("width")?.asInt ?: 0,
                        height = pred.get("height")?.asInt ?: 0
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "ROBOFLOW: Failed to parse prediction: ${e.message}")
                    null
                }
            }

            if (detections.isNotEmpty()) {
                Log.i(TAG, "ROBOFLOW: ${detections.size} detections in ${elapsed}ms")
            }

            return@withContext detections
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.w(TAG, "ROBOFLOW: Request failed (${elapsed}ms): ${e.message}")
            return@withContext emptyList()
        }
    }
}
