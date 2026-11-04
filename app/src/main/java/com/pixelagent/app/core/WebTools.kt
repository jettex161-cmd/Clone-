package com.pixelagent.app.core

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.net.URLDecoder

/** Endpoints are variables so tests can point them at a local mock server. */
object Endpoints {
    @JvmField var duckDuckGo = "https://html.duckduckgo.com/html/"
    @JvmField var traceMoe = "https://api.trace.moe"
    @JvmField var sauceNao = "https://saucenao.com"
}

object Html {
    private val entities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "copy" to "©", "reg" to "®",
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "bull" to "•", "middot" to "·",
        "laquo" to "«", "raquo" to "»", "times" to "×", "trade" to "™", "euro" to "€"
    )

    fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(s) { m ->
            val g = m.groupValues[1]
            when {
                g.startsWith("#x") || g.startsWith("#X") -> g.substring(2).toIntOrNull(16)?.let { cp -> codePointToString(cp) } ?: m.value
                g.startsWith("#") -> g.substring(1).toIntOrNull()?.let { cp -> codePointToString(cp) } ?: m.value
                else -> entities[g] ?: m.value
            }
        }
    }

    private fun codePointToString(cp: Int): String =
        if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else ""

    fun title(html: String): String {
        val m = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
        return if (m == null) "" else decodeEntities(m.groupValues[1]).replace(Regex("\\s+"), " ").trim()
    }

    fun toText(html: String): String {
        val opts = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        var s = html
        s = Regex("<!--.*?-->", opts).replace(s, " ")
        s = Regex("<(script|style|noscript|svg|template|head)\\b.*?</\\1>", opts).replace(s, " ")
        s = Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE).replace(s, "\n- ")
        s = Regex("<br\\s*/?>|</(p|div|tr|h[1-6]|section|article|ul|ol|table|blockquote|pre|header|footer)>", RegexOption.IGNORE_CASE)
            .replace(s, "\n")
        s = Regex("<[^>]+>").replace(s, " ")
        s = decodeEntities(s)
        s = s.replace(' ', ' ')
        s = Regex("[ \\t\\x0B\\f\\r]+").replace(s, " ")
        s = Regex(" *\\n *").replace(s, "\n")
        s = Regex("\\n{3,}").replace(s, "\n\n")
        return s.trim()
    }

    fun links(html: String, baseUrl: String, max: Int): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val re = Regex("<a\\s[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (m in re.findAll(html)) {
            if (out.size >= max) break
            val href = decodeEntities(m.groupValues[1]).trim()
            if (href.startsWith("#") || href.startsWith("javascript:") || href.startsWith("mailto:")) continue
            val text = decodeEntities(Regex("<[^>]+>").replace(m.groupValues[2], " ")).replace(Regex("\\s+"), " ").trim()
            val abs = try { URL(URL(baseUrl), href).toString() } catch (e: Exception) { continue }
            if (text.isNotEmpty()) out.add(Pair(text.take(100), abs))
        }
        return out
    }
}

private class SearchHit(val title: String, val url: String, val snippet: String)

private fun parseDuckDuckGo(html: String): List<SearchHit> {
    val opts = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    val anchors = Regex("<a\\s+([^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*)>(.*?)</a>", opts).findAll(html).toList()
    val snippets = Regex("class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</(?:a|td|div|span)>", opts).findAll(html).toList()
    val hits = ArrayList<SearchHit>()
    for ((i, m) in anchors.withIndex()) {
        val attrs = m.groupValues[1]
        var href = Regex("href=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1) ?: continue
        href = Html.decodeEntities(href)
        if (href.startsWith("//")) href = "https:$href"
        if (href.contains("duckduckgo.com/l/")) {
            val u = Regex("[?&]uddg=([^&]+)").find(href)?.groupValues?.get(1)
            if (u != null) href = try { URLDecoder.decode(u, "UTF-8") } catch (e: Exception) { href }
        }
        if (href.contains("duckduckgo.com/y.js")) continue // ads
        val title = Html.decodeEntities(Regex("<[^>]+>").replace(m.groupValues[2], "")).trim()
        val nextStart = if (i + 1 < anchors.size) anchors[i + 1].range.first else Int.MAX_VALUE
        val snip = snippets.firstOrNull { it.range.first > m.range.first && it.range.first < nextStart }
        val snippet = if (snip == null) "" else Html.decodeEntities(Regex("<[^>]+>").replace(snip.groupValues[1], "")).replace(Regex("\\s+"), " ").trim()
        hits.add(SearchHit(title, href, snippet))
    }
    return hits
}

private fun formatHits(query: String, hits: List<SearchHit>): String {
    if (hits.isEmpty()) return "No results for '$query'."
    val sb = StringBuilder("Search results for '$query' (untrusted web content):\n")
    for ((i, h) in hits.withIndex()) {
        sb.append("${i + 1}. ${h.title}\n   ${h.url}\n")
        if (h.snippet.isNotEmpty()) sb.append("   ${h.snippet.take(300)}\n")
    }
    return sb.toString().trim()
}

private fun searchWeb(query: String, max: Int, ctx: ToolContext): String {
    val searx = ctx.settings.searxngUrl.trim().trimEnd('/')
    if (searx.isNotEmpty()) {
        val r = Http.request("$searx/search?q=${Http.urlEncode(query)}&format=json", canceller = ctx.canceller, readTimeoutMs = 25000)
        if (r.code != 200) throw ToolError("SearXNG returned HTTP ${r.code}. Check the instance URL (JSON output must be enabled).")
        val arr = JSONObject(r.text).arr("results") ?: return "No results for '$query'."
        val hits = arr.objects().take(max).map { SearchHit(it.str("title"), it.str("url"), it.str("content")) }
        return formatHits(query, hits)
    }
    val body = "q=${Http.urlEncode(query)}&kl=wt-wt".toByteArray(Charsets.UTF_8)
    val r = Http.request(
        Endpoints.duckDuckGo, "POST",
        mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept-Language" to "en-US,en;q=0.9"),
        body, canceller = ctx.canceller, readTimeoutMs = 25000
    )
    if (r.code != 200 && r.code != 202) throw ToolError("Search engine returned HTTP ${r.code}")
    val hits = parseDuckDuckGo(r.text)
    if (hits.isEmpty()) {
        throw ToolError(
            "No results could be parsed (the search engine may be rate-limiting or showing a captcha). " +
                "Try again later, rephrase, or configure a SearXNG instance in Settings."
        )
    }
    return formatHits(query, hits.take(max))
}

private fun fileNameFromUrl(url: String, contentDisposition: String): String {
    val cd = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(contentDisposition)?.groupValues?.get(1)
    if (!cd.isNullOrBlank()) return try { URLDecoder.decode(cd, "UTF-8") } catch (e: Exception) { cd }.replace(Regex("[/\\\\]"), "_")
    val last = try { URL(url).path.substringAfterLast('/') } catch (e: Exception) { "" }
    val dec = try { URLDecoder.decode(last, "UTF-8") } catch (e: Exception) { last }
    return dec.ifBlank { "download" }.replace(Regex("[/\\\\]"), "_")
}

fun webTools(): List<Tool> = listOf(
    Tool(
        "web_search", "Search the web and return result titles, URLs and snippets. Follow up with web_fetch to read a page.", Risk.READ,
        objectSchema(
            P("query", "string", "Search query", required = true),
            P("max_results", "integer", "Number of results (default 8, max 20).")
        )
    ) { a, ctx ->
        searchWeb(a.req("query"), a.int("max_results", 8).coerceIn(1, 20), ctx)
    },

    Tool(
        "web_fetch", "Download a web page (or text/JSON URL) and return its readable text. Use offset to continue a long page.", Risk.READ,
        objectSchema(
            P("url", "string", "http(s) URL", required = true),
            P("max_chars", "integer", "Maximum characters to return (default 12000)."),
            P("offset", "integer", "Character offset to start from (default 0)."),
            P("include_links", "boolean", "Also list the page's links.")
        )
    ) { a, ctx ->
        val url = a.req("url").let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        val r = Http.request(url, canceller = ctx.canceller, maxBytes = 3 * 1024 * 1024, readTimeoutMs = 30000)
        val ctype = r.header("content-type").lowercase()
        if (r.code >= 400) throw ToolError("HTTP ${r.code} fetching $url")
        if (ctype.startsWith("image/") || ctype.startsWith("video/") || ctype.startsWith("audio/") ||
            ctype.contains("pdf") || ctype.contains("zip") || ctype.contains("octet-stream")
        ) {
            return@Tool "This URL is a binary file ($ctype, ${humanSize(r.body.size.toLong())}+). Use download_file to save it."
        }
        val raw = r.text
        val isHtml = ctype.contains("html") || raw.trimStart().startsWith("<")
        val text = if (isHtml) Html.toText(raw) else raw
        val offset = a.int("offset", 0).coerceAtLeast(0)
        val max = a.int("max_chars", 12000).coerceIn(500, 100000)
        val slice = if (offset >= text.length) "" else text.substring(offset, minOf(text.length, offset + max))
        val sb = StringBuilder()
        sb.append("URL: ").append(r.finalUrl).append('\n')
        if (isHtml) Html.title(raw).takeIf { it.isNotEmpty() }?.let { sb.append("Title: ").append(it).append('\n') }
        sb.append("<untrusted_web_content>\n").append(slice).append("\n</untrusted_web_content>")
        if (offset + max < text.length) sb.append("\n[more content: call again with offset=${offset + max}; total ${text.length} chars]")
        if (a.bool("include_links") && isHtml) {
            sb.append("\nLinks:\n")
            for ((t, u) in Html.links(raw, r.finalUrl, 40)) sb.append("- ").append(t).append(" -> ").append(u).append('\n')
        }
        sb.toString()
    },

    Tool(
        "download_file", "Download a URL into the workspace. If path is empty or a folder, the file name is taken from the URL.", Risk.WRITE,
        objectSchema(
            P("url", "string", "http(s) URL", required = true),
            P("path", "string", "Destination file or folder (default: workspace root).")
        )
    ) { a, ctx ->
        val url = a.req("url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw ToolError("Only http(s) URLs are supported")
        val (conn, finalUrl) = Http.open(url, canceller = ctx.canceller, readTimeoutMs = 60000)
        try {
            val code = conn.responseCode
            if (code >= 400) throw ToolError("HTTP $code downloading $url")
            val name = fileNameFromUrl(finalUrl, conn.getHeaderField("Content-Disposition") ?: "")
            val given = a.str("path")
            val target: File = when {
                given.isEmpty() -> File(ctx.workspace, name)
                else -> {
                    val g = ctx.resolve(given)
                    if (g.isDirectory || given.endsWith("/")) File(g, name) else g
                }
            }
            val resolved = ctx.resolve(target.path)
            resolved.parentFile?.mkdirs()
            val limit = 2L * 1024 * 1024 * 1024
            var total = 0L
            try {
                conn.inputStream.use { input ->
                    FileOutputStream(resolved).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            ctx.canceller.check()
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > limit) throw ToolError("Download exceeds 2 GB; aborted")
                            out.write(buf, 0, n)
                        }
                    }
                }
            } catch (e: Exception) {
                resolved.delete()
                if (ctx.canceller.cancelled) throw CancelledException()
                throw e
            }
            "Downloaded ${humanSize(total)} to ${ctx.rel(resolved)}"
        } finally {
            conn.disconnect()
            ctx.canceller.attach(null)
        }
    },

    Tool(
        "http_request", "Send an arbitrary HTTP request (GET, POST, PUT, DELETE, HEAD) and return status, headers and body.", Risk.WRITE,
        objectSchema(
            P("method", "string", "HTTP method", required = true, enum = listOf("GET", "POST", "PUT", "DELETE", "HEAD")),
            P("url", "string", "http(s) URL", required = true),
            P("headers", "object", "Header name/value pairs"),
            P("body", "string", "Request body (text/JSON)")
        )
    ) { a, ctx ->
        val method = a.req("method").uppercase()
        val url = a.req("url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw ToolError("Only http(s) URLs are supported")
        val hdrs = HashMap<String, String>()
        a.obj("headers")?.let { h -> for (k in h.keyList()) hdrs[k] = h.str(k) }
        val body = a.str("body")
        if (body.isNotEmpty() && !hdrs.keys.any { it.equals("Content-Type", true) }) hdrs["Content-Type"] = "application/json"
        val r = Http.request(url, method, hdrs, if (body.isEmpty()) null else body.toByteArray(Charsets.UTF_8), canceller = ctx.canceller, readTimeoutMs = 45000)
        val sb = StringBuilder("HTTP ${r.code}\n")
        for (k in listOf("content-type", "content-length", "location", "set-cookie")) {
            val v = r.header(k)
            if (v.isNotEmpty()) sb.append("$k: $v\n")
        }
        sb.append("\n<untrusted_web_content>\n").append(r.text).append("\n</untrusted_web_content>")
        sb.toString()
    }
)
