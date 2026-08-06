package com.modelviewer3d

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Dependency-free Gemini REST client used by the AI workflow. */
object GeminiClient {

    /**
     * Model fallback chain — newest stable first.
     *
     * Google retires older models over time (gemini-2.0-flash shut down Jun 2026,
     * the 2.5-preview snapshots shut down Feb 2026) and blocks retired models for
     * new accounts ("This model ... is no longer available to new users"). The
     * client therefore tries the newest model first and automatically moves to the
     * next one in the chain whenever a model answers with a "not available / not
     * found" error.
     */
    val MODELS = listOf(
        "gemini-3.5-flash",     // current recommended stable (2026)
        "gemini-flash-latest",  // rolling alias → newest flash
        "gemini-2.5-flash"      // legacy fallback for existing keys
    )

    const val DEFAULT_MODEL = "gemini-3.5-flash"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 45_000

    /** Reply from a successful Gemini call — the text plus the model that answered. */
    class GeminiReply(val model: String, val text: String?)

    class GeminiException(message: String, val modelUnavailable: Boolean = false) : Exception(message)

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

    /**
     * Runs a Gemini request, trying the requested [model] first and then falling
     * back through [MODELS] when the model is retired/unavailable.
     *
     * @return a [GeminiReply] with the model that answered and its text reply
     *         (text is null when Gemini returned no candidates).
     */
    suspend fun generate(
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
        pngBase64: String? = null,
        model: String = DEFAULT_MODEL
    ): GeminiReply {
        val cleanKey = sanitizeApiKey(apiKey)
        validateApiKey(cleanKey)?.let { throw GeminiException(it) }

        // Requested model first, then every fallback not already in the list.
        val tryOrder = linkedSetOf(model) + MODELS
        var lastTried = model
        for (m in tryOrder) {
            lastTried = m
            try {
                return GeminiReply(m, post(m, cleanKey, systemPrompt, userPrompt, pngBase64))
            } catch (e: GeminiException) {
                if (e.modelUnavailable) continue // model retired → try the next one
                throw e                          // key/rate-limit/network → surface it
            }
        }
        throw GeminiException(
            "Gemini model '$lastTried' is no longer available. " +
            "Update AuraCAD to the latest version for a working model."
        )
    }

    private suspend fun post(
        model: String,
        cleanKey: String,
        systemPrompt: String,
        userPrompt: String,
        pngBase64: String?
    ): String? = withContext(Dispatchers.IO) {
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

        val conn = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        ).openConnection() as HttpURLConnection
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
            if (code !in 200..299) {
                val apiMessage = extractApiMessage(response)
                throw GeminiException(
                    actionableError(code, response, apiMessage),
                    modelUnavailable = isModelUnavailable(code, apiMessage)
                )
            }

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

    private fun extractApiMessage(response: String): String =
        try {
            JSONObject(response).optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: Exception) { "" }

    /**
     * True when this HTTP error means "model retired / blocked for this account",
     * in which case the client should retry with the next model in the chain.
     */
    private fun isModelUnavailable(code: Int, message: String): Boolean {
        if (code == 404) return true
        if (code != 400) return false
        val m = message.lowercase()
        return m.contains("not available") || m.contains("no longer") ||
            m.contains("not found") || m.contains("does not exist") ||
            m.contains("doesn't exist") || m.contains("discontinued")
    }

    private fun actionableError(code: Int, response: String, apiMessage: String): String =
        when (code) {
            400 -> "Gemini rejected the request${if (apiMessage.isNotBlank()) ": $apiMessage" else ". Check the key and model access."}"
            401, 403 -> "Gemini API key was rejected. Create a key in Google AI Studio and check its API restrictions."
            404 -> "Gemini model not found. Update AuraCAD to the latest version."
            429 -> "Gemini rate limit reached. Wait a moment and try again."
            else -> apiMessage.ifBlank { "Gemini returned HTTP $code." }
        }
}
