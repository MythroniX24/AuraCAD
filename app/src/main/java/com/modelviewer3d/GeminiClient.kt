package com.modelviewer3d

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Google Gemini client (no third-party networking dependency).
 *
 * Calls the REST generateContent endpoint with an optional inline PNG
 * (the ring-model screenshot) so Gemini can actually *see* the ring.
 * The user's API key is stored locally via [AiPrefs] and sent as the
 * ?key= query parameter — no server side needed.
 */
object GeminiClient {

    private const val MODEL = "gemini-2.0-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 45_000

    class GeminiException(message: String) : Exception(message)

    /** @return the model's text reply, or null if the API returned no candidates. */
    suspend fun generate(
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
        pngBase64: String? = null
    ): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw GeminiException("API key is missing")

        val parts = JSONArray()
            .put(JSONObject().put("text", userPrompt))
        if (!pngBase64.isNullOrBlank()) {
            parts.put(JSONObject().put("inlineData", JSONObject()
                .put("mimeType", "image/png")
                .put("data", pngBase64)))
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("contents", JSONArray()
                .put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject()
                .put("temperature", 0.2)
                .put("responseMimeType", "application/json"))

        val conn = URL("$ENDPOINT?key=$apiKey").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } }
                .orEmpty()

            if (code !in 200..299) {
                val msg = try {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                } catch (_: Exception) { null }
                throw GeminiException(msg ?: "HTTP $code")
            }
            val root = try { JSONObject(text) } catch (_: Exception) { null } ?: return@withContext null
            val candidates = root.optJSONArray("candidates") ?: return@withContext null
            if (candidates.length() == 0) return@withContext null
            val c = candidates.optJSONObject(0) ?: return@withContext null
            val partsArr = c.optJSONObject("content")?.optJSONArray("parts") ?: return@withContext null
            return@withContext partsArr.optJSONObject(0)?.optString("text")
        } finally {
            conn.disconnect()
        }
    }
}
