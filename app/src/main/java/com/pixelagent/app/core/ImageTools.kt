package com.pixelagent.app.core

import org.json.JSONObject
import java.io.File
import java.util.Locale

private fun mimeFor(f: File): String = when (f.extension.lowercase()) {
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    else -> "image/jpeg"
}

private fun isUrl(s: String) = s.startsWith("http://") || s.startsWith("https://")

private fun fmtTime(sec: Double): String {
    val t = sec.toInt()
    return String.format(Locale.US, "%d:%02d", t / 60, t % 60)
}

/** trace.moe: identifies the anime, episode and timestamp of a screenshot. */
fun traceMoe(image: String, ctx: ToolContext): String {
    val base = Endpoints.traceMoe.trimEnd('/')
    val r = if (isUrl(image)) {
        Http.request("$base/search?anilistInfo&url=${Http.urlEncode(image)}", canceller = ctx.canceller, readTimeoutMs = 60000)
    } else {
        val f = ctx.resolve(image)
        if (!f.isFile) throw ToolError("Image not found: ${ctx.rel(f)}")
        if (f.length() > 25L * 1024 * 1024) throw ToolError("Image is larger than trace.moe's 25 MB limit")
        Http.request(
            "$base/search?anilistInfo", "POST", mapOf("Content-Type" to mimeFor(f)), f.readBytes(),
            canceller = ctx.canceller, readTimeoutMs = 90000
        )
    }
    if (r.code == 429) return "trace.moe: rate limit or quota reached. Try again in a minute."
    val json = try { JSONObject(r.text) } catch (e: Exception) { return "trace.moe: unexpected response (HTTP ${r.code})" }
    val err = json.str("error")
    if (err.isNotEmpty()) return "trace.moe error: $err"
    val results = json.arr("result")?.objects() ?: emptyList()
    if (results.isEmpty()) return "trace.moe: no anime scene matched."
    val sb = StringBuilder("trace.moe (anime scene finder):\n")
    for ((i, res) in results.take(3).withIndex()) {
        val sim = res.dbl("similarity") * 100
        val anilist = res.obj("anilist")
        val title: String
        var link = ""
        if (anilist != null) {
            val t = anilist.obj("title")
            title = listOfNotNull(
                t?.str("english")?.ifEmpty { null },
                t?.str("romaji")?.ifEmpty { null },
                t?.str("native")?.ifEmpty { null }
            ).distinct().joinToString(" / ").ifEmpty { "Unknown" }
            val id = anilist.int("id")
            if (id > 0) link = "https://anilist.co/anime/$id"
        } else {
            title = "AniList id ${res.str("anilist")}"
        }
        val ep = res.str("episode").ifEmpty { "?" }
        sb.append(String.format(Locale.US, "%d. %s — episode %s at %s (similarity %.1f%%)\n", i + 1, title, ep, fmtTime(res.dbl("from")), sim))
        if (link.isNotEmpty()) sb.append("   $link\n")
        val preview = res.str("video")
        if (preview.isNotEmpty() && i == 0) sb.append("   preview: $preview\n")
    }
    val best = results[0].dbl("similarity")
    if (best < 0.87) sb.append("Note: best similarity is below 87%, so the match is probably wrong or the image is not from an anime.\n")
    return sb.toString().trim()
}

/** SauceNAO: finds the source of artwork / illustrations. Needs a free API key. */
fun sauceNao(image: String, ctx: ToolContext): String {
    val key = ctx.settings.sauceNaoKey.trim()
    if (key.isEmpty()) return "SauceNAO skipped: add a free API key in Settings → Image search (saucenao.com/user.php)."
    val base = Endpoints.sauceNao.trimEnd('/')
    val common = "output_type=2&numres=5&db=999&api_key=${Http.urlEncode(key)}"
    val r = if (isUrl(image)) {
        Http.request("$base/search.php?$common&url=${Http.urlEncode(image)}", canceller = ctx.canceller, readTimeoutMs = 60000)
    } else {
        val f = ctx.resolve(image)
        if (!f.isFile) throw ToolError("Image not found: ${ctx.rel(f)}")
        val mp = Multipart()
            .field("api_key", key).field("output_type", "2").field("numres", "5").field("db", "999")
            .file("file", f.name, mimeFor(f), f.readBytes())
        Http.request(
            "$base/search.php", "POST", mapOf("Content-Type" to mp.contentType), mp.finish(),
            canceller = ctx.canceller, readTimeoutMs = 90000
        )
    }
    if (r.code == 429) return "SauceNAO: daily/30-second search limit reached."
    val json = try { JSONObject(r.text) } catch (e: Exception) { return "SauceNAO: unexpected response (HTTP ${r.code})" }
    val header = json.obj("header")
    if (header != null && header.int("status") != 0) {
        return "SauceNAO error: ${header.str("message", "status " + header.int("status"))}"
    }
    val results = json.arr("results")?.objects() ?: emptyList()
    if (results.isEmpty()) return "SauceNAO: no source found."
    val sb = StringBuilder("SauceNAO (artwork source finder):\n")
    for ((i, res) in results.take(4).withIndex()) {
        val h = res.obj("header")
        val d = res.obj("data") ?: JSONObject()
        val sim = h?.str("similarity") ?: "?"
        val title = d.str("title").ifEmpty { d.str("source") }.ifEmpty { d.str("material") }.ifEmpty { "(untitled)" }
        val author = d.str("member_name").ifEmpty { d.str("author_name") }.ifEmpty {
            val c = d.opt("creator")
            if (c is org.json.JSONArray) c.strings().joinToString(", ") else d.str("creator")
        }
        sb.append("${i + 1}. $title")
        if (author.isNotEmpty()) sb.append(" — by $author")
        sb.append("  (similarity $sim%, ${h?.str("index_name") ?: ""})\n")
        val urls = d.arr("ext_urls")?.strings() ?: emptyList()
        for (u in urls.take(2)) sb.append("   $u\n")
        val chars = d.str("characters")
        if (chars.isNotEmpty()) sb.append("   characters: $chars\n")
    }
    val best = results[0].obj("header")?.str("similarity")?.toDoubleOrNull() ?: 0.0
    if (best < 80.0) sb.append("Note: best similarity is below 80%; treat results as weak leads.\n")
    return sb.toString().trim()
}

fun imageTools(): List<Tool> = listOf(
    Tool(
        "reverse_image_search",
        "Find where an image comes from. Uses trace.moe (identifies the anime, episode and timestamp of a screenshot) and SauceNAO (artwork source). " +
            "Accepts a file in the workspace or a public image URL.",
        Risk.READ,
        objectSchema(
            P("image", "string", "Workspace file path or http(s) image URL", required = true),
            P("engines", "array", "Engines to use: trace_moe, saucenao. Default: both.")
        )
    ) { a, ctx ->
        val image = a.req("image")
        val engines = a.arr("engines")?.strings()?.map { it.lowercase().replace("-", "_").replace(".", "_") }
            ?.takeIf { it.isNotEmpty() } ?: listOf("trace_moe", "saucenao")
        val out = ArrayList<String>()
        for (e in engines) {
            ctx.canceller.check()
            try {
                when (e) {
                    "trace_moe", "tracemoe" -> out.add(traceMoe(image, ctx))
                    "saucenao" -> out.add(sauceNao(image, ctx))
                    else -> out.add("Unknown engine '$e' (use trace_moe or saucenao)")
                }
            } catch (ex: CancelledException) {
                throw ex
            } catch (ex: ToolError) {
                throw ex
            } catch (ex: Exception) {
                out.add("$e failed: ${ex.message}")
            }
        }
        if (isUrl(image)) {
            val enc = Http.urlEncode(image)
            out.add(
                "More engines (open in a browser with open_url):\n" +
                    "- Google Lens: https://lens.google.com/uploadbyurl?url=$enc\n" +
                    "- Yandex: https://yandex.com/images/search?rpt=imageview&url=$enc\n" +
                    "- TinEye: https://tineye.com/search?url=$enc"
            )
        } else {
            out.add("For a broader web search, open https://lens.google.com or https://yandex.com/images and upload the file manually.")
        }
        out.joinToString("\n\n")
    }
)
