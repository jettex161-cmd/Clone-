package com.pixelagent.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pixelagent.app.core.AgentEngine
import com.pixelagent.app.core.AgentListener
import com.pixelagent.app.core.AgentSettings
import com.pixelagent.app.core.ApprovalDecision
import com.pixelagent.app.core.ApprovalRequest
import com.pixelagent.app.core.Canceller
import com.pixelagent.app.core.ChatMeta
import com.pixelagent.app.core.ChatRequest
import com.pixelagent.app.core.ChatStore
import com.pixelagent.app.core.MemoryEntry
import com.pixelagent.app.core.MemoryStore
import com.pixelagent.app.core.Msg
import com.pixelagent.app.core.ProviderProfile
import com.pixelagent.app.core.Role
import com.pixelagent.app.core.ToolRegistry
import com.pixelagent.app.core.clientFor
import com.pixelagent.app.core.LocalLlm
import com.pixelagent.app.platform.LiteRtClient
import com.pixelagent.app.core.newId
import com.pixelagent.app.platform.AgentService
import com.pixelagent.app.platform.AndroidPlatform
import com.pixelagent.app.platform.Perms
import com.pixelagent.app.platform.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.CompletableFuture

class AgentViewModel(app: Application) : AndroidViewModel(app) {
    private val appCtx: Context = app
    private val settingsStore = SettingsStore(app)
    private val chatStore = ChatStore(File(app.filesDir, "chats"))
    val memory = MemoryStore(File(app.filesDir, "memory.json"))
    private val platform = AndroidPlatform(app)
    private val registry = ToolRegistry.default()
    private val sessionAllowed = HashSet<String>()

    var settings by mutableStateOf(settingsStore.loadSettings())
        private set
    var profiles by mutableStateOf(settingsStore.loadProfiles())
        private set
    var activeProfileId by mutableStateOf(settingsStore.activeProfileId)
        private set
    var chats by mutableStateOf<List<ChatMeta>>(emptyList())
        private set
    var currentChatId by mutableStateOf(newId())
        private set
    var messages by mutableStateOf<List<Msg>>(emptyList())
        private set
    var running by mutableStateOf(false)
        private set
    var agentState by mutableStateOf("idle")
        private set
    var agentDetail by mutableStateOf("")
        private set
    var pendingApproval by mutableStateOf<ApprovalRequest?>(null)
        private set
    var pendingQuestion by mutableStateOf<String?>(null)
        private set
    var attachments by mutableStateOf<List<String>>(emptyList())
    var draft by mutableStateOf("")
    var toast by mutableStateOf<String?>(null)
    var memories by mutableStateOf<List<MemoryEntry>>(emptyList())
        private set

    /** Bumped when the app returns to the foreground so permission status rows refresh. */
    var permTick by mutableStateOf(0)
        private set

    private var engine: AgentEngine? = null
    private var job: Job? = null
    private var approvalFuture: CompletableFuture<ApprovalDecision>? = null
    private var questionFuture: CompletableFuture<String?>? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    val activeProfile: ProviderProfile?
        get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()

    init {
        LocalLlm.factory = { LiteRtClient(appCtx) }
        refreshChats()
        refreshMemory()
    }

    // ── permissions / workspace ───────────────────────────────────────────

    fun refreshPermissions() {
        permTick++
    }

    fun workspaceDir(): File {
        val custom = settings.workspacePath.trim()
        val fallback = File(getApplication<Application>().getExternalFilesDir(null) ?: appCtx.filesDir, "workspace")
        val dir = when {
            custom.isNotEmpty() -> File(custom)
            Perms.hasAllFiles(appCtx) -> File(Environment.getExternalStorageDirectory(), "PixelAgent")
            else -> fallback
        }
        if (!dir.exists() && !dir.mkdirs()) {
            fallback.mkdirs()
            return fallback
        }
        return dir
    }

    // ── chats ─────────────────────────────────────────────────────────────

    private fun refreshChats() {
        chats = chatStore.list()
    }

    private fun titleOf(list: List<Msg>): String {
        val first = list.firstOrNull { it.role == Role.USER }?.text?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return first.ifEmpty { "New chat" }.take(48)
    }

    private fun persist() {
        val snapshot = messages
        if (snapshot.isEmpty()) return
        try {
            chatStore.save(currentChatId, titleOf(snapshot), snapshot)
            refreshChats()
        } catch (e: Exception) {
            // storage problems must never crash a run
        }
    }

    fun newChat() {
        if (running) return
        currentChatId = newId()
        messages = emptyList()
        attachments = emptyList()
    }

    fun openChat(id: String) {
        if (running) return
        currentChatId = id
        messages = chatStore.load(id)
    }

    fun deleteChat(id: String) {
        if (running && id == currentChatId) return
        chatStore.delete(id)
        if (id == currentChatId) {
            currentChatId = newId()
            messages = emptyList()
        }
        refreshChats()
    }

    // ── providers & settings ──────────────────────────────────────────────

    fun saveProfile(p: ProviderProfile) {
        val exists = profiles.any { it.id == p.id }
        profiles = if (exists) profiles.map { if (it.id == p.id) p else it } else profiles + p
        settingsStore.saveProfiles(profiles)
        if (activeProfileId.isEmpty() || profiles.none { it.id == activeProfileId }) setActive(p.id)
    }

    fun deleteProfile(id: String) {
        profiles = profiles.filter { it.id != id }
        settingsStore.saveProfiles(profiles)
        if (activeProfileId == id) setActive(profiles.firstOrNull()?.id ?: "")
    }

    fun setActive(id: String) {
        activeProfileId = id
        settingsStore.activeProfileId = id
    }

    fun updateSettings(s: AgentSettings) {
        settings = s
        settingsStore.saveSettings(s)
    }

    /** Copies a model file chosen in the system picker into the app's private folder and returns its path. */
    fun importModel(uri: Uri, onStatus: (String) -> Unit, onDone: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            var path: String? = null
            try {
                val resolver = appCtx.contentResolver
                var name = "model.litertlm"
                resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { if (it.isNotBlank()) name = it }
                }
                name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val dir = File(appCtx.getExternalFilesDir(null) ?: appCtx.filesDir, "models")
                dir.mkdirs()
                val out = File(dir, name)
                val tmp = File(dir, "$name.part")
                var total = 0L
                resolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { o ->
                        val buf = ByteArray(1 shl 20)
                        var last = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            total += n
                            if (total - last > (32L shl 20)) {
                                last = total
                                val mb = total shr 20
                                withContext(Dispatchers.Main) { onStatus("Copying model… $mb MB") }
                            }
                        }
                    }
                }
                if (out.exists()) out.delete()
                if (!tmp.renameTo(out)) throw java.io.IOException("Could not save the model file")
                path = out.path
                val mb = total shr 20
                withContext(Dispatchers.Main) { onStatus("✓ Imported $name ($mb MB)") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onStatus("✗ Import failed: " + (e.message ?: e.javaClass.simpleName)) }
            }
            withContext(Dispatchers.Main) { onDone(path) }
        }
    }

    fun testProvider(p: ProviderProfile, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                val req = ChatRequest(p, "Reply with the single word OK.", listOf(Msg(Role.USER, "ping")), emptyList(), platform)
                val turn = clientFor(p).chat(req, Canceller()) { }
                "✓ Connected. Model replied: " + turn.text.trim().take(80).ifEmpty { "(empty reply)" }
            } catch (e: Exception) {
                "✗ " + AgentEngine.friendly(e)
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    fun fetchModels(p: ProviderProfile, onResult: (List<String>, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            var models: List<String> = emptyList()
            var error: String? = null
            try {
                models = clientFor(p).listModels(p, Canceller())
                if (models.isEmpty()) error = "The server returned no models."
            } catch (e: Exception) {
                error = AgentEngine.friendly(e)
            }
            withContext(Dispatchers.Main) { onResult(models, error) }
        }
    }

    // ── memory ────────────────────────────────────────────────────────────

    fun refreshMemory() {
        memories = memory.all()
    }

    fun addMemory(text: String) {
        if (text.isBlank()) return
        memory.add(text)
        refreshMemory()
    }

    fun deleteMemory(id: String) {
        memory.delete(id)
        refreshMemory()
    }

    // ── attachments (share sheet / picker) ────────────────────────────────

    fun attachUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cr = appCtx.contentResolver
                val mime = cr.getType(uri) ?: "image/jpeg"
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
                val dir = File(workspaceDir(), "attachments")
                dir.mkdirs()
                val out = File(dir, "img_${System.currentTimeMillis()}.$ext")
                cr.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                withContext(Dispatchers.Main) { attachments = attachments + out.path }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast = "Could not attach file: ${e.message}" }
            }
        }
    }

    fun attachPath(path: String) {
        if (path !in attachments) attachments = attachments + path
    }

    fun removeAttachment(path: String) {
        attachments = attachments.filter { it != path }
    }

    // ── running the agent ─────────────────────────────────────────────────

    fun send(text: String) {
        val t = text.trim()
        if (running || (t.isEmpty() && attachments.isEmpty())) return
        val provider = activeProfile
        if (provider == null) {
            toast = "Add an AI provider first (menu → Providers)."
            return
        }
        val imgs = attachments
        attachments = emptyList()
        val body = buildString {
            append(t)
            for (p in imgs) append("\n[Attached image: ").append(p).append("]")
        }.trim()
        val history = messages + Msg(Role.USER, body, images = imgs)
        messages = history
        draft = ""
        running = true
        agentState = "thinking"
        agentDetail = ""
        val serviceStartedAt = System.currentTimeMillis()
        try {
            ContextCompat.startForegroundService(appCtx, Intent(appCtx, AgentService::class.java))
        } catch (e: Exception) {
            // The foreground service is a nice-to-have; the run continues without it.
        }
        val eng = AgentEngine(provider, settings, workspaceDir(), platform, memory, registry, sessionAllowed, listener)
        engine = eng
        job = viewModelScope.launch(Dispatchers.IO) {
            val result = eng.run(history)
            messages = result
            persist()
            refreshMemory()
            running = false
            agentState = "idle"
            engine = null
            // Give the service time to call startForeground() before stopping it (avoids a rare crash on instant failures).
            val elapsed = System.currentTimeMillis() - serviceStartedAt
            if (elapsed < 2000) kotlinx.coroutines.delay(2000 - elapsed)
            try {
                appCtx.stopService(Intent(appCtx, AgentService::class.java))
            } catch (e: Exception) {
            }
            if (settings.speakReplies) {
                result.lastOrNull { it.role == Role.ASSISTANT && it.text.isNotBlank() }?.let { speak(it.text) }
            }
        }
    }

    fun stop() {
        engine?.cancel()
        approvalFuture?.complete(ApprovalDecision.DENY)
        questionFuture?.complete(null)
    }

    fun resolveApproval(d: ApprovalDecision) {
        approvalFuture?.complete(d)
    }

    fun answerQuestion(answer: String?) {
        questionFuture?.complete(answer)
    }

    private val listener = object : AgentListener {
        override fun onMessages(messages: List<Msg>) {
            this@AgentViewModel.messages = messages
            if (messages.lastOrNull()?.role == Role.TOOL) persist()
        }

        override fun onState(state: String, detail: String) {
            agentState = state
            agentDetail = detail
        }

        override fun confirm(request: ApprovalRequest): ApprovalDecision {
            val f = CompletableFuture<ApprovalDecision>()
            approvalFuture = f
            pendingApproval = request
            return try {
                f.get()
            } catch (e: Exception) {
                ApprovalDecision.DENY
            } finally {
                pendingApproval = null
                approvalFuture = null
            }
        }

        override fun ask(question: String): String? {
            val f = CompletableFuture<String?>()
            questionFuture = f
            pendingQuestion = question
            return try {
                f.get()
            } catch (e: Exception) {
                null
            } finally {
                pendingQuestion = null
                questionFuture = null
            }
        }
    }

    // ── speech ────────────────────────────────────────────────────────────

    private fun speak(text: String) {
        val clean = text.replace(Regex("[`*#>_]"), "").take(2500)
        if (tts == null) {
            tts = TextToSpeech(appCtx) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale.getDefault()
                    tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "reply")
                }
            }
        } else if (ttsReady) {
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "reply")
        }
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    override fun onCleared() {
        engine?.cancel()
        tts?.shutdown()
        LiteRtClient.release()
        super.onCleared()
    }
}
