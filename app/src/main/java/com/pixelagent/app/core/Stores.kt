package com.pixelagent.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MemoryEntry(val id: String, val text: String, val ts: Long)

/** Long-term notes the agent (or the user) saved; injected into every system prompt. */
class MemoryStore(private val file: File) {
    private val lock = Any()

    fun all(): List<MemoryEntry> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            arr.objects().map { MemoryEntry(it.str("id"), it.str("text"), it.long("ts")) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(list: List<MemoryEntry>) {
        val arr = JSONArray()
        for (e in list) arr.put(jsonOf("id" to e.id, "text" to e.text, "ts" to e.ts))
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun add(text: String): MemoryEntry = synchronized(lock) {
        val e = MemoryEntry(newId().substring(0, 6), text.trim().take(500), System.currentTimeMillis())
        write(all() + e)
        e
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val cur = all()
        val next = cur.filter { it.id != id }
        if (next.size == cur.size) return false
        write(next)
        true
    }
}

class ChatMeta(val id: String, val title: String, val updated: Long)

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
                metas.add(ChatMeta(o.str("id"), o.str("title", "Chat"), o.long("updated")))
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

    fun save(id: String, title: String, messages: List<Msg>) {
        val now = System.currentTimeMillis()
        val arr = JSONArray()
        for (m in messages) arr.put(m.toJson())
        atomicWrite(File(dir, "$id.json"), jsonOf("id" to id, "title" to title, "updated" to now, "messages" to arr).toString())
        atomicWrite(File(dir, "$id.meta"), jsonOf("id" to id, "title" to title, "updated" to now).toString())
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        File(dir, "$id.meta").delete()
    }
}
