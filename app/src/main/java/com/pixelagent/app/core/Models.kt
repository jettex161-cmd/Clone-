package com.pixelagent.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Role { USER, ASSISTANT, TOOL, NOTE }
enum class ToolExecutionState { PENDING, AWAITING_CONFIRMATION, EXECUTING, SUCCEEDED, FAILED, INTERRUPTED }

data class ToolCall(
    val id: String,
    val name: String,
    val args: JSONObject,
    val providerMetadata: JSONObject = JSONObject(),
    val executionState: ToolExecutionState = ToolExecutionState.PENDING
)

/** One entry of a conversation. NOTE messages are UI-only notices and are never sent to a model. */
data class Msg(
    val role: Role,
    val text: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String = "",
    val toolName: String = "",
    val images: List<String> = emptyList(),
    val isError: Boolean = false,
    val ts: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("role", role.name)
        o.put("text", text)
        if (toolCalls.isNotEmpty()) {
            val a = JSONArray()
            for (c in toolCalls) {
                val call = jsonOf("id" to c.id, "name" to c.name, "args" to c.args)
                if (c.providerMetadata.length() > 0) call.put("providerMetadata", c.providerMetadata)
                call.put("executionState", c.executionState.name)
                a.put(call)
            }
            o.put("toolCalls", a)
        }
        if (toolCallId.isNotEmpty()) o.put("toolCallId", toolCallId)
        if (toolName.isNotEmpty()) o.put("toolName", toolName)
        if (images.isNotEmpty()) o.put("images", jsonArrayOf(images))
        if (isError) o.put("isError", true)
        o.put("ts", ts)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Msg {
            val calls = ArrayList<ToolCall>()
            val ca = o.arr("toolCalls")
            if (ca != null) {
                for (c in ca.objects()) {
                    calls.add(ToolCall(
                        c.str("id"), c.str("name"), c.obj("args") ?: JSONObject(),
                        c.obj("providerMetadata") ?: JSONObject(),
                        try { ToolExecutionState.valueOf(c.str("executionState", "PENDING")) } catch (_: IllegalArgumentException) { ToolExecutionState.PENDING }
                    ))
                }
            }
            val role = try {
                Role.valueOf(o.str("role", "NOTE"))
            } catch (e: IllegalArgumentException) {
                Role.NOTE
            }
            return Msg(
                role = role,
                text = o.str("text"),
                toolCalls = calls,
                toolCallId = o.str("toolCallId"),
                toolName = o.str("toolName"),
                images = o.arr("images")?.strings() ?: emptyList(),
                isError = o.bool("isError"),
                ts = o.long("ts", System.currentTimeMillis())
            )
        }
    }
}

/** LOCAL = a model file (.litertlm) run inside this app; for LOCAL, ProviderProfile.model holds the file path. */
enum class ProviderType { OPENAI, ANTHROPIC, LOCAL }

/** How tools are offered to the model. NATIVE = API function calling; TEXT = prompt-based (works with any model). */
enum class ToolMode { NATIVE, TEXT, NONE }

enum class ModelRoutingMode { MANUAL, LOCAL_ONLY, LOCAL_FIRST, CLOUD_ONLY }

enum class ConfirmMode { NEVER, RISKY, WRITES, ALL }

fun newId(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 10)

data class ProviderProfile(
    val id: String = newId(),
    val name: String = "New provider",
    val type: ProviderType = ProviderType.OPENAI,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096,
    val toolMode: ToolMode = ToolMode.NATIVE,
    val vision: Boolean = true,
    val visionVerified: Boolean = false
) {
    fun toJson(): JSONObject = jsonOf(
        "id" to id, "name" to name, "type" to type.name, "baseUrl" to baseUrl,
        "apiKey" to apiKey, "model" to model, "temperature" to temperature,
        "maxTokens" to maxTokens, "toolMode" to toolMode.name, "vision" to vision, "visionVerified" to visionVerified
    )

    companion object {
        fun fromJson(o: JSONObject): ProviderProfile = ProviderProfile(
            id = o.str("id").ifEmpty { newId() },
            name = o.str("name", "Provider"),
            type = try { ProviderType.valueOf(o.str("type", "OPENAI")) } catch (e: IllegalArgumentException) { ProviderType.OPENAI },
            baseUrl = o.str("baseUrl"),
            apiKey = o.str("apiKey"),
            model = o.str("model"),
            temperature = o.dbl("temperature", 0.7),
            maxTokens = o.int("maxTokens", 4096),
            toolMode = try { ToolMode.valueOf(o.str("toolMode", "NATIVE")) } catch (e: IllegalArgumentException) { ToolMode.NATIVE },
            vision = o.bool("vision", true),
            visionVerified = o.bool("visionVerified", false)
        )
    }
}

class ProviderPreset(
    val label: String,
    val type: ProviderType,
    val baseUrl: String,
    val model: String,
    val note: String = ""
) {
    fun toProfile(): ProviderProfile =
        if (type == ProviderType.LOCAL) ProviderProfile(
            name = label, type = type, baseUrl = "", model = model,
            temperature = 0.7, maxTokens = 1024, toolMode = ToolMode.TEXT, vision = false
        ) else ProviderProfile(name = label, type = type, baseUrl = baseUrl, model = model)
}

object ProviderPresets {
    val all: List<ProviderPreset> = listOf(
        ProviderPreset("On-device model (runs inside this app, no server)", ProviderType.LOCAL, "", "",
            "Pick a .litertlm model file (e.g. Gemma). Works offline."),
        ProviderPreset("Anthropic (Claude)", ProviderType.ANTHROPIC, "https://api.anthropic.com", "claude-sonnet-5-5",
            "API key from console.anthropic.com"),
        ProviderPreset("OpenAI", ProviderType.OPENAI, "https://api.openai.com/v1", "gpt-4o-mini",
            "API key from platform.openai.com"),
        ProviderPreset("OpenRouter (any model)", ProviderType.OPENAI, "https://openrouter.ai/api/v1", "openai/gpt-4o-mini",
            "One key, hundreds of models"),
        ProviderPreset("Google Gemini", ProviderType.OPENAI, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash",
            "Key from aistudio.google.com"),
        ProviderPreset("Groq", ProviderType.OPENAI, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
        ProviderPreset("DeepSeek", ProviderType.OPENAI, "https://api.deepseek.com/v1", "deepseek-chat"),
        ProviderPreset("Mistral", ProviderType.OPENAI, "https://api.mistral.ai/v1", "mistral-large-latest"),
        ProviderPreset("Ollama (this phone via Termux, or your PC)", ProviderType.OPENAI, "http://127.0.0.1:11434/v1", "llama3.2",
            "No key needed. Use your PC's LAN address instead of 127.0.0.1 to reach another machine."),
        ProviderPreset("llama.cpp server (Termux / PC)", ProviderType.OPENAI, "http://127.0.0.1:8080/v1", "local",
            "llama-server exposes an OpenAI-compatible API"),
        ProviderPreset("LM Studio (PC on your Wi-Fi)", ProviderType.OPENAI, "http://192.168.1.10:1234/v1", "local-model"),
        ProviderPreset("Custom OpenAI-compatible", ProviderType.OPENAI, "https://", "")
    )
}

data class AgentSettings(
    val systemPrompt: String = DEFAULT_PERSONA,
    /** Empty = automatic (/sdcard/PixelAgent when all-files access is granted, else the app's own folder). */
    val workspacePath: String = "",
    val allowOutsideWorkspace: Boolean = false,
    val confirmMode: ConfirmMode = ConfirmMode.RISKY,
    val maxSteps: Int = 25,
    val maxToolOutputChars: Int = 12000,
    val contextChars: Int = 100000,
    val modelRoutingMode: ModelRoutingMode = ModelRoutingMode.MANUAL,
    val allowCloudFallback: Boolean = false,
    val maxModelSwitches: Int = 1,
    val modelTimeoutSeconds: Int = 120,
    val projectId: String = "general",
    val persistentMemoryEnabled: Boolean = true,
    val learnFromDocuments: Boolean = false,
    val shellEnabled: Boolean = true,
    val screenControlEnabled: Boolean = true,
    val speakReplies: Boolean = false,
    val sauceNaoKey: String = "",
    val searxngUrl: String = ""
) {
    fun toJson(): JSONObject = jsonOf(
        "systemPrompt" to systemPrompt, "workspacePath" to workspacePath,
        "allowOutsideWorkspace" to allowOutsideWorkspace, "confirmMode" to confirmMode.name,
        "maxSteps" to maxSteps, "maxToolOutputChars" to maxToolOutputChars, "contextChars" to contextChars,
        "modelRoutingMode" to modelRoutingMode.name, "allowCloudFallback" to allowCloudFallback,
        "maxModelSwitches" to maxModelSwitches, "modelTimeoutSeconds" to modelTimeoutSeconds,
        "projectId" to projectId,
        "persistentMemoryEnabled" to persistentMemoryEnabled, "learnFromDocuments" to learnFromDocuments,
        "shellEnabled" to shellEnabled, "screenControlEnabled" to screenControlEnabled,
        "speakReplies" to speakReplies, "sauceNaoKey" to sauceNaoKey, "searxngUrl" to searxngUrl
    )

    companion object {
        const val DEFAULT_PERSONA =
            "You are Pixel Agent, a capable personal AI assistant that lives on your owner's Android phone. " +
                "You work only for your owner and you are direct, resourceful and honest. " +
                "You can act, not just talk: use your tools to manage files, search and read the web, " +
                "reverse-search images (anime scenes and artwork), handle archives, and operate the phone. " +
                "Prefer doing the task over describing how to do it. Keep replies short and clear."

        fun fromJson(o: JSONObject): AgentSettings {
            val d = AgentSettings()
            return AgentSettings(
                systemPrompt = o.str("systemPrompt", d.systemPrompt).ifBlank { d.systemPrompt },
                workspacePath = o.str("workspacePath", ""),
                allowOutsideWorkspace = o.bool("allowOutsideWorkspace", d.allowOutsideWorkspace),
                confirmMode = try { ConfirmMode.valueOf(o.str("confirmMode", "RISKY")) } catch (e: IllegalArgumentException) { ConfirmMode.RISKY },
                maxSteps = o.int("maxSteps", d.maxSteps).coerceIn(1, 200),
                maxToolOutputChars = o.int("maxToolOutputChars", d.maxToolOutputChars).coerceIn(500, 200000),
                contextChars = o.int("contextChars", d.contextChars).coerceIn(4000, 2000000),
                modelRoutingMode = try { ModelRoutingMode.valueOf(o.str("modelRoutingMode", "MANUAL")) } catch (e: IllegalArgumentException) { ModelRoutingMode.MANUAL },
                allowCloudFallback = o.bool("allowCloudFallback", d.allowCloudFallback),
                maxModelSwitches = o.int("maxModelSwitches", d.maxModelSwitches).coerceIn(0, 5),
                modelTimeoutSeconds = o.int("modelTimeoutSeconds", d.modelTimeoutSeconds).coerceIn(15, 600),
                projectId = o.str("projectId", d.projectId).trim().ifEmpty { "general" }.take(80),
                persistentMemoryEnabled = o.bool("persistentMemoryEnabled", d.persistentMemoryEnabled),
                learnFromDocuments = o.bool("learnFromDocuments", d.learnFromDocuments),
                shellEnabled = o.bool("shellEnabled", d.shellEnabled),
                screenControlEnabled = o.bool("screenControlEnabled", d.screenControlEnabled),
                speakReplies = o.bool("speakReplies", d.speakReplies),
                sauceNaoKey = o.str("sauceNaoKey", ""),
                searxngUrl = o.str("searxngUrl", "")
            )
        }
    }
}
