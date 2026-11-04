package com.pixelagent.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

class ChatRequest(
    val profile: ProviderProfile,
    val system: String,
    val messages: List<Msg>,
    val tools: List<ToolSpec>,
    val platform: Platform
)

class AssistantTurn(val text: String, val toolCalls: List<ToolCall>, val stopReason: String)

interface LlmClient {
    /** Blocking call; streams text deltas to [onText] and returns the complete turn. */
    fun chat(req: ChatRequest, canceller: Canceller, onText: (String) -> Unit): AssistantTurn

    fun listModels(profile: ProviderProfile, canceller: Canceller): List<String>
}

/** The Android layer registers the on-device engine here (the core itself stays Android-free). */
object LocalLlm {
    @Volatile
    var factory: (() -> LlmClient)? = null
}

fun clientFor(p: ProviderProfile): LlmClient = when (p.type) {
    ProviderType.ANTHROPIC -> AnthropicClient()
    ProviderType.OPENAI -> OpenAiClient()
    ProviderType.LOCAL -> LocalLlm.factory?.invoke() ?: throw IOException("On-device models are not available in this build.")
}

/** Tool arguments arrive as JSON text; a malformed payload is reported back to the model instead of crashing. */
fun parseToolArgs(raw: String): JSONObject {
    val t = raw.trim()
    if (t.isEmpty()) return JSONObject()
    return try {
        JSONObject(t)
    } catch (e: Exception) {
        JSONObject().put("__error", "Tool arguments were not valid JSON: " + t.take(200))
    }
}

private class CallAcc {
    var id = ""
    var name = ""
    val args = StringBuilder()
}

private fun requireModel(p: ProviderProfile) {
    if (p.baseUrl.isBlank()) throw IOException("No base URL set for provider '${p.name}'. Open Providers and fill it in.")
    if (p.model.isBlank()) throw IOException("No model set for provider '${p.name}'. Open Providers and pick or type a model name.")
}

// ─────────────────────────────────────────────────────────────────────────────
//  OpenAI-compatible (OpenAI, OpenRouter, Groq, Gemini, DeepSeek, Mistral, Ollama, llama.cpp, LM Studio…)
// ─────────────────────────────────────────────────────────────────────────────

class OpenAiClient : LlmClient {
    private data class Variant(val maxCompletion: Boolean = false, val noTemperature: Boolean = false)

    companion object {
        // Remembers per-provider parameter quirks learned from error messages (e.g. o-series models).
        private val learned = ConcurrentHashMap<String, Any>()
    }

    private fun root(base: String): String {
        var b = base.trim().trimEnd('/')
        if (b.endsWith("/chat/completions")) b = b.removeSuffix("/chat/completions")
        return b.trimEnd('/')
    }

    private fun headers(p: ProviderProfile, stream: Boolean): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["Content-Type"] = "application/json"
        h["Accept"] = if (stream) "text/event-stream" else "application/json"
        if (p.apiKey.isNotBlank()) h["Authorization"] = "Bearer ${p.apiKey.trim()}"
        return h
    }

    private fun buildMessages(req: ChatRequest): JSONArray {
        val arr = JSONArray()
        if (req.system.isNotBlank()) arr.put(jsonOf("role" to "system", "content" to req.system))
        for (m in req.messages) {
            when (m.role) {
                Role.USER -> {
                    val imgs = if (req.profile.vision) m.images.mapNotNull { req.platform.encodeImage(it) } else emptyList()
                    if (imgs.isEmpty()) {
                        arr.put(jsonOf("role" to "user", "content" to m.text.ifEmpty { " " }))
                    } else {
                        val parts = JSONArray()
                        parts.put(jsonOf("type" to "text", "text" to m.text.ifEmpty { "(see attached image)" }))
                        for (img in imgs) {
                            parts.put(
                                jsonOf(
                                    "type" to "image_url",
                                    "image_url" to jsonOf("url" to "data:${img.mime};base64,${img.base64}")
                                )
                            )
                        }
                        arr.put(jsonOf("role" to "user", "content" to parts))
                    }
                }
                Role.ASSISTANT -> {
                    if (m.toolCalls.isEmpty() && m.text.isBlank()) continue
                    val o = JSONObject()
                    o.put("role", "assistant")
                    o.put("content", m.text)
                    if (m.toolCalls.isNotEmpty()) {
                        val tcs = JSONArray()
                        for (c in m.toolCalls) {
                            tcs.put(
                                jsonOf(
                                    "id" to c.id, "type" to "function",
                                    "function" to jsonOf("name" to c.name, "arguments" to c.args.toString())
                                )
                            )
                        }
                        o.put("tool_calls", tcs)
                    }
                    arr.put(o)
                }
                Role.TOOL -> arr.put(
                    jsonOf("role" to "tool", "tool_call_id" to m.toolCallId, "content" to m.text.ifEmpty { "(no output)" })
                )
                Role.NOTE -> {}
            }
        }
        return arr
    }

    private fun buildBody(req: ChatRequest, v: Variant): ByteArray {
        val p = req.profile
        val body = JSONObject()
        body.put("model", p.model)
        body.put("messages", buildMessages(req))
        body.put("stream", true)
        if (!v.noTemperature) body.put("temperature", p.temperature)
        if (v.maxCompletion) body.put("max_completion_tokens", p.maxTokens) else body.put("max_tokens", p.maxTokens)
        if (req.tools.isNotEmpty()) {
            val tools = JSONArray()
            for (t in req.tools) {
                tools.put(
                    jsonOf(
                        "type" to "function",
                        "function" to jsonOf("name" to t.name, "description" to t.description, "parameters" to t.parameters)
                    )
                )
            }
            body.put("tools", tools)
        }
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    override fun chat(req: ChatRequest, canceller: Canceller, onText: (String) -> Unit): AssistantTurn {
        requireModel(req.profile)
        var variant = (learned[req.profile.id] as? Variant) ?: Variant()
        var attempts = 0
        while (true) {
            try {
                val turn = doChat(req, variant, canceller, onText)
                learned[req.profile.id] = variant
                return turn
            } catch (e: HttpException) {
                val b = e.body.lowercase()
                var next: Variant? = null
                if (e.code in 400..422) {
                    if (!variant.maxCompletion && b.contains("max_completion_tokens")) next = variant.copy(maxCompletion = true)
                    else if (!variant.noTemperature && b.contains("temperature")) next = variant.copy(noTemperature = true)
                }
                attempts++
                if (next == null || attempts > 3) throw e
                variant = next
            }
        }
    }

    private fun doChat(req: ChatRequest, variant: Variant, canceller: Canceller, onText: (String) -> Unit): AssistantTurn {
        val p = req.profile
        val url = root(p.baseUrl) + "/chat/completions"
        val text = StringBuilder()
        val calls = TreeMap<Int, CallAcc>()
        var finish = ""
        var sawData = false
        val raw = StringBuilder()

        fun readToolCalls(tcs: JSONArray) {
            for (tc in tcs.objects()) {
                val idxRaw = tc.int("index", -1)
                val id = tc.str("id")
                val fn = tc.obj("function")
                var idx = when {
                    idxRaw >= 0 -> idxRaw
                    id.isNotEmpty() -> if (calls.isEmpty()) 0 else calls.lastKey() + 1
                    else -> if (calls.isEmpty()) 0 else calls.lastKey()
                }
                var acc = calls[idx]
                if (acc != null && id.isNotEmpty() && acc.id.isNotEmpty() && acc.id != id) {
                    idx = calls.lastKey() + 1
                    acc = null
                }
                if (acc == null) {
                    acc = CallAcc()
                    calls[idx] = acc
                }
                if (id.isNotEmpty()) acc.id = id
                if (fn != null) {
                    val n = fn.str("name")
                    if (n.isNotEmpty()) acc.name = n
                    val a = fn.opt("arguments")
                    if (a is String) acc.args.append(a) else if (a is JSONObject) acc.args.append(a.toString())
                }
            }
        }

        fun failIfError(j: JSONObject) {
            if (j.has("error") && !j.isNull("error")) {
                val msg = j.obj("error")?.str("message") ?: j.str("error")
                throw IOException("Provider error: $msg")
            }
        }

        fun handleChunk(payload: String) {
            val j = try { JSONObject(payload) } catch (e: Exception) { return }
            failIfError(j)
            val c = j.arr("choices")?.optJSONObject(0) ?: return
            val delta = c.obj("delta")
            if (delta != null) {
                val t = delta.str("content")
                if (t.isNotEmpty()) {
                    text.append(t)
                    onText(t)
                }
                val tcs = delta.arr("tool_calls")
                if (tcs != null) readToolCalls(tcs)
            }
            val fr = c.str("finish_reason")
            if (fr.isNotEmpty()) finish = fr
        }

        Http.streamLines(url, "POST", headers(p, true), buildBody(req, variant), canceller) { line ->
            val l = line.trimEnd()
            if (l.startsWith("data:")) {
                val payload = l.substring(5).trim()
                if (payload == "[DONE]") return@streamLines false
                if (payload.isNotEmpty()) {
                    sawData = true
                    handleChunk(payload)
                }
            } else if (l.isNotEmpty() && !l.startsWith(":") && !l.startsWith("event:") && !l.startsWith("id:") && !l.startsWith("retry:")) {
                raw.append(l).append('\n')
            }
            true
        }

        if (!sawData && raw.isNotEmpty()) {
            // The server ignored stream=true and returned a plain JSON completion.
            val j = try { JSONObject(raw.toString()) } catch (e: Exception) {
                throw IOException("Unexpected response from server: " + raw.toString().take(300))
            }
            failIfError(j)
            val c = j.arr("choices")?.optJSONObject(0)
            val msg = c?.obj("message")
            if (msg != null) {
                val t = msg.str("content")
                if (t.isNotEmpty()) {
                    text.append(t)
                    onText(t)
                }
                msg.arr("tool_calls")?.let { readToolCalls(it) }
            }
            finish = c?.str("finish_reason") ?: ""
        }

        val list = ArrayList<ToolCall>()
        var n = 0
        for (c in calls.values) {
            if (c.name.isEmpty()) continue
            list.add(ToolCall(c.id.ifEmpty { "call_${System.nanoTime()}_$n" }, c.name, parseToolArgs(c.args.toString())))
            n++
        }
        return AssistantTurn(text.toString(), list, finish)
    }

    override fun listModels(profile: ProviderProfile, canceller: Canceller): List<String> {
        if (profile.baseUrl.isBlank()) throw IOException("Fill in the base URL first")
        val r = Http.request(root(profile.baseUrl) + "/models", headers = headers(profile, false), canceller = canceller, readTimeoutMs = 25000)
        if (r.code >= 400) throw HttpException(r.code, r.text)
        val j = JSONObject(r.text)
        val data = j.arr("data") ?: j.arr("models") ?: return emptyList()
        val ids = ArrayList<String>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i)
            val id = if (o != null) o.str("id").ifEmpty { o.str("name") } else data.optString(i, "")
            if (id.isNotEmpty()) ids.add(id)
        }
        return ids.sorted()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Anthropic Messages API
// ─────────────────────────────────────────────────────────────────────────────

class AnthropicClient : LlmClient {
    companion object {
        private val noTemperature = ConcurrentHashMap<String, Boolean>()
    }

    private class Block(val type: String, val id: String, val name: String) {
        val text = StringBuilder()
        val json = StringBuilder()
    }

    private fun root(base: String): String {
        var b = base.trim().trimEnd('/')
        if (b.endsWith("/v1/messages")) b = b.removeSuffix("/v1/messages")
        if (b.endsWith("/v1")) b = b.removeSuffix("/v1")
        return b.trimEnd('/')
    }

    private fun headers(p: ProviderProfile, stream: Boolean): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["Content-Type"] = "application/json"
        h["Accept"] = if (stream) "text/event-stream" else "application/json"
        h["anthropic-version"] = "2023-06-01"
        if (p.apiKey.isNotBlank()) h["x-api-key"] = p.apiKey.trim()
        return h
    }

    private fun buildMessages(req: ChatRequest): JSONArray {
        val arr = JSONArray()
        var pending: JSONArray? = null

        fun flush() {
            val pr = pending
            if (pr != null) {
                arr.put(jsonOf("role" to "user", "content" to pr))
                pending = null
            }
        }

        for (m in req.messages) {
            when (m.role) {
                Role.TOOL -> {
                    val pr = pending ?: JSONArray().also { pending = it }
                    pr.put(
                        jsonOf(
                            "type" to "tool_result", "tool_use_id" to m.toolCallId,
                            "content" to m.text.ifEmpty { "(no output)" }, "is_error" to m.isError
                        )
                    )
                }
                Role.USER -> {
                    flush()
                    val blocks = JSONArray()
                    if (req.profile.vision) {
                        for (path in m.images) {
                            val img = req.platform.encodeImage(path) ?: continue
                            blocks.put(
                                jsonOf(
                                    "type" to "image",
                                    "source" to jsonOf("type" to "base64", "media_type" to img.mime, "data" to img.base64)
                                )
                            )
                        }
                    }
                    if (m.text.isNotBlank() || blocks.length() == 0) {
                        blocks.put(jsonOf("type" to "text", "text" to m.text.ifBlank { " " }))
                    }
                    arr.put(jsonOf("role" to "user", "content" to blocks))
                }
                Role.ASSISTANT -> {
                    flush()
                    val blocks = JSONArray()
                    if (m.text.isNotBlank()) blocks.put(jsonOf("type" to "text", "text" to m.text))
                    for (c in m.toolCalls) {
                        blocks.put(jsonOf("type" to "tool_use", "id" to c.id, "name" to c.name, "input" to c.args))
                    }
                    if (blocks.length() > 0) arr.put(jsonOf("role" to "assistant", "content" to blocks))
                }
                Role.NOTE -> {}
            }
        }
        flush()
        return arr
    }

    private fun buildBody(req: ChatRequest, withTemperature: Boolean): ByteArray {
        val p = req.profile
        val body = JSONObject()
        body.put("model", p.model)
        body.put("max_tokens", p.maxTokens)
        body.put("stream", true)
        if (req.system.isNotBlank()) body.put("system", req.system)
        if (withTemperature) body.put("temperature", p.temperature.coerceIn(0.0, 1.0))
        body.put("messages", buildMessages(req))
        if (req.tools.isNotEmpty()) {
            val tools = JSONArray()
            for (t in req.tools) {
                tools.put(jsonOf("name" to t.name, "description" to t.description, "input_schema" to t.parameters))
            }
            body.put("tools", tools)
        }
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    override fun chat(req: ChatRequest, canceller: Canceller, onText: (String) -> Unit): AssistantTurn {
        requireModel(req.profile)
        var withTemp = noTemperature[req.profile.id] != true
        var attempts = 0
        while (true) {
            try {
                return doChat(req, withTemp, canceller, onText)
            } catch (e: HttpException) {
                attempts++
                if (withTemp && e.code in 400..422 && e.body.lowercase().contains("temperature") && attempts <= 2) {
                    withTemp = false
                    noTemperature[req.profile.id] = true
                } else {
                    throw e
                }
            }
        }
    }

    private fun doChat(req: ChatRequest, withTemp: Boolean, canceller: Canceller, onText: (String) -> Unit): AssistantTurn {
        val p = req.profile
        val url = root(p.baseUrl) + "/v1/messages"
        val blocks = TreeMap<Int, Block>()
        var stop = ""
        var sawData = false
        val raw = StringBuilder()
        val plainText = StringBuilder()

        fun handle(payload: String) {
            val j = try { JSONObject(payload) } catch (e: Exception) { return }
            when (j.str("type")) {
                "content_block_start" -> {
                    val cb = j.obj("content_block") ?: return
                    val b = Block(cb.str("type"), cb.str("id"), cb.str("name"))
                    val initial = cb.str("text")
                    if (b.type == "text" && initial.isNotEmpty()) {
                        b.text.append(initial)
                        onText(initial)
                    }
                    blocks[j.int("index")] = b
                }
                "content_block_delta" -> {
                    val b = blocks[j.int("index")] ?: return
                    val d = j.obj("delta") ?: return
                    when (d.str("type")) {
                        "text_delta" -> {
                            val t = d.str("text")
                            if (t.isNotEmpty()) {
                                b.text.append(t)
                                onText(t)
                            }
                        }
                        "input_json_delta" -> b.json.append(d.str("partial_json"))
                    }
                }
                "message_delta" -> {
                    val sr = j.obj("delta")?.str("stop_reason") ?: ""
                    if (sr.isNotEmpty()) stop = sr
                }
                "error" -> {
                    val e = j.obj("error")
                    throw IOException("Provider error: " + (e?.str("message") ?: payload.take(300)))
                }
            }
        }

        Http.streamLines(url, "POST", headers(p, true), buildBody(req, withTemp), canceller) { line ->
            val l = line.trim()
            if (l.startsWith("data:")) {
                val payload = l.substring(5).trim()
                if (payload.isNotEmpty() && payload != "[DONE]") {
                    sawData = true
                    handle(payload)
                }
            } else if (l.startsWith("{")) {
                raw.append(l)
            }
            true
        }

        val calls = ArrayList<ToolCall>()
        if (!sawData && raw.isNotEmpty()) {
            val j = try { JSONObject(raw.toString()) } catch (e: Exception) {
                throw IOException("Unexpected response from server: " + raw.toString().take(300))
            }
            if (j.str("type") == "error") throw IOException("Provider error: " + (j.obj("error")?.str("message") ?: raw.toString().take(300)))
            stop = j.str("stop_reason")
            for (b in j.arr("content")?.objects() ?: emptyList()) {
                when (b.str("type")) {
                    "text" -> {
                        plainText.append(b.str("text"))
                        onText(b.str("text"))
                    }
                    "tool_use" -> calls.add(ToolCall(b.str("id"), b.str("name"), b.obj("input") ?: JSONObject()))
                }
            }
        } else {
            for (b in blocks.values) {
                when (b.type) {
                    "text" -> plainText.append(b.text)
                    "tool_use" -> calls.add(ToolCall(b.id, b.name, parseToolArgs(b.json.toString())))
                }
            }
        }
        return AssistantTurn(plainText.toString(), calls, stop)
    }

    override fun listModels(profile: ProviderProfile, canceller: Canceller): List<String> {
        if (profile.baseUrl.isBlank()) throw IOException("Fill in the base URL first")
        val r = Http.request(root(profile.baseUrl) + "/v1/models?limit=100", headers = headers(profile, false), canceller = canceller, readTimeoutMs = 25000)
        if (r.code >= 400) throw HttpException(r.code, r.text)
        val data = JSONObject(r.text).arr("data") ?: return emptyList()
        return data.objects().map { it.str("id") }.filter { it.isNotEmpty() }
    }
}
