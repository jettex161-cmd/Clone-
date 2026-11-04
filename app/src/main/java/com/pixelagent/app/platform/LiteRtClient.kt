package com.pixelagent.app.platform

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.pixelagent.app.core.AssistantTurn
import com.pixelagent.app.core.Canceller
import com.pixelagent.app.core.CancelledException
import com.pixelagent.app.core.ChatRequest
import com.pixelagent.app.core.LlmClient
import com.pixelagent.app.core.ProviderProfile
import com.pixelagent.app.core.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Runs a .litertlm model file inside the app (no server, no Termux).
 * The agent always uses prompt-based ("Text") tools with this client, so the whole conversation is
 * flattened to text: the system prompt + earlier turns go into the system instruction and the latest
 * user message is sent as the actual prompt.
 */
class LiteRtClient(private val ctx: Context) : LlmClient {

    companion object {
        private val lock = Any()
        private var engine: Engine? = null
        private var enginePath: String? = null

        /** Frees the model from memory (it can be several GB). */
        fun release() {
            synchronized(lock) {
                try { engine?.close() } catch (_: Throwable) {}
                engine = null
                enginePath = null
            }
        }

        private const val HISTORY_CHARS = 8000
        private const val PROMPT_CHARS = 6000
    }

    private fun obtainEngine(path: String): Engine = synchronized(lock) {
        val cur = engine
        if (cur != null && enginePath == path) return cur
        release()
        val f = File(path)
        if (path.isBlank()) throw IOException("No model file selected. Open the provider and pick a .litertlm model file.")
        if (!f.isFile) throw IOException("Model file not found: $path")
        val e = Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = ctx.cacheDir.path))
        try {
            e.initialize()
        } catch (t: Throwable) {
            try { e.close() } catch (_: Throwable) {}
            throw IOException("Could not load the model (${t.message ?: t.javaClass.simpleName}). Make sure it is a .litertlm file made for LiteRT-LM and that the phone has enough free RAM.")
        }
        engine = e
        enginePath = path
        e
    }

    override fun chat(req: ChatRequest, canceller: Canceller, onText: (String) -> Unit): AssistantTurn {
        val eng = obtainEngine(req.profile.model.trim())
        canceller.check()

        val msgs = req.messages.filter { it.role == Role.USER || it.role == Role.ASSISTANT }
        val lastUser = msgs.indexOfLast { it.role == Role.USER }
        if (lastUser < 0) throw IOException("Nothing to send to the model.")
        val prompt = msgs[lastUser].text.let { if (it.length > PROMPT_CHARS) it.takeLast(PROMPT_CHARS) else it }

        // Earlier turns as a transcript (most recent first until the budget is used up).
        val lines = ArrayList<String>()
        var used = 0
        for (i in lastUser - 1 downTo 0) {
            val m = msgs[i]
            val line = (if (m.role == Role.USER) "User: " else "Assistant: ") + m.text.trim()
            if (used + line.length > HISTORY_CHARS) break
            lines.add(line)
            used += line.length
        }
        lines.reverse()
        val system = buildString {
            append(req.system)
            if (lines.isNotEmpty()) {
                append("\n\n## Conversation so far\n")
                append(lines.joinToString("\n\n"))
            }
        }

        val conv: Conversation = eng.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = req.profile.temperature),
                maxOutputToken = req.profile.maxTokens.coerceIn(64, 8192)
            )
        )

        val queue = LinkedBlockingQueue<Any>()
        val done = Any()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch {
            try {
                conv.sendMessageAsync(prompt).collect { queue.put(it.toString()) }
                queue.put(done)
            } catch (t: Throwable) {
                queue.put(t)
            }
        }

        val out = StringBuilder()
        try {
            while (true) {
                if (canceller.cancelled) throw CancelledException()
                val item = queue.poll(150, TimeUnit.MILLISECONDS) ?: continue
                if (item === done) break
                if (item is Throwable) {
                    if (canceller.cancelled) throw CancelledException()
                    throw IOException("The on-device model failed: " + (item.message ?: item.javaClass.simpleName))
                }
                val delta = item as String
                out.append(delta)
                onText(delta)
            }
        } finally {
            job.cancel()
            scope.cancel()
            // close() may block while the native side winds down, so never do it on the caller's thread
            val t = Thread { try { conv.close() } catch (_: Throwable) {} }
            t.isDaemon = true
            t.start()
        }
        return AssistantTurn(out.toString(), emptyList(), "stop")
    }

    override fun listModels(profile: ProviderProfile, canceller: Canceller): List<String> {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models")
        return (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && (it.name.endsWith(".litertlm") || it.name.endsWith(".task")) }
            .map { it.path }
    }
}
