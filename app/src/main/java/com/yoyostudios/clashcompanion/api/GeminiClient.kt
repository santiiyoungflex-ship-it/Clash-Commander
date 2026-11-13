package com.yoyostudios.clashcompanion.api

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yoyostudios.clashcompanion.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * API client for Google Gemini 3 Flash.
 * Used for:
 *   - Vision/multimodal queries (images + text)
 *   - M8: Real-time tactical decisions (text-only Smart Path)
 *
 * Separate from AnthropicClient which handles Opus only.
 * All calls run on Dispatchers.IO.
 */
object GeminiClient {

    private const val TAG = "ClashCompanion"
    private const val MODEL = "gemini-3.8-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    private fun apiKey(): String =
        ApiKeyStore.getGeminiKey().ifBlank { BuildConfig.GEMINI_API_KEY.trim() }

    fun isConfigured(): Boolean = apiKey().isNotBlank()

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Text-only chat with Gemini Flash.
     * For M8 Smart Path: strategic decisions using Opus playbook.
     *
     * @param systemInstruction System instruction text
     * @param userMessage User message text
     * @param maxTokens Maximum output tokens
     * @param jsonMode If true, sets responseMimeType to application/json
     * @return Response text from Gemini
     */
    suspend fun chat(
        systemInstruction: String,
        userMessage: String,
        maxTokens: Int = 1024,
        jsonMode: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val apiKey = apiKey()
        if (apiKey.isBlank()) {
            throw IllegalStateException("GEMINI_API_KEY not set in local.properties")
        }

        // Build parts array with text only
        val partsArray = JsonArray().apply {
            add(JsonObject().apply { addProperty("text", userMessage) })
        }

        val body = buildRequestBody(systemInstruction, partsArray, maxTokens, jsonMode)
        return@withContext executeRequest(apiKey, body)
    }

    /**
     * Vision/multimodal chat with Gemini Flash.
     * For M6: card identification from hand screenshot crops.
     *
     * @param systemInstruction System instruction text
     * @param userText Text prompt accompanying the images
     * @param images List of base64-encoded JPEG strings
     * @param maxTokens Maximum output tokens
     * @param jsonMode If true, sets responseMimeType to application/json
     * @return Response text from Gemini
     */
    suspend fun chatWithImages(
        systemInstruction: String,
        userText: String,
        images: List<String>,
        maxTokens: Int = 1024,
        jsonMode: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val apiKey = apiKey()
        if (apiKey.isBlank()) {
            throw IllegalStateException("GEMINI_API_KEY not set in local.properties")
        }

        // Build parts array: images first, then text
        val partsArray = JsonArray()
        for (img in images) {
            partsArray.add(JsonObject().apply {
                add("inline_data", JsonObject().apply {
                    addProperty("mime_type", "image/jpeg")
                    addProperty("data", img)
                })
            })
        }
        partsArray.add(JsonObject().apply { addProperty("text", userText) })

        val body = buildRequestBody(systemInstruction, partsArray, maxTokens, jsonMode)
        return@withContext executeRequest(apiKey, body)
    }

    // ── Utilities ───────────────────────────────────────────────────────

    /**
     * Convert a Bitmap to a base64-encoded JPEG string.
     * @param bitmap Source bitmap (not recycled by this method)
     * @param quality JPEG compression quality (0-100)
     */
    fun bitmapToBase64(bitmap: Bitmap, quality: Int = 85): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Clean Gemini response text by stripping markdown fences and preamble.
     * Gemini 3 Flash (thinking model) often returns:
     *   "Here is the JSON requested:\n```json\n[...]\n```"
     * This extracts just the JSON content.
     */
    fun cleanJsonResponse(raw: String): String {
        var text = raw.trim()

        // Strip markdown code fences: ```json ... ``` or ``` ... ```
        val fencePattern = Regex("```(?:json)?\\s*\\n?(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)
        val fenceMatch = fencePattern.find(text)
        if (fenceMatch != null) {
            text = fenceMatch.groupValues[1].trim()
        }

        // If still not starting with [ or {, try to find JSON array/object in the text
        if (!text.startsWith("[") && !text.startsWith("{")) {
            val jsonStart = text.indexOfFirst { it == '[' || it == '{' }
            if (jsonStart >= 0) {
                text = text.substring(jsonStart)
                // Find matching close
                val openChar = text[0]
                val closeChar = if (openChar == '[') ']' else '}'
                val closeIdx = text.lastIndexOf(closeChar)
                if (closeIdx > 0) {
                    text = text.substring(0, closeIdx + 1)
                }
            }
        }

        return text
    }

    // ── Internals ───────────────────────────────────────────────────────

    /**
     * Build the Gemini API request body JSON.
     */
    private fun buildRequestBody(
        systemInstruction: String,
        partsArray: JsonArray,
        maxTokens: Int,
        jsonMode: Boolean
    ): JsonObject {
        return JsonObject().apply {
            // contents: [{ parts: [...] }]
            add("contents", JsonArray().apply {
                add(JsonObject().apply {
                    add("parts", partsArray)
                })
            })

            // systemInstruction: { parts: [{ text: "..." }] }
            if (systemInstruction.isNotBlank()) {
                add("systemInstruction", JsonObject().apply {
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("text", systemInstruction)
                        })
                    })
                })
            }

            // generationConfig
            // thinkingLevel "minimal" disables thinking for fast, direct responses.
            // Without this, Flash burns tokens on internal reasoning and wraps
            // output in markdown fences.
            add("generationConfig", JsonObject().apply {
                addProperty("temperature", 0.1)
                addProperty("maxOutputTokens", maxTokens)
                add("thinkingConfig", JsonObject().apply {
                    addProperty("thinkingLevel", "minimal")
                })
                if (jsonMode) {
                    addProperty("responseMimeType", "application/json")
                }
            })
        }
    }

    /**
     * Execute the HTTP request and parse the response.
     * Extracts candidates[0].content.parts[0].text from Gemini response.
     */
    private fun executeRequest(apiKey: String, body: JsonObject): String {
        val url = "$BASE_URL/$MODEL:generateContent?key=$apiKey"
        val requestBody = body.toString().toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(url)
            .addHeader("content-type", "application/json")
            .post(requestBody)
            .build()

        Log.i(TAG, "GEMINI: Calling $MODEL...")
        val startTime = System.currentTimeMillis()

        val response = client.newCall(request).execute()
        val elapsed = System.currentTimeMillis() - startTime
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            Log.e(TAG, "GEMINI: Error ${response.code}: ${responseBody.take(300)}")
            throw RuntimeException("Gemini API error ${response.code}: ${responseBody.take(200)}")
        }

        Log.i(TAG, "GEMINI: Response in ${elapsed}ms (${responseBody.length} chars)")
        Log.d(TAG, "GEMINI: Raw response: ${responseBody.take(500)}")

        // Parse: { candidates: [{ content: { parts: [{ text: "..." }] } }] }
        try {
            val json = JsonParser.parseString(responseBody).asJsonObject

            // Check for prompt feedback / safety blocks
            val promptFeedback = json.getAsJsonObject("promptFeedback")
            if (promptFeedback != null) {
                val blockReason = promptFeedback.get("blockReason")?.asString
                if (blockReason != null) {
                    Log.e(TAG, "GEMINI: Blocked by safety filter: $blockReason")
                    throw RuntimeException("Gemini blocked: $blockReason")
                }
            }

            val candidates = json.getAsJsonArray("candidates")
            if (candidates == null || candidates.size() == 0) {
                // Log full response for debugging
                Log.e(TAG, "GEMINI: No candidates in response: $responseBody")
                throw RuntimeException("No candidates in Gemini response")
            }

            val candidate = candidates[0].asJsonObject

            // Check for finish reason issues
            val finishReason = candidate.get("finishReason")?.asString
            if (finishReason != null && finishReason != "STOP") {
                Log.w(TAG, "GEMINI: finishReason=$finishReason")
            }

            val content = candidate.getAsJsonObject("content")
            if (content == null) {
                Log.e(TAG, "GEMINI: No content in candidate: $candidate")
                throw RuntimeException("No content in Gemini candidate")
            }

            val parts = content.getAsJsonArray("parts")
            if (parts != null && parts.size() > 0) {
                val text = parts[0].asJsonObject.get("text")?.asString ?: ""
                Log.i(TAG, "GEMINI: Extracted text (${text.length} chars): ${text.take(200)}")
                return text
            }

            Log.e(TAG, "GEMINI: No parts in content: $content")
            throw RuntimeException("No parts in Gemini content")
        } catch (e: Exception) {
            if (e is RuntimeException) throw e
            Log.e(TAG, "GEMINI: Failed to parse response: ${e.message}")
            Log.e(TAG, "GEMINI: Response was: ${responseBody.take(500)}")
            throw RuntimeException("Failed to parse Gemini response: ${e.message}")
        }
    }
}
