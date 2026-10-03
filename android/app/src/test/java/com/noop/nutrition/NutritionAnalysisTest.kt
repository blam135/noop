package com.noop.nutrition

import com.noop.ai.AiProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NutritionAnalysisTest {
    @Test fun everyProviderIncludesActualImageAndSelectedModel() {
        for (provider in AiProvider.entries) {
            val body = NutritionAnalysis.requestBody(provider, "vision-model", "JPEG_BYTES", "one bowl")
            if (provider != AiProvider.GEMINI) assertEquals("vision-model", body.getString("model"))
            val image = when (provider) {
                AiProvider.OPENAI, AiProvider.CUSTOM -> body.getJSONArray("messages").getJSONObject(1)
                    .getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url")
                AiProvider.ANTHROPIC -> body.getJSONArray("messages").getJSONObject(0)
                    .getJSONArray("content").getJSONObject(0).getJSONObject("source").getString("data")
                AiProvider.GEMINI -> body.getJSONArray("contents").getJSONObject(0)
                    .getJSONArray("parts").getJSONObject(0).getJSONObject("inline_data").getString("data")
            }
            assertTrue(image.endsWith("JPEG_BYTES"))
            assertTrue(body.toString().contains("one bowl"))
            assertFalse(body.toString().contains("deviceId"))
        }
    }

    @Test fun geminiThoughtsDoNotContaminateNutritionJson() {
        val response = JSONObject("""{"candidates":[{"finishReason":"STOP","content":{"parts":[{"thought":true,"text":"thinking"},{"text":"{\"name\":\"Lunch\"}"}]}}]}""")
        assertEquals("""{"name":"Lunch"}""", NutritionAnalysis.reply(AiProvider.GEMINI, response))
    }

    @Test fun truncatedRepliesAreRejectedBeforeParsing() {
        for ((provider, text) in listOf(
            AiProvider.OPENAI to """{"choices":[{"finish_reason":"length","message":{"content":"{}"}}]}""",
            AiProvider.ANTHROPIC to """{"stop_reason":"max_tokens","content":[]}""",
            AiProvider.GEMINI to """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[]}}]}""",
        )) {
            assertThrows(IllegalArgumentException::class.java) { NutritionAnalysis.reply(provider, JSONObject(text)) }
        }
    }
}
