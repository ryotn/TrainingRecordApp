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

    private val gson = Gson()

    companion object {
        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        @Volatile
        private var cachedModelCandidates: List<String> = emptyList()
        private val modelCacheLock = Any()
    }

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

                val modelCandidates = resolveSupportedModelPaths()
                if (modelCandidates.isEmpty()) {
                    return@withContext Result.failure(
                        Exception("Geminiの利用可能モデル一覧を取得できませんでした。ネットワーク状態を確認して再試行してください。")
                    )
                }

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
                        if (statusCode == 404 || statusCode == 502 || statusCode == 503 || statusCode == 504) {
                            return@use
                        }

                        return@withContext Result.failure(lastError!!)
                    }
                }

                return@withContext Result.failure(
                    lastError ?: Exception("No available Gemini model supporting generateContent")
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

    private fun resolveSupportedModelPaths(): List<String> {
        val cached = cachedModelCandidates
        if (cached.isNotEmpty()) return cached
        synchronized(modelCacheLock) {
            if (cachedModelCandidates.isNotEmpty()) return cachedModelCandidates
            val discovered = fetchSupportedModelPaths()
            if (discovered.isNotEmpty()) {
                cachedModelCandidates = discovered
            }
            return cachedModelCandidates
        }
    }

    private fun fetchSupportedModelPaths(): List<String> {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models")
            .header("x-goog-api-key", apiKey)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val responseBody = response.body?.string() ?: return emptyList()
                val responseJson = gson.fromJson(responseBody, JsonObject::class.java)
                if (!responseJson.has("models")) return emptyList()

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
                        val name = model.get("name").asString
                        if (!name.startsWith("models/")) return@mapNotNull null
                        ModelCandidate(
                            name = name,
                            inputTokenLimit = model.getAsLongOrZero("inputTokenLimit"),
                            outputTokenLimit = model.getAsLongOrZero("outputTokenLimit")
                        )
                    }
                    .sortedWith(
                        compareByDescending<ModelCandidate> { it.inputTokenLimit }
                            .thenByDescending { it.outputTokenLimit }
                            .thenBy { it.name }
                    )
                    .map { it.name }

                supportedModels
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private data class ModelCandidate(
        val name: String,
        val inputTokenLimit: Long,
        val outputTokenLimit: Long
    )

    private fun JsonObject.getAsLongOrZero(memberName: String): Long {
        if (!has(memberName)) return 0L
        return runCatching { get(memberName).asLong }.getOrDefault(0L)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}
