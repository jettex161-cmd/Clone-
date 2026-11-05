package com.pixelagent.app.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

class GeminiHistorySerializationTest {
    @Test
    fun consecutiveToolCallsAndResultsRetainSignaturesInGeminiCompatibleHistory() {
        val signatureA = "opaque-A"
        val signatureB = "opaque-B"
        val assistant = Msg(
            role = Role.ASSISTANT,
            toolCalls = listOf(
                ToolCall("call-a", "shell", JSONObject().put("command", "pwd"), geminiMetadata(signatureA)),
                ToolCall("call-b", "list_files", JSONObject().put("path", "."), geminiMetadata(signatureB))
            )
        )
        val request = ChatRequest(
            ProviderProfile(baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai", model = "gemini-2.0-flash"),
            "system", listOf(assistant, Msg(Role.TOOL, "done-a", toolCallId = "call-a"), Msg(Role.TOOL, "done-b", toolCallId = "call-b")),
            emptyList(), NoOpPlatform
        )

        val history = OpenAiClient().buildMessages(request)
        val calls = history.getJSONObject(1).getJSONArray("tool_calls")

        assertEquals(signatureA, calls.getJSONObject(0).getJSONObject("extra_content").getJSONObject("google").getString("thought_signature"))
        assertEquals(signatureB, calls.getJSONObject(1).getJSONObject("extra_content").getJSONObject("google").getString("thought_signature"))
        assertEquals("call-a", history.getJSONObject(2).getString("tool_call_id"))
        assertEquals("done-b", history.getJSONObject(3).getString("content"))
    }

    @Test
    fun geminiMetadataIsNotForwardedToOtherOpenAiCompatibleProviders() {
        val request = ChatRequest(
            ProviderProfile(baseUrl = "https://api.openai.com/v1", model = "gpt-4o-mini"),
            "system", listOf(Msg(Role.ASSISTANT, toolCalls = listOf(
                ToolCall("call-a", "shell", JSONObject(), geminiMetadata("opaque"))
            ))), emptyList(), NoOpPlatform
        )

        val toolCall = OpenAiClient().buildMessages(request).getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0)

        assertEquals(false, toolCall.has("extra_content"))
    }

    @Test
    fun imageIsNotSilentlyDroppedForTextOnlyOrUnreadableInputs() {
        val textOnlyRequest = ChatRequest(
            ProviderProfile(baseUrl = "https://api.openai.com/v1", model = "text-model", vision = false),
            "system", listOf(Msg(Role.USER, "Describe", images = listOf("image.jpg"))), emptyList(), NoOpPlatform
        )
        val unreadableImageRequest = ChatRequest(
            ProviderProfile(baseUrl = "https://api.openai.com/v1", model = "vision-model", vision = true, visionVerified = true),
            "system", listOf(Msg(Role.USER, "Describe", images = listOf("missing.jpg"))), emptyList(), NoOpPlatform
        )

        assertThrows(java.io.IOException::class.java) { OpenAiClient().buildMessages(textOnlyRequest) }
        assertThrows(java.io.IOException::class.java) { OpenAiClient().buildMessages(unreadableImageRequest) }
    }

    @Test
    fun openAiCompatibleGeminiParserRetainsReturnedThoughtSignature() {
        val server = ServerSocket(0)
        val serverError = AtomicReference<Throwable?>(null)
        val thread = Thread {
            try {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) contentLength = line.substringAfter(':').trim().toInt()
                    }
                    repeat(contentLength) { reader.read() }
                    val body = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-gemini\",\"function\":{\"name\":\"screen_read\",\"arguments\":\"{}\"},\"extra_content\":{\"google\":{\"thought_signature\":\"opaque-signature\"}}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
                    )
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                }
            } catch (e: Throwable) {
                serverError.set(e)
            } finally {
                server.close()
            }
        }
        thread.start()
        try {
            val request = ChatRequest(
                ProviderProfile(baseUrl = "http://127.0.0.1:${server.localPort}/v1", model = "gemini-2.0-flash"),
                "system", listOf(Msg(Role.USER, "read screen")), emptyList(), NoOpPlatform
            )

            val turn = OpenAiClient().chat(request, Canceller()) { }

            assertEquals("opaque-signature", turn.toolCalls.single().providerMetadata
                .getJSONObject("extra_content").getJSONObject("google").getString("thought_signature"))
            thread.join(5000)
            assertEquals(null, serverError.get())
        } finally {
            server.close()
            thread.join(5000)
        }
    }

    private fun geminiMetadata(signature: String) = JSONObject()
        .put("extra_content", JSONObject().put("google", JSONObject().put("thought_signature", signature)))
}

private object NoOpPlatform : Platform {
    override fun deviceInfo() = ""
    override fun listApps(query: String) = ""
    override fun openApp(app: String) = ""
    override fun openUrl(url: String) = ""
    override fun setAlarm(hour: Int, minute: Int, label: String) = ""
    override fun setTimer(seconds: Int, label: String) = ""
    override fun clipboardGet() = ""
    override fun clipboardSet(text: String) = ""
    override fun notify(title: String, text: String) = ""
    override fun imageInfo(path: String) = ""
    override fun encodeImage(path: String): EncodedImage? = null
    override fun screen(): ScreenController? = null
}