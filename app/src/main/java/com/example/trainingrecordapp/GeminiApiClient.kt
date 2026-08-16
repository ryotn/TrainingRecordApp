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

    private val client = OkHttpClient()
    private val gson = Gson()

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

                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: return@withContext Result.failure(
                    Exception("Empty response from Gemini API")
                )

                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("API error ${response.code}: $responseBody"))
                }

                val responseJson = gson.fromJson(responseBody, JsonObject::class.java)
                val text = responseJson
                    .getAsJsonArray("candidates")
                    .get(0).asJsonObject
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")
                    .get(0).asJsonObject
                    .get("text").asString

                val cleanedText = text.trim().removePrefix("```json").removeSuffix("```").trim()
                val record = gson.fromJson(cleanedText, TrainingRecord::class.java)
                Result.success(record)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}
