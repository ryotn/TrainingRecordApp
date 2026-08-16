package com.example.trainingrecordapp

import android.graphics.Bitmap
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class TrainingRecord(
    val exerciseName: String,
    val sets: List<ExerciseSet>,
    val notes: String
)

data class ExerciseSet(
    val setNumber: Int,
    val reps: Int,
    val weightKg: Double
)

class GeminiApiClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    @Volatile
    private var cachedModelPath: String? = null
    private val preferredModelNames = listOf(
        "models/gemini-2.5-flash",
        "models/gemini-2.0-flash",
        "models/gemini-1.5-flash-latest",
        "models/gemini-1.5-flash"
    )

    private val systemPrompt = """
        あなたはトレーニングマシンの結果画面の画像を解析するアシスタントです。
        画像からトレーニングの記録情報を抽出してJSON形式で返してください。
        
        必ず以下の形式のJSONのみを返してください（コードブロックなし）:
        {
          "exerciseName": "エクササイズ名",
          "sets": [
            {"setNumber": 1, "reps": 10, "weightKg": 50.0}
          ],
          "notes": "備考"
        }
        
        情報が読み取れない場合は適切なデフォルト値を使用してください。
    """.trimIndent()

    suspend fun parseTrainingImages(bitmaps: List<Bitmap>): Result<TrainingRecord> =
        withContext(Dispatchers.IO) {
            try {
                val parts = mutableListOf<JsonObject>()

                // Add text prompt
                val textPart = JsonObject().apply {
                    add("text", gson.toJsonTree(systemPrompt))
                }
                parts.add(textPart)

                // Add image parts
                for (bitmap in bitmaps) {
                    val base64 = bitmapToBase64(bitmap)
                    val inlineData = JsonObject().apply {
                        addProperty("mime_type", "image/jpeg")
                        addProperty("data", base64)
                    }
                    val imagePart = JsonObject().apply {
                        add("inline_data", inlineData)
                    }
                    parts.add(imagePart)
                }

                val content = JsonObject().apply {
                    addProperty("role", "user")
                    add("parts", gson.toJsonTree(parts))
                }

                val requestBody = JsonObject().apply {
                    add("contents", gson.toJsonTree(listOf(content)))
                }

                val resolvedModelPath = cachedModelPath ?: resolveSupportedModelPath()?.also {
                    cachedModelPath = it
                }
                val modelCandidates = buildList {
                    resolvedModelPath?.let(::add)
                    addAll(preferredModelNames)
                }
                    .distinct()

                var lastError: Exception? = null

                for (modelPath in modelCandidates) {
                    val request = Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/$modelPath:generateContent")
                        .header("x-goog-api-key", apiKey)
                        .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    client.newCall(request).execute().use { response ->
                        val responseBody = response.body?.string()
                        if (responseBody == null) {
                            lastError = Exception("Empty response from Gemini API ($modelPath)")
                            return@use
                        }

                        if (response.isSuccessful) {
                            return@withContext parseTrainingRecord(responseBody)
                        }

                        val statusCode = response.code
                        lastError = Exception("API error $statusCode ($modelPath)")
                        if (statusCode == 429) {
                            return@withContext Result.failure(
                                Exception("Gemini API rate limit exceeded. Please try again later.")
                            )
                        }
                        if (statusCode == 404 || statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504) {
                            if (cachedModelPath == modelPath) {
                                cachedModelPath = null
                            }
                            return@use
                        }

                        return@withContext Result.failure(lastError!!)
                    }
                }

                return@withContext Result.failure(
                    lastError ?: Exception("No compatible Gemini model found for generateContent")
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun parseTrainingRecord(responseBody: String): Result<TrainingRecord> {
        return runCatching {
            val responseJson = gson.fromJson(responseBody, JsonObject::class.java)
            val text = extractCandidateText(responseJson)
                ?: throw IllegalStateException(buildGeminiErrorMessage(responseJson))
            val cleanedText = extractJsonPayload(text)
            gson.fromJson(cleanedText, TrainingRecord::class.java)
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(Exception("Unexpected response format from Gemini API", it)) }
        )
    }

    private fun extractCandidateText(responseJson: JsonObject): String? {
        if (!responseJson.has("candidates")) return null
        val candidates = responseJson.getAsJsonArray("candidates")
        for (candidateElement in candidates) {
            val candidate = candidateElement.asJsonObject
            if (!candidate.has("content")) continue
            val content = candidate.getAsJsonObject("content")
            if (!content.has("parts")) continue
            val parts = content.getAsJsonArray("parts")
            for (partElement in parts) {
                val part = partElement.asJsonObject
                if (!part.has("text")) continue
                val text = part.get("text").asString
                if (text.isNotBlank()) return text
            }
        }
        return null
    }

    private fun extractJsonPayload(text: String): String {
        var stripped = text.trim()
        stripped = stripped.replaceFirst(Regex("^```(?:json)?\\s*\\n?", RegexOption.IGNORE_CASE), "")
        stripped = stripped.replaceFirst(Regex("\\n?```\\s*$"), "")
        stripped = stripped.trim()
        val firstBraceIndex = stripped.indexOf('{')
        val lastBraceIndex = stripped.lastIndexOf('}')
        return if (firstBraceIndex >= 0 && lastBraceIndex > firstBraceIndex) {
            stripped.substring(firstBraceIndex, lastBraceIndex + 1)
        } else {
            stripped
        }
    }

    private fun buildGeminiErrorMessage(responseJson: JsonObject): String {
        if (responseJson.has("error")) {
            val errorObject = responseJson.getAsJsonObject("error")
            if (errorObject.has("message")) {
                return "Gemini API error: ${errorObject.get("message").asString}"
            }
        }
        if (responseJson.has("promptFeedback")) {
            val feedback = responseJson.getAsJsonObject("promptFeedback")
            if (feedback.has("blockReason")) {
                return "Gemini response blocked: ${feedback.get("blockReason").asString}"
            }
        }
        return "Gemini response did not contain usable text content"
    }

    private fun resolveSupportedModelPath(): String? {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models")
            .header("x-goog-api-key", apiKey)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val responseBody = response.body?.string() ?: return null
                val responseJson = gson.fromJson(responseBody, JsonObject::class.java)
                if (!responseJson.has("models")) return null

                val supportedModels = responseJson.getAsJsonArray("models")
                    .mapNotNull { modelElement ->
                        val model = modelElement.asJsonObject
                        val methods = if (model.has("supportedGenerationMethods")) {
                            model.getAsJsonArray("supportedGenerationMethods")
                        } else {
                            null
                        } ?: return@mapNotNull null

                        val supportsGenerateContent = methods.any { it.asString == "generateContent" }
                        if (!supportsGenerateContent || !model.has("name")) return@mapNotNull null
                        model.get("name").asString
                    }

                preferredModelNames.firstOrNull { it in supportedModels }
                    ?: supportedModels.firstOrNull { it.contains("flash") }
                    ?: supportedModels.firstOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}
