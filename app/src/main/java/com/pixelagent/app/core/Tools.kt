package com.pixelagent.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** READ = no side effects, WRITE = changes files/state, DANGEROUS = can destroy data or act on the phone. */
enum class Risk { READ, WRITE, DANGEROUS }

/** A failure that should be reported back to the model as a normal tool error. */
class ToolError(message: String) : Exception(message)

/** Describes a tool to the model (JSON-schema parameters). */
class ToolSpec(val name: String, val description: String, val parameters: JSONObject)

/** Schema property helper. */
class P(
    val name: String,
    val type: String,
    val desc: String,
    val required: Boolean = false,
    val enum: List<String>? = null,
    val items: String? = null
)

fun objectSchema(vararg ps: P): JSONObject {
    val props = JSONObject()
    val req = JSONArray()
    for (p in ps) {
        val o = JSONObject()
        o.put("type", p.type)
        o.put("description", p.desc)
        if (p.enum != null) o.put("enum", jsonArrayOf(p.enum))
        if (p.type == "array") o.put("items", jsonOf("type" to (p.items ?: "string")))
        props.put(p.name, o)
        if (p.required) req.put(p.name)
    }
    val schema = JSONObject()
    schema.put("type", "object")
    schema.put("properties", props)
    if (req.length() > 0) schema.put("required", req)
    return schema
}

class Tool(
    val name: String,
    val description: String,
    val risk: Risk,
    val params: JSONObject,
    /** Group used to enable/disable sets of tools from Settings: "core", "shell", "screen". */
    val group: String = "core",
    private val summarizer: ((JSONObject) -> String)? = null,
    val run: (JSONObject, ToolContext) -> String
) {
    fun spec() = ToolSpec(name, description, params)

    fun summary(args: JSONObject): String {
        summarizer?.let { return it(args) }
        val keys = args.keyList().filter { it != "content" }
        if (keys.isEmpty()) return name
        return name + " " + keys.joinToString(", ") { k ->
            "$k=" + clip(args.optString(k, ""), 120).replace("\n", " ")
        }
    }
}

interface AgentListener {
    /** A new snapshot of the conversation (streaming text, tool results...). */
    fun onMessages(messages: List<Msg>)

    /** What the agent is doing right now: state is "thinking", "tool" or "idle". */
    fun onState(state: String, detail: String)

    /** Blocks until the user decides. */
    fun confirm(request: ApprovalRequest): ApprovalDecision

    /** Blocks until the user answers; null if they dismissed the question. */
    fun ask(question: String): String?
}

enum class ApprovalDecision { ALLOW_ONCE, ALLOW_SESSION, DENY }

class ApprovalRequest(val toolName: String, val risk: Risk, val summary: String, val argsJson: String)

class ToolContext(
    val settings: AgentSettings,
    val workspace: File,
    val platform: Platform,
    val memory: MemoryStore,
    val canceller: Canceller,
    val listener: AgentListener?
) {
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun formatDate(ms: Long): String = dateFmt.format(Date(ms))

    /** Resolves a path against the workspace and enforces the sandbox. */
    fun resolve(path: String): File {
        val p = path.trim().ifEmpty { "." }
        val base = workspace.canonicalFile
        val f = if (File(p).isAbsolute) File(p) else File(base, p)
        val canon = f.canonicalFile
        val inside = canon.path == base.path || canon.path.startsWith(base.path + File.separator)
        if (!inside && !settings.allowOutsideWorkspace) {
            throw ToolError(
                "Path '$path' is outside the workspace (${base.path}). " +
                    "Use a path inside the workspace, or ask the user to enable 'Allow access outside workspace' in Settings."
            )
        }
        return canon
    }

    fun isWorkspaceRoot(f: File): Boolean = f.canonicalFile.path == workspace.canonicalFile.path

    /** Path as shown to the model: relative to the workspace when inside it. */
    fun rel(f: File): String {
        val base = workspace.canonicalFile.path
        val p = f.canonicalFile.path
        return when {
            p == base -> "."
            p.startsWith(base + File.separator) -> p.substring(base.length + 1)
            else -> p
        }
    }
}

fun JSONObject.req(key: String): String {
    val v = str(key)
    if (v.isEmpty()) throw ToolError("Missing required parameter '$key'")
    return v
}

fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = -1
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return String.format(Locale.US, "%.1f %s", v, units[i])
}

class ToolRegistry(val tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }

    fun get(name: String): Tool? = byName[name]

    fun enabled(settings: AgentSettings): List<Tool> = tools.filter { isEnabled(it, settings) }

    fun isEnabled(t: Tool, settings: AgentSettings): Boolean = when (t.group) {
        "shell" -> settings.shellEnabled
        "screen" -> settings.screenControlEnabled
        else -> true
    }

    companion object {
        fun default(): ToolRegistry {
            val all = ArrayList<Tool>()
            all.addAll(fileTools())
            all.addAll(webTools())
            all.addAll(imageTools())
            all.addAll(deviceTools())
            all.addAll(screenTools())
            all.addAll(memoryTools())
            all.add(shellTool())
            all.add(askUserTool())
            return ToolRegistry(all)
        }
    }
}

fun askUserTool(): Tool = Tool(
    name = "ask_user",
    description = "Ask the user a question and wait for their typed answer. Use when a request is ambiguous or a decision is theirs to make.",
    risk = Risk.READ,
    params = objectSchema(P("question", "string", "The question to show the user", required = true))
) { a, ctx ->
    val q = a.req("question")
    val listener = ctx.listener ?: throw ToolError("Cannot ask the user in this context")
    listener.ask(q) ?: "The user dismissed the question without answering."
}
