package com.pixelagent.app.core

import org.json.JSONArray
import org.json.JSONObject

// Small helpers around org.json (which is built into Android). Android's optString() returns the
// literal text "null" for JSON nulls, so everything goes through these null-safe accessors.

fun JSONObject.str(key: String, def: String = ""): String =
    if (!has(key) || isNull(key)) def else optString(key, def)

fun JSONObject.int(key: String, def: Int = 0): Int =
    if (!has(key) || isNull(key)) def else optInt(key, def)

fun JSONObject.long(key: String, def: Long = 0L): Long =
    if (!has(key) || isNull(key)) def else optLong(key, def)

fun JSONObject.dbl(key: String, def: Double = 0.0): Double =
    if (!has(key) || isNull(key)) def else optDouble(key, def)

fun JSONObject.bool(key: String, def: Boolean = false): Boolean =
    if (!has(key) || isNull(key)) def else optBoolean(key, def)

fun JSONObject.obj(key: String): JSONObject? =
    if (!has(key) || isNull(key)) null else optJSONObject(key)

fun JSONObject.arr(key: String): JSONArray? =
    if (!has(key) || isNull(key)) null else optJSONArray(key)

fun JSONObject.keyList(): List<String> {
    val out = ArrayList<String>()
    val it = keys()
    while (it.hasNext()) out.add(it.next())
    return out
}

fun JSONArray.objects(): List<JSONObject> {
    val out = ArrayList<JSONObject>()
    for (i in 0 until length()) {
        val o = optJSONObject(i)
        if (o != null) out.add(o)
    }
    return out
}

fun JSONArray.strings(): List<String> {
    val out = ArrayList<String>()
    for (i in 0 until length()) {
        if (!isNull(i)) out.add(optString(i, ""))
    }
    return out
}

fun jsonOf(vararg pairs: Pair<String, Any?>): JSONObject {
    val o = JSONObject()
    for ((k, v) in pairs) {
        if (v != null) o.put(k, v)
    }
    return o
}

fun jsonArrayOf(items: Iterable<Any?>): JSONArray {
    val a = JSONArray()
    for (i in items) a.put(i)
    return a
}

/** Parse a JSON object string, returning an empty object when blank and null when invalid. */
fun parseObjectOrNull(s: String?): JSONObject? {
    if (s == null) return null
    val t = s.trim()
    if (t.isEmpty()) return JSONObject()
    return try {
        JSONObject(t)
    } catch (e: Exception) {
        null
    }
}

fun clip(s: String, max: Int): String =
    if (s.length <= max) s else s.substring(0, max) + "\n…[truncated ${s.length - max} chars]"
