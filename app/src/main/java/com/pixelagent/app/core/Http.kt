package com.pixelagent.app.core

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

class HttpException(val code: Int, val body: String) : IOException("HTTP $code: ${body.take(800)}")

class CancelledException : RuntimeException("Cancelled")

/** Lets another thread abort whatever network call / process the agent is currently blocked on. */
class Canceller {
    @Volatile
    var cancelled = false

    @Volatile
    private var conn: HttpURLConnection? = null

    @Volatile
    private var process: Process? = null

    fun attach(c: HttpURLConnection?) {
        conn = c
        if (cancelled) Http.disconnectAsync(c)
    }

    fun attachProcess(p: Process?) {
        process = p
        if (cancelled) p?.destroyForcibly()
    }

    fun cancel() {
        cancelled = true
        Http.disconnectAsync(conn)
        try { process?.destroyForcibly() } catch (_: Throwable) {}
    }

    fun check() {
        if (cancelled) throw CancelledException()
    }
}

object Http {
    /** disconnect() can block on some JVMs while another thread is reading, so never call it inline. */
    fun disconnectAsync(c: HttpURLConnection?) {
        if (c == null) return
        val t = Thread { try { c.disconnect() } catch (_: Throwable) {} }
        t.isDaemon = true
        t.start()
    }

    const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 PixelAgent/4.0"

    class Response(val code: Int, val body: ByteArray, val headers: Map<String, String>, val finalUrl: String) {
        val text: String get() = String(body, Charsets.UTF_8)
        fun header(name: String): String = headers[name.lowercase()] ?: ""
    }

    private fun sameHost(a: String, b: String): Boolean = try {
        URL(a).host.equals(URL(b).host, ignoreCase = true)
    } catch (e: Exception) {
        false
    }

    /**
     * Opens a connection, following redirects manually (HttpURLConnection will not follow
     * http -> https), and returns it with the response code already available.
     */
    fun open(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        connectTimeoutMs: Int = 20000,
        readTimeoutMs: Int = 60000,
        canceller: Canceller? = null
    ): Pair<HttpURLConnection, String> {
        var currentUrl = url
        var m = method
        var b = body
        var hdrs = headers
        var redirects = 0
        while (true) {
            canceller?.check()
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.requestMethod = m
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            var hasUa = false
            for ((k, v) in hdrs) {
                if (k.equals("User-Agent", ignoreCase = true)) hasUa = true
                conn.setRequestProperty(k, v)
            }
            if (!hasUa) conn.setRequestProperty("User-Agent", UA)
            canceller?.attach(conn)
            try {
                if (b != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(b) }
                }
                val code = conn.responseCode
                if (code in 301..308 && code != 304 && code != 305 && code != 306) {
                    val loc = conn.getHeaderField("Location")
                    if (loc != null && redirects < 6) {
                        conn.disconnect()
                        redirects++
                        val next = URL(URL(currentUrl), loc).toString()
                        if (!sameHost(currentUrl, next)) {
                            hdrs = hdrs.filterKeys {
                                !it.equals("Authorization", true) && !it.equals("x-api-key", true)
                            }
                        }
                        if (code == 303 || ((code == 301 || code == 302) && m == "POST")) {
                            m = "GET"
                            b = null
                        }
                        currentUrl = next
                        continue
                    }
                }
                return Pair(conn, currentUrl)
            } catch (e: IOException) {
                try { conn.disconnect() } catch (_: Throwable) {}
                if (canceller?.cancelled == true) throw CancelledException()
                throw e
            }
        }
    }

    private fun wrap(conn: HttpURLConnection, stream: InputStream): InputStream {
        val enc = conn.getHeaderField("Content-Encoding")
        return if (enc != null && enc.contains("gzip", ignoreCase = true)) GZIPInputStream(stream) else stream
    }

    private fun readLimited(input: InputStream, maxBytes: Int, canceller: Canceller?): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            canceller?.check()
            val n = input.read(buf)
            if (n < 0) break
            val room = maxBytes - out.size()
            if (n >= room) {
                out.write(buf, 0, room)
                break
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun headerMap(conn: HttpURLConnection): Map<String, String> {
        val m = HashMap<String, String>()
        for ((k, v) in conn.headerFields) {
            if (k != null && v != null && v.isNotEmpty()) m[k.lowercase()] = v.last() ?: ""
        }
        return m
    }

    /** Performs a request and returns the whole (size-limited) response, including 4xx/5xx bodies. */
    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        maxBytes: Int = 4 * 1024 * 1024,
        connectTimeoutMs: Int = 20000,
        readTimeoutMs: Int = 60000,
        canceller: Canceller? = null
    ): Response {
        val (conn, finalUrl) = open(url, method, headers, body, connectTimeoutMs, readTimeoutMs, canceller)
        try {
            val code = conn.responseCode
            val stream = (if (code >= 400) conn.errorStream else conn.inputStream)
            val bytes = if (stream == null) ByteArray(0) else wrap(conn, stream).use { readLimited(it, maxBytes, canceller) }
            return Response(code, bytes, headerMap(conn), finalUrl)
        } catch (e: IOException) {
            if (canceller?.cancelled == true) throw CancelledException()
            throw e
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
            canceller?.attach(null)
        }
    }

    /** Streams a response line by line (Server-Sent Events). Throws [HttpException] for HTTP errors. */
    fun streamLines(
        url: String,
        method: String = "POST",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        canceller: Canceller,
        readTimeoutMs: Int = 300000,
        onLine: (String) -> Boolean
    ) {
        val (conn, _) = open(url, method, headers, body, 30000, readTimeoutMs, canceller)
        try {
            val code = conn.responseCode
            if (code >= 400) {
                val err = conn.errorStream
                val text = if (err == null) "" else String(wrap(conn, err).use { readLimited(it, 64 * 1024, null) }, Charsets.UTF_8)
                throw HttpException(code, text)
            }
            // A blocked socket read cannot be interrupted reliably on every JVM/Android version, so the
            // blocking read happens on a helper thread and this thread polls, staying cancellable.
            val queue = java.util.concurrent.LinkedBlockingQueue<Any>()
            val eof = Any()
            val input = wrap(conn, conn.inputStream)
            val worker = Thread {
                try {
                    val r = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
                    while (true) {
                        val line = r.readLine() ?: break
                        queue.put(line)
                    }
                    queue.put(eof)
                } catch (t: Throwable) {
                    queue.put(t)
                }
            }
            worker.isDaemon = true
            worker.start()
            while (true) {
                if (canceller.cancelled) {
                    disconnectAsync(conn)
                    throw CancelledException()
                }
                val item = queue.poll(150, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                if (item === eof) break
                if (item is Throwable) {
                    if (canceller.cancelled) throw CancelledException()
                    if (item is IOException) throw item
                    throw IOException(item.message ?: item.javaClass.simpleName)
                }
                if (!onLine(item as String)) break
            }
        } catch (e: IOException) {
            if (canceller.cancelled) throw CancelledException()
            throw e
        } finally {
            disconnectAsync(conn)
            canceller.attach(null)
        }
    }

    fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}

/** Minimal multipart/form-data builder. */
class Multipart {
    val boundary = "----PixelAgent" + System.nanoTime()
    private val out = ByteArrayOutputStream()
    val contentType: String get() = "multipart/form-data; boundary=$boundary"

    fun field(name: String, value: String): Multipart {
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray(Charsets.UTF_8))
        return this
    }

    fun file(name: String, filename: String, mime: String, bytes: ByteArray): Multipart {
        out.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n" +
                "Content-Type: $mime\r\n\r\n").toByteArray(Charsets.UTF_8)
        )
        out.write(bytes)
        out.write("\r\n".toByteArray(Charsets.UTF_8))
        return this
    }

    fun finish(): ByteArray {
        out.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }
}
