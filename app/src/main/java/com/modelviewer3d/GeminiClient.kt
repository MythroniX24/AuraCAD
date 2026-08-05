package com.modelviewer3d

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Dependency-free Gemini REST client used by the AI ring workflow. */
object GeminiClient {
    private const val MODEL = "gemini-2.5-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 45_000

    class GeminiException(message: String) : Exception(message)

    /** Removes formatting copied from .env files, quotes, code blocks, and whitespace. */
    fun sanitizeApiKey(value: String): String {
        var key = value.trim()
            .removePrefix("Bearer ")
            .removePrefix("bearer ")
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .removeSurrounding("`")
            .trim()
        key = key.removePrefix("GEMINI_API_KEY=").removePrefix("gemini_api_key=")
            .removePrefix("API_KEY=").removePrefix("api_key=").trim()
            .removeSurrounding("\"").removeSurrounding("'")
        return key.filterNot { it.isWhitespace() }
    }

    fun validateApiKey(value: String): String? {
        val key = sanitizeApiKey(value)
        return when {
            key.isBlank() -> "Paste your Gemini API key first."
            key.length < 20 -> "This key looks incomplete. Gemini keys usually start with AIza."
            !key.startsWith("AIza") -> "This does not look like a Google AI Studio key (it should start with AIza)."
            else -> null
        }
    }

    /** @return the model text reply, or null when Gemini returned no candidates. */
    suspend fun generate(
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
        pngBase64: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val cleanKey = sanitizeApiKey(apiKey)
        validateApiKey(cleanKey)?.let { throw GeminiException(it) }

        val parts = JSONArray().put(JSONObject().put("text", userPrompt))
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

        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // Header authentication avoids URL encoding and pasted-key query-string issues.
            conn.setRequestProperty("x-goog-api-key", cleanKey)
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw GeminiException(actionableError(code, response))

            val root = JSONObject(response)
            val candidates = root.optJSONArray("candidates")
                ?: throw GeminiException("Gemini returned no candidates. Try again.")
            val text = buildString {
                for (i in 0 until candidates.length()) {
                    val partsArray = candidates.optJSONObject(i)
                        ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                    for (j in 0 until partsArray.length()) {
                        val part = partsArray.optJSONObject(j)?.optString("text").orEmpty()
                        if (part.isNotBlank()) append(part)
                    }
                }
            }.trim()
            return@withContext text.ifBlank { null }
        } catch (e: GeminiException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw GeminiException("Gemini timed out. Check your connection and try again.")
        } catch (e: java.io.IOException) {
            throw GeminiException("Network error while contacting Gemini. Check internet access.")
        } catch (e: Exception) {
            throw GeminiException("Gemini response could not be read. Try again.")
        } finally {
            conn.disconnect()
        }
    }

    private fun actionableError(code: Int, response: String): String {
        val apiMessage = try {
            JSONObject(response).optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: Exception) { "" }
        return when (code) {
            400 -> "Gemini rejected the request${if (apiMessage.isNotBlank()) ": $apiMessage" else ". Check the key and model access."}"
            401, 403 -> "Gemini API key was rejected. Create a key in Google AI Studio and check its API restrictions."
            429 -> "Gemini rate limit reached. Wait a moment and try again."
            else -> apiMessage.ifBlank { "Gemini returned HTTP $code." }
        }
    }
}
