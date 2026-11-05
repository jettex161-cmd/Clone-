package com.pixelagent.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class MemoryEntry(
    val id: String,
    val text: String,
    val ts: Long,
    val projectId: String = "general",
    val source: String = "",
    val kind: String = "fact",
    val digest: String = ""
)

/** Long-term notes the agent (or the user) saved; injected into every system prompt. */
class MemoryStore(private val file: File) {
    private val lock = Any()

    fun all(): List<MemoryEntry> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            arr.objects().map {
                MemoryEntry(it.str("id"), it.str("text"), it.long("ts"), it.str("projectId", "general"), it.str("source"), it.str("kind", "fact"), it.str("digest"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(list: List<MemoryEntry>) {
        val arr = JSONArray()
        for (e in list) arr.put(jsonOf(
            "id" to e.id, "text" to e.text, "ts" to e.ts, "projectId" to e.projectId,
            "source" to e.source, "kind" to e.kind, "digest" to e.digest
        ))
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun add(text: String, projectId: String = "general"): MemoryEntry? = synchronized(lock) {
        val clean = text.trim().take(500)
        if (clean.isEmpty() || containsSecret(clean)) return null
        val entries = all()
        val normalized = clean.lowercase().replace(Regex("\\s+"), " ")
        val existing = entries.firstOrNull {
            it.kind == "fact" && it.projectId == projectId && it.text.lowercase().replace(Regex("\\s+"), " ") == normalized
        }
        if (existing != null) return existing
        val entry = MemoryEntry(newId().substring(0, 6), clean, System.currentTimeMillis(), projectId)
        write(entries + entry)
        entry
    }

    fun delete(id: String, projectId: String? = null): Boolean = synchronized(lock) {
        val cur = all()
        val next = cur.filter { it.id != id || projectId != null && it.projectId != projectId && it.projectId != "general" }
        if (next.size == cur.size) return false
        write(next)
        true
    }

    fun update(id: String, text: String, projectId: String? = null): Boolean = synchronized(lock) {
        val clean = text.trim().take(500)
        if (clean.isEmpty() || containsSecret(clean)) return false
        val entries = all()
        if (entries.none { it.id == id && it.kind == "fact" && (projectId == null || it.projectId == projectId || it.projectId == "general") }) return false
        write(entries.map { if (it.id == id) it.copy(text = clean, ts = System.currentTimeMillis()) else it })
        true
    }

    fun search(query: String, projectId: String = "general", limit: Int = 8): List<MemoryEntry> {
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}_-]+")).filter { it.length > 1 }.toSet()
        val candidates = all().filter { it.projectId == "general" || it.projectId == projectId }
        if (terms.isEmpty()) return candidates.filter { it.kind == "fact" }.takeLast(limit)
        return candidates.map { entry ->
            val text = entry.text.lowercase()
            val score = terms.sumOf { term -> if (text.contains(term)) 1 else 0 }
            entry to score
        }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<MemoryEntry, Int>> { it.second }.thenByDescending { it.first.ts })
            .take(limit).map { it.first }
    }

    fun clearProject(projectId: String): Int = synchronized(lock) {
        val entries = all()
        val next = entries.filterNot { it.projectId == projectId }
        write(next)
        entries.size - next.size
    }

    fun replaceDocument(source: String, projectId: String, digest: String, chunks: List<String>): Int = synchronized(lock) {
        if (source.isBlank() || chunks.isEmpty()) return 0
        val entries = all().toMutableList()
        if (entries.any { it.kind == "document" && it.projectId == projectId && it.source == source && it.digest.startsWith("$digest:") }) return 0
        entries.removeAll { it.kind == "document" && it.projectId == projectId && it.source == source }
        for ((index, chunk) in chunks.withIndex()) {
            if (chunk.isBlank()) continue
            entries.add(MemoryEntry(
                id = newId().substring(0, 6), text = chunk.take(1800), ts = System.currentTimeMillis(),
                projectId = projectId, source = source, kind = "document", digest = "$digest:$index"
            ))
        }
        write(entries)
        entries.count { it.kind == "document" && it.projectId == projectId && it.source == source }
    }

    fun removeDocument(source: String, projectId: String): Int = synchronized(lock) {
        val entries = all()
        val next = entries.filterNot { it.kind == "document" && it.projectId == projectId && it.source == source }
        write(next)
        entries.size - next.size
    }

    private fun containsSecret(text: String): Boolean = Regex(
        "(?i)(api[_ -]?key|password|passphrase|secret|access[_ -]?token)\\s*[:=]\\s*\\S+|\\bsk-[A-Za-z0-9]{12,}\\b"
    ).containsMatchIn(text)
}

data class DocumentIndexResult(val source: String, val chunkCount: Int, val duplicate: Boolean = false)

interface DocumentIndexer {
    fun index(file: File, projectId: String, canceller: Canceller): DocumentIndexResult
}

data class ChatMeta(val id: String, val title: String, val updated: Long, val excludedFromLearning: Boolean = false)

/** Stores each conversation as JSON on disk (chats/<id>.json plus a small <id>.meta for fast listing). */
class ChatStore(private val dir: File) {
    init {
        dir.mkdirs()
    }

    fun list(): List<ChatMeta> {
        val metas = ArrayList<ChatMeta>()
        for (f in dir.listFiles() ?: emptyArray()) {
            if (!f.name.endsWith(".meta")) continue
            try {
                val o = JSONObject(f.readText(Charsets.UTF_8))
                metas.add(ChatMeta(o.str("id"), o.str("title", "Chat"), o.long("updated"), o.bool("excludedFromLearning")))
            } catch (e: Exception) {
                // skip corrupt entry
            }
        }
        return metas.sortedByDescending { it.updated }
    }

    fun load(id: String): List<Msg> {
        val f = File(dir, "$id.json")
        if (!f.exists()) return emptyList()
        return try {
            JSONObject(f.readText(Charsets.UTF_8)).arr("messages")?.objects()?.map { Msg.fromJson(it) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.path + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun save(id: String, title: String, messages: List<Msg>, excludedFromLearning: Boolean = false) {
        val now = System.currentTimeMillis()
        val arr = JSONArray()
        for (m in messages) arr.put(m.toJson())
        atomicWrite(File(dir, "$id.json"), jsonOf("id" to id, "title" to title, "updated" to now, "excludedFromLearning" to excludedFromLearning, "messages" to arr).toString())
        atomicWrite(File(dir, "$id.meta"), jsonOf("id" to id, "title" to title, "updated" to now, "excludedFromLearning" to excludedFromLearning).toString())
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        File(dir, "$id.meta").delete()
    }
}
