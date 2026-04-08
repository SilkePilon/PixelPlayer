package com.theveloper.pixelplay.data.ai.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini AI provider implementation using direct REST API calls.
 * Avoids the google-genai SDK which is incompatible with Android R8 minification.
 */
class GeminiAiClient(private val apiKey: String) : AiClient {

    companion object {
        private const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val CONNECT_TIMEOUT = 15000
        private const val READ_TIMEOUT = 120000 // 2 min for generation
    }

    override suspend fun generateContent(model: String, prompt: String): String {
        return withContext(Dispatchers.IO) {
            val url = URL("$BASE_URL/models/$model:generateContent?key=$apiKey")
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.doOutput = true

            // Build request body — simple text-only prompt
            val escapedPrompt = prompt
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
            val requestBody = """{"contents":[{"parts":[{"text":"$escapedPrompt"}]}]}"""

            connection.outputStream.bufferedWriter().use { it.write(requestBody) }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Exception) { "" }
                val errorMsg = parseErrorMessage(errorBody)
                throw Exception("Gemini API error $responseCode: $errorMsg")
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            extractTextFromResponse(responseBody)
                ?: throw Exception("Gemini returned an empty response")
        }
    }

    override suspend fun getAvailableModels(apiKey: String): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$BASE_URL/models?key=$apiKey")
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = 15000

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    parseModelsFromResponse(response)
                } else {
                    getDefaultModels()
                }
            } catch (e: Exception) {
                getDefaultModels()
            }
        }
    }

    override suspend fun validateApiKey(apiKey: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$BASE_URL/models/$DEFAULT_GEMINI_MODEL:generateContent?key=$apiKey")
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = 30000
                connection.doOutput = true

                val requestBody = """{"contents":[{"parts":[{"text":"test"}]}]}"""
                connection.outputStream.bufferedWriter().use { it.write(requestBody) }

                connection.responseCode == 200
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun getDefaultModel(): String = DEFAULT_GEMINI_MODEL

    private fun extractTextFromResponse(jsonResponse: String): String? {
        // Extract text from: candidates[0].content.parts[0].text
        val textPattern = """"text"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
        val match = textPattern.find(jsonResponse) ?: return null
        return match.groupValues[1]
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun parseErrorMessage(errorBody: String): String {
        if (errorBody.isBlank()) return "Unknown error"
        val msgPattern = """"message"\s*:\s*"([^"]+)"""".toRegex()
        return msgPattern.find(errorBody)?.groupValues?.get(1) ?: errorBody.take(200)
    }

    private fun parseModelsFromResponse(jsonResponse: String): List<String> {
        try {
            val models = mutableListOf<String>()
            val modelPattern = """"name":\s*"(models/[^"]+)"""".toRegex()
            val matches = modelPattern.findAll(jsonResponse)

            for (match in matches) {
                val fullName = match.groupValues[1]
                val modelName = fullName.removePrefix("models/")

                if (modelName.startsWith("gemini", ignoreCase = true) &&
                    !modelName.contains("embedding", ignoreCase = true)) {
                    models.add(modelName)
                }
            }

            return if (models.isNotEmpty()) models else getDefaultModels()
        } catch (e: Exception) {
            return getDefaultModels()
        }
    }

    private fun getDefaultModels(): List<String> {
        return listOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.5-pro",
            "gemini-3-flash-preview",
            "gemini-3.1-pro-preview",
            "gemini-3.1-flash-lite-preview"
        )
    }
}
