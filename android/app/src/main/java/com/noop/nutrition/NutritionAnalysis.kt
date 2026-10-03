package com.noop.nutrition

import android.content.Context
import android.util.Base64
import com.noop.ai.AiCoach
import com.noop.ai.AiKeyStore
import com.noop.ai.AiProvider
import com.noop.ai.CustomAiAuthHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object NutritionAnalysis {
    private val http = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    // Snapshot settings at the explicit Analyze action, before suspending or reading the photo.
    data class Settings(val provider: AiProvider, val model: String, val key: String?,
                        val customUrl: String, val customAuth: CustomAiAuthHeader)
    fun settings(context: Context): Settings {
        val provider = AiKeyStore.readProvider(context)
        return Settings(provider, AiKeyStore.readModel(context, provider), AiKeyStore.read(context, provider),
            AiKeyStore.readCustomBaseUrl(context), AiKeyStore.readCustomAuthHeader(context))
    }

    suspend fun analyze(jpeg: ByteArray, notes: String, settings: Settings): NutritionMeal = withContext(Dispatchers.IO) {
        val (provider, model, key, customUrl, customAuth) = settings
        require(provider == AiProvider.CUSTOM || key != null) { "Add a key for the selected provider in Coach settings." }
        require(model.isNotBlank()) { "Select a vision-capable model in Coach settings." }
        val url = when (provider) {
            AiProvider.CUSTOM -> {
                val base = AiCoach.normalizeCustomBaseUrl(customUrl)
                AiCoach.guardCustomUrl(base)
                base + "/chat/completions"
            }
            AiProvider.GEMINI -> provider.endpoint + "/" + model + ":generateContent"
            else -> provider.endpoint
        }
        val request = Request.Builder().url(url)
        when (provider) {
            AiProvider.OPENAI -> request.header("Authorization", "Bearer $key")
            AiProvider.ANTHROPIC -> request.header("x-api-key", key!!).header("anthropic-version", "2023-06-01")
            AiProvider.GEMINI -> request.header("x-goog-api-key", key!!)
            AiProvider.CUSTOM -> if (!key.isNullOrBlank()) {
                if (customAuth == CustomAiAuthHeader.BEARER) request.header("Authorization", "Bearer $key")
                else request.header("x-api-key", key)
            }
        }
        val body = requestBody(provider, model, Base64.encodeToString(jpeg, Base64.NO_WRAP), notes)
        request.post(body.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(request.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                throw IllegalArgumentException("${provider.displayName}: HTTP ${response.code}. $detail")
            }
            val meal = NutritionMeal.parseEstimate(reply(provider, JSONObject(text)))
            meal.copy(provider = when(provider) {
                AiProvider.OPENAI -> "openAI"; AiProvider.ANTHROPIC -> "anthropic"
                AiProvider.GEMINI -> "gemini"; AiProvider.CUSTOM -> "custom"
            }, model = model)
        }
    }

    internal fun requestBody(provider: AiProvider, model: String, image: String, notes: String): JSONObject {
        val text = "Meal notes: " + notes.take(4000)
        return when (provider) {
            AiProvider.OPENAI, AiProvider.CUSTOM -> JSONObject().put("model", model).put(if (provider == AiProvider.CUSTOM) "max_tokens" else "max_completion_tokens", 4096)
                .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", NutritionMeal.PROMPT))
                    .put(JSONObject().put("role", "user").put("content", JSONArray()
                        .put(JSONObject().put("type", "text").put("text", text))
                        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$image"))))))
            AiProvider.ANTHROPIC -> JSONObject().put("model", model).put("max_tokens", 4096).put("system", NutritionMeal.PROMPT)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
                    .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", image)))
                    .put(JSONObject().put("type", "text").put("text", text)))))
            AiProvider.GEMINI -> JSONObject().put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", NutritionMeal.PROMPT))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
                    .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", image)))
                    .put(JSONObject().put("text", text)))))
                .put("generationConfig", JSONObject().put("maxOutputTokens", 4096))
        }
    }

    internal fun reply(provider: AiProvider, json: JSONObject): String = when (provider) {
        AiProvider.OPENAI, AiProvider.CUSTOM -> {
            val choice = json.getJSONArray("choices").getJSONObject(0)
            require(choice.optString("finish_reason") != "length") { NutritionMeal.INVALID }
            choice.getJSONObject("message").getString("content")
        }
        AiProvider.ANTHROPIC -> {
            require(json.optString("stop_reason") != "max_tokens") { NutritionMeal.INVALID }
            val parts = json.getJSONArray("content")
            (0 until parts.length()).map { parts.getJSONObject(it) }.filter { it.optString("type") == "text" }
                .joinToString("") { it.getString("text") }
        }
        AiProvider.GEMINI -> {
            val candidate = json.getJSONArray("candidates").getJSONObject(0)
            require(candidate.optString("finishReason") == "STOP") { NutritionMeal.INVALID }
            val parts = candidate.getJSONObject("content").getJSONArray("parts")
            (0 until parts.length()).map { parts.getJSONObject(it) }.filterNot { it.optBoolean("thought") }
                .joinToString("") { it.optString("text") }
        }
    }
}
