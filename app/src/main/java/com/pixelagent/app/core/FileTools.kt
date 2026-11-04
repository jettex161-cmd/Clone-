package com.pixelagent.app.core

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLConnection
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private const val MAX_WRITE_BYTES = 8 * 1024 * 1024

private fun isBinary(f: File): Boolean {
    FileInputStream(f).use { input ->
        val buf = ByteArray(4096)
        val n = input.read(buf)
        for (i in 0 until maxOf(n, 0)) if (buf[i].toInt() == 0) return true
    }
    return false
}

private fun uniqueTarget(f: File): File {
    if (!f.exists()) return f
    val name = f.name
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var i = 1
    while (true) {
        val c = File(f.parentFile, "$stem ($i)$ext")
        if (!c.exists()) return c
        i++
    }
}

private fun moveFile(src: File, dst: File) {
    dst.parentFile?.mkdirs()
    if (src.renameTo(dst)) return
    src.copyRecursively(dst, overwrite = true)
    src.deleteRecursively()
}

private fun sha256(f: File, canceller: Canceller): String {
    val md = MessageDigest.getInstance("SHA-256")
    FileInputStream(f).use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            canceller.check()
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

private fun naturalCompare(a: String, b: String): Int {
    val ra = Regex("(\\d+|\\D+)").findAll(a.lowercase()).map { it.value }.toList()
    val rb = Regex("(\\d+|\\D+)").findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(ra.size, rb.size)) {
        val x = ra[i]
        val y = rb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) {
            val lx = x.trimStart('0').length
            val ly = y.trimStart('0').length
            if (lx != ly) lx.compareTo(ly) else x.trimStart('0').compareTo(y.trimStart('0'))
        } else x.compareTo(y)
        if (c != 0) return c
    }
    return ra.size.compareTo(rb.size)
}

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "3gp")
private val AUDIO_EXT = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma")
private val DOC_EXT = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf", "odt", "csv", "epub")
private val ARCHIVE_EXT = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "cbz", "cbr", "cb7")
private val CODE_EXT = setOf("kt", "java", "py", "js", "ts", "c", "cpp", "h", "rs", "go", "json", "xml", "html", "css", "sh", "yml", "yaml")

private fun categoryOf(f: File): String {
    val ext = f.extension.lowercase()
    return when {
        ext in IMAGE_EXT -> "images"
        ext in VIDEO_EXT -> "videos"
        ext in AUDIO_EXT -> "audio"
        ext in DOC_EXT -> "documents"
        ext in ARCHIVE_EXT -> "archives"
        ext in CODE_EXT -> "code"
        else -> "other"
    }
}

fun fileTools(): List<Tool> = listOf(
    Tool(
        "list_files", "List files and folders in a directory (paths are relative to the workspace).", Risk.READ,
        objectSchema(
            P("path", "string", "Directory to list. Default '.' (the workspace root)."),
            P("recursive", "boolean", "Also list sub-folders (max depth 4)."),
            P("max_entries", "integer", "Maximum entries to return (default 200).")
        )
    ) { a, ctx ->
        val dir = ctx.resolve(a.str("path", "."))
        if (!dir.exists()) throw ToolError("Not found: ${ctx.rel(dir)}")
        if (!dir.isDirectory) throw ToolError("Not a directory: ${ctx.rel(dir)}")
        val max = a.int("max_entries", 200).coerceIn(1, 2000)
        val recursive = a.bool("recursive")
        val entries: List<File> = if (recursive) {
            dir.walkTopDown().maxDepth(4).filter { it != dir }.take(max + 1).toList()
        } else {
            (dir.listFiles() ?: emptyArray()).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).take(max + 1)
        }
        if (entries.isEmpty()) return@Tool "${ctx.rel(dir)}: (empty)"
        val sb = StringBuilder("Contents of ${ctx.rel(dir)}:\n")
        for (f in entries.take(max)) {
            val shown = if (recursive) f.relativeTo(dir).path else f.name
            if (f.isDirectory) sb.append("[dir]  ").append(shown).append("/\n")
            else sb.append("[file] ").append(shown).append("  ").append(humanSize(f.length()))
                .append("  ").append(ctx.formatDate(f.lastModified())).append('\n')
        }
        if (entries.size > max) sb.append("… more entries not shown (raise max_entries)\n")
        sb.toString()
    },

    Tool(
        "read_file", "Read a text file. Returns up to max_chars characters starting at offset.", Risk.READ,
        objectSchema(
            P("path", "string", "File path", required = true),
            P("offset", "integer", "Character offset to start at (default 0)."),
            P("max_chars", "integer", "Maximum characters to return (default 20000).")
        )
    ) { a, ctx ->
        val f = ctx.resolve(a.req("path"))
        if (!f.exists()) throw ToolError("File not found: ${ctx.rel(f)}")
        if (f.isDirectory) throw ToolError("${ctx.rel(f)} is a directory; use list_files")
        if (isBinary(f)) {
            val mime = URLConnection.guessContentTypeFromName(f.name) ?: "unknown type"
            return@Tool "${ctx.rel(f)} is a binary file (${humanSize(f.length())}, $mime). Use file_info, extract_archive, or another tool."
        }
        val offset = a.int("offset", 0).coerceAtLeast(0)
        val max = a.int("max_chars", 20000).coerceIn(1, 200000)
        val text = f.bufferedReader(Charsets.UTF_8).use { r ->
            var skipped = 0
            while (skipped < offset) {
                val n = r.skip((offset - skipped).toLong()).toInt()
                if (n <= 0) break
                skipped += n
            }
            val buf = CharArray(max + 1)
            var filled = 0
            while (filled < buf.size) {
                val n = r.read(buf, filled, buf.size - filled)
                if (n < 0) break
                filled += n
            }
            Pair(String(buf, 0, minOf(filled, max)), filled > max)
        }
        val more = if (text.second) "\n…[more content available: call again with offset=${offset + max}]" else ""
        text.first + more
    },

    Tool(
        "write_file", "Create or overwrite a text file (parent folders are created). Set append=true to add to the end.", Risk.WRITE,
        objectSchema(
            P("path", "string", "File path", required = true),
            P("content", "string", "Text to write", required = true),
            P("append", "boolean", "Append instead of overwrite.")
        ),
        summarizer = { a -> "write_file ${a.str("path")} (${a.str("content").length} chars${if (a.bool("append")) ", append" else ""})" }
    ) { a, ctx ->
        val f = ctx.resolve(a.req("path"))
        val content = a.str("content")
        if (content.toByteArray(Charsets.UTF_8).size > MAX_WRITE_BYTES) throw ToolError("Content too large (max 8 MB per write)")
        if (f.isDirectory) throw ToolError("${ctx.rel(f)} is a directory")
        f.parentFile?.mkdirs()
        if (a.bool("append")) f.appendText(content, Charsets.UTF_8) else f.writeText(content, Charsets.UTF_8)
        "Wrote ${content.length} characters to ${ctx.rel(f)}"
    },

    Tool(
        "edit_file", "Replace text in an existing file. old_text must match exactly (and be unique unless replace_all is true).", Risk.WRITE,
        objectSchema(
            P("path", "string", "File path", required = true),
            P("old_text", "string", "Exact text to find", required = true),
            P("new_text", "string", "Replacement text", required = true),
            P("replace_all", "boolean", "Replace every occurrence.")
        )
    ) { a, ctx ->
        val f = ctx.resolve(a.req("path"))
        if (!f.isFile) throw ToolError("File not found: ${ctx.rel(f)}")
        val old = a.str("old_text")
        if (old.isEmpty()) throw ToolError("old_text must not be empty")
        val text = f.readText(Charsets.UTF_8)
        var count = 0
        var idx = text.indexOf(old)
        while (idx >= 0) {
            count++
            idx = text.indexOf(old, idx + old.length)
        }
        if (count == 0) throw ToolError("old_text was not found in ${ctx.rel(f)}")
        if (count > 1 && !a.bool("replace_all")) throw ToolError("old_text occurs $count times; make it unique or set replace_all=true")
        f.writeText(text.replace(old, a.str("new_text")), Charsets.UTF_8)
        "Edited ${ctx.rel(f)} ($count replacement${if (count == 1) "" else "s"})"
    },

    Tool(
        "delete_path", "Permanently delete a file or folder (folders are deleted with everything inside).", Risk.DANGEROUS,
        objectSchema(P("path", "string", "File or folder to delete", required = true))
    ) { a, ctx ->
        val f = ctx.resolve(a.req("path"))
        if (!f.exists()) throw ToolError("Not found: ${ctx.rel(f)}")
        if (ctx.isWorkspaceRoot(f) || f.parentFile == null) throw ToolError("Refusing to delete the workspace root or a filesystem root")
        val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
        if (!ok) throw ToolError("Could not delete ${ctx.rel(f)} (permission denied or in use)")
        "Deleted ${ctx.rel(f)}"
    },

    Tool(
        "make_dir", "Create a folder (and any missing parents).", Risk.WRITE,
        objectSchema(P("path", "string", "Folder path", required = true))
    ) { a, ctx ->
        val f = ctx.resolve(a.req("path"))
        if (f.exists() && !f.isDirectory) throw ToolError("A file with that name already exists")
        if (!f.exists() && !f.mkdirs()) throw ToolError("Could not create folder ${ctx.rel(f)}")
        "Folder ready: ${ctx.rel(f)}"
    },

    Tool(
        "move_path", "Move or rename a file or folder. If dst is an existing folder, src is moved into it.", Risk.WRITE,
        objectSchema(
            P("src", "string", "Source path", required = true),
            P("dst", "string", "Destination path", required = true),
            P("overwrite", "boolean", "Overwrite an existing destination file.")
        )
    ) { a, ctx ->
        val src = ctx.resolve(a.req("src"))
        val dstIn = ctx.resolve(a.req("dst"))
        if (!src.exists()) throw ToolError("Source not found: ${ctx.rel(src)}")
        if (ctx.isWorkspaceRoot(src)) throw ToolError("Refusing to move the workspace root")
        val dst = if (dstIn.isDirectory) File(dstIn, src.name) else dstIn
        if (dst.exists() && !a.bool("overwrite")) throw ToolError("Destination already exists: ${ctx.rel(dst)} (set overwrite=true)")
        if (dst.canonicalPath.startsWith(src.canonicalPath + File.separator)) throw ToolError("Cannot move a folder into itself")
        moveFile(src, dst)
        "Moved ${ctx.rel(src)} -> ${ctx.rel(dst)}"
    },

    Tool(
        "copy_path", "Copy a file or folder. If dst is an existing folder, the copy goes inside it.", Risk.WRITE,
        objectSchema(
            P("src", "string", "Source path", required = true),
            P("dst", "string", "Destination path", required = true),
            P("overwrite", "boolean", "Overwrite existing files.")
        )
    ) { a, ctx ->
        val src = ctx.resolve(a.req("src"))
        val dstIn = ctx.resolve(a.req("dst"))
        if (!src.exists()) throw ToolError("Source not found: ${ctx.rel(src)}")
        val dst = if (dstIn.isDirectory) File(dstIn, src.name) else dstIn
        if (dst.exists() && !a.bool("overwrite")) throw ToolError("Destination already exists: ${ctx.rel(dst)} (set overwrite=true)")
        if (src.isDirectory && dst.canonicalPath.startsWith(src.canonicalPath + File.separator)) throw ToolError("Cannot copy a folder into itself")
        dst.parentFile?.mkdirs()
        val ok = src.copyRecursively(dst, overwrite = a.bool("overwrite"))
        if (!ok) throw ToolError("Copy failed")
        "Copied ${ctx.rel(src)} -> ${ctx.rel(dst)}"
    },

    Tool(
        "search_files", "Find files by name (case-insensitive substring) or, with content=true, search inside text files for a string.", Risk.READ,
        objectSchema(
            P("query", "string", "Text to look for", required = true),
            P("path", "string", "Folder to search in (default '.')."),
            P("content", "boolean", "Search file contents instead of file names."),
            P("max_results", "integer", "Maximum results (default 50).")
        )
    ) { a, ctx ->
        val q = a.req("query")
        val root = ctx.resolve(a.str("path", "."))
        if (!root.isDirectory) throw ToolError("Not a directory: ${ctx.rel(root)}")
        val max = a.int("max_results", 50).coerceIn(1, 500)
        val results = ArrayList<String>()
        val content = a.bool("content")
        for (f in root.walkTopDown()) {
            ctx.canceller.check()
            if (results.size >= max) break
            if (!f.isFile) continue
            if (!content) {
                if (f.name.contains(q, ignoreCase = true)) results.add("${ctx.rel(f)}  ${humanSize(f.length())}")
            } else {
                if (f.length() > 1_500_000 || isBinary(f)) continue
                var lineNo = 0
                try {
                    f.forEachLine(Charsets.UTF_8) { line ->
                        lineNo++
                        if (results.size < max && line.contains(q, ignoreCase = true)) {
                            results.add("${ctx.rel(f)}:$lineNo: ${line.trim().take(200)}")
                        }
                    }
                } catch (e: Exception) {
                    // unreadable file: skip
                }
            }
        }
        if (results.isEmpty()) "No matches for '$q' in ${ctx.rel(root)}"
        else "Found ${results.size} match(es):\n" + results.joinToString("\n")
    },

    Tool(
        "file_info", "Get size, type, dates and (optionally) SHA-256 of a file or folder. For images also returns dimensions.", Risk.READ,
        objectSchema(
            P("path", "string", "File or folder path", required = true),
            P("hash", "boolean", "Also compute the SHA-256 hash (files only).")
        )
    ) { a, ctx ->
        val f = ctx.resolve(a.req("path"))
        if (!f.exists()) throw ToolError("Not found: ${ctx.rel(f)}")
        val sb = StringBuilder()
        sb.append("Path: ").append(f.path).append('\n')
        sb.append("Type: ").append(if (f.isDirectory) "folder" else "file").append('\n')
        sb.append("Modified: ").append(ctx.formatDate(f.lastModified())).append('\n')
        sb.append("Readable: ").append(f.canRead()).append("  Writable: ").append(f.canWrite()).append('\n')
        if (f.isDirectory) {
            val kids = f.listFiles() ?: emptyArray()
            sb.append("Entries: ").append(kids.size).append('\n')
        } else {
            sb.append("Size: ").append(humanSize(f.length())).append(" (").append(f.length()).append(" bytes)\n")
            sb.append("MIME: ").append(URLConnection.guessContentTypeFromName(f.name) ?: "unknown").append('\n')
            if (f.extension.lowercase() in IMAGE_EXT) sb.append("Image: ").append(ctx.platform.imageInfo(f.path)).append('\n')
            if (a.bool("hash")) sb.append("SHA-256: ").append(sha256(f, ctx.canceller)).append('\n')
        }
        sb.toString().trim()
    },

    Tool(
        "organize_files", "Sort the files directly inside a folder into sub-folders by type (images, videos, ...), extension, or month. Use dry_run=true to preview.", Risk.WRITE,
        objectSchema(
            P("path", "string", "Folder to organize (default '.')."),
            P("by", "string", "Grouping rule", enum = listOf("type", "extension", "date")),
            P("dry_run", "boolean", "Only show what would be moved.")
        )
    ) { a, ctx ->
        val dir = ctx.resolve(a.str("path", "."))
        if (!dir.isDirectory) throw ToolError("Not a directory: ${ctx.rel(dir)}")
        val by = a.str("by", "type")
        val dry = a.bool("dry_run")
        val fmt = java.text.SimpleDateFormat("yyyy-MM", Locale.US)
        val lines = ArrayList<String>()
        var moved = 0
        for (f in (dir.listFiles() ?: emptyArray()).sortedBy { it.name.lowercase() }) {
            ctx.canceller.check()
            if (!f.isFile || f.name.startsWith(".")) continue
            val folder = when (by) {
                "extension" -> f.extension.lowercase().ifEmpty { "no_extension" }
                "date" -> fmt.format(java.util.Date(f.lastModified()))
                else -> categoryOf(f)
            }
            val target = uniqueTarget(File(File(dir, folder), f.name))
            if (!dry) moveFile(f, target)
            moved++
            if (lines.size < 100) lines.add("${f.name} -> $folder/${target.name}")
        }
        if (moved == 0) "Nothing to organize in ${ctx.rel(dir)}"
        else (if (dry) "Would move $moved file(s):\n" else "Moved $moved file(s):\n") + lines.joinToString("\n") +
            (if (moved > lines.size) "\n… and ${moved - lines.size} more" else "")
    },

    Tool(
        "find_duplicates", "Find duplicate files (identical content) under a folder using size + SHA-256. Only reports; use delete_path to remove.", Risk.READ,
        objectSchema(
            P("path", "string", "Folder to scan (default '.')."),
            P("min_size", "integer", "Ignore files smaller than this many bytes (default 1).")
        )
    ) { a, ctx ->
        val root = ctx.resolve(a.str("path", "."))
        if (!root.isDirectory) throw ToolError("Not a directory: ${ctx.rel(root)}")
        val minSize = a.long("min_size", 1L).coerceAtLeast(0)
        val bySize = HashMap<Long, MutableList<File>>()
        var scanned = 0
        for (f in root.walkTopDown()) {
            ctx.canceller.check()
            if (!f.isFile || f.length() < minSize) continue
            if (++scanned > 50000) break
            bySize.getOrPut(f.length()) { ArrayList() }.add(f)
        }
        val groups = ArrayList<List<File>>()
        for ((_, list) in bySize) {
            if (list.size < 2) continue
            val byHash = HashMap<String, MutableList<File>>()
            for (f in list) {
                try {
                    byHash.getOrPut(sha256(f, ctx.canceller)) { ArrayList() }.add(f)
                } catch (e: CancelledException) {
                    throw e
                } catch (e: Exception) {
                    // unreadable: skip
                }
            }
            for ((_, g) in byHash) if (g.size > 1) groups.add(g)
        }
        if (groups.isEmpty()) return@Tool "No duplicate files found among $scanned files in ${ctx.rel(root)}."
        var wasted = 0L
        val sb = StringBuilder()
        groups.sortByDescending { it[0].length() * (it.size - 1) }
        for ((i, g) in groups.withIndex()) {
            wasted += g[0].length() * (g.size - 1)
            if (i < 40) {
                sb.append("Group ${i + 1} (${humanSize(g[0].length())} each):\n")
                for (f in g) sb.append("  ").append(ctx.rel(f)).append('\n')
            }
        }
        "Found ${groups.size} duplicate group(s); reclaimable space ${humanSize(wasted)}.\n$sb"
    },

    Tool(
        "create_archive", "Create a zip, cbz (comic book zip, pages sorted naturally) or 7z archive from a file or folder.", Risk.WRITE,
        objectSchema(
            P("source", "string", "File or folder to archive", required = true),
            P("archive_path", "string", "Output archive path. Default: next to the source, named after it."),
            P("format", "string", "Archive format", enum = listOf("zip", "cbz", "7z"))
        )
    ) { a, ctx ->
        val src = ctx.resolve(a.req("source"))
        if (!src.exists()) throw ToolError("Source not found: ${ctx.rel(src)}")
        val fmt = a.str("format", "zip").lowercase()
        if (fmt != "zip" && fmt != "cbz" && fmt != "7z") throw ToolError("Unsupported format '$fmt' (use zip, cbz or 7z)")
        val given = a.str("archive_path")
        var out = if (given.isNotEmpty()) ctx.resolve(given) else ctx.resolve(File(src.parentFile, src.name + "." + fmt).path)
        if (out.extension.lowercase() != fmt) out = File(out.path + "." + fmt)
        if (out.exists()) throw ToolError("Archive already exists: ${ctx.rel(out)}")
        out.parentFile?.mkdirs()
        val files: List<Pair<File, String>> = if (src.isDirectory) {
            src.walkTopDown().filter { it.isFile && it.canonicalPath != out.canonicalPath }
                .map { Pair(it, it.relativeTo(src).path.replace(File.separatorChar, '/')) }
                .sortedWith { x, y -> naturalCompare(x.second, y.second) }.toList()
        } else listOf(Pair(src, src.name))
        if (files.isEmpty()) throw ToolError("Nothing to archive: folder is empty")
        try {
            if (fmt == "7z") {
                val sz = SevenZOutputFile(out)
                try {
                    for ((f, name) in files) {
                        ctx.canceller.check()
                        val entry: SevenZArchiveEntry = sz.createArchiveEntry(f, name)
                        sz.putArchiveEntry(entry)
                        FileInputStream(f).use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                sz.write(buf, 0, n)
                            }
                        }
                        sz.closeArchiveEntry()
                    }
                } finally {
                    sz.close()
                }
            } else {
                ZipOutputStream(FileOutputStream(out)).use { zos ->
                    for ((f, name) in files) {
                        ctx.canceller.check()
                        zos.putNextEntry(ZipEntry(name))
                        FileInputStream(f).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            out.delete()
            throw e
        }
        "Created ${ctx.rel(out)} with ${files.size} file(s), ${humanSize(out.length())}"
    },

    Tool(
        "extract_archive", "Extract a zip/cbz/jar/apk or 7z archive into a folder.", Risk.WRITE,
        objectSchema(
            P("archive", "string", "Archive path", required = true),
            P("dest", "string", "Destination folder. Default: a folder next to the archive named after it.")
        )
    ) { a, ctx ->
        val arc = ctx.resolve(a.req("archive"))
        if (!arc.isFile) throw ToolError("Archive not found: ${ctx.rel(arc)}")
        val dest = ctx.resolve(a.str("dest").ifEmpty { File(arc.parentFile, arc.nameWithoutExtension).path })
        dest.mkdirs()
        val destCanon = dest.canonicalPath
        var count = 0
        var total = 0L
        val limit = 4L * 1024 * 1024 * 1024
        fun target(name: String): File? {
            val t = File(dest, name).canonicalFile
            return if (t.path == destCanon || t.path.startsWith(destCanon + File.separator)) t else null
        }
        when (arc.extension.lowercase()) {
            "zip", "cbz", "jar", "apk" -> {
                ZipFile(arc).use { zf ->
                    for (entry in zf.entries()) {
                        ctx.canceller.check()
                        val t = target(entry.name) ?: continue // skips path-traversal entries
                        if (entry.isDirectory) {
                            t.mkdirs()
                        } else {
                            t.parentFile?.mkdirs()
                            zf.getInputStream(entry).use { input ->
                                FileOutputStream(t).use { fo ->
                                    val buf = ByteArray(64 * 1024)
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        total += n
                                        if (total > limit) throw ToolError("Archive expands beyond 4 GB; aborting")
                                        fo.write(buf, 0, n)
                                    }
                                }
                            }
                            count++
                        }
                    }
                }
            }
            "7z", "cb7" -> {
                val sz = SevenZFile(arc)
                try {
                    while (true) {
                        ctx.canceller.check()
                        val entry = sz.nextEntry ?: break
                        val t = target(entry.name) ?: continue
                        if (entry.isDirectory) {
                            t.mkdirs()
                        } else {
                            t.parentFile?.mkdirs()
                            FileOutputStream(t).use { fo ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = sz.read(buf)
                                    if (n < 0) break
                                    total += n
                                    if (total > limit) throw ToolError("Archive expands beyond 4 GB; aborting")
                                    fo.write(buf, 0, n)
                                }
                            }
                            count++
                        }
                    }
                } finally {
                    sz.close()
                }
            }
            else -> throw ToolError("Unsupported archive type '.${arc.extension}'. Supported: zip, cbz, jar, apk, 7z.")
        }
        "Extracted $count file(s) (${humanSize(total)}) to ${ctx.rel(dest)}"
    }
)
