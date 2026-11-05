package com.pixelagent.app.core

import java.io.File
import java.util.zip.ZipFile

object DocumentTextExtractor {
    private const val MAX_TEXT_CHARS = 2 * 1024 * 1024
    private const val MAX_ENTRY_CHARS = 8 * 1024 * 1024

    fun extract(file: File, canceller: Canceller): String {
        require(file.isFile && file.canRead()) { "The selected document is not readable." }
        require(file.length() <= 100L * 1024 * 1024) { "Document exceeds the 100 MB indexing limit; the original was left unchanged." }
        return when (file.extension.lowercase()) {
            "txt", "md", "kt", "java", "py", "js", "ts", "c", "cpp", "h", "rs", "go", "json", "xml", "html", "htm", "css", "sh", "yml", "yaml", "csv", "log" ->
                readText(file, canceller).let { if (file.extension.lowercase() in setOf("html", "htm")) Html.toText(it) else it }
            "rtf" -> readText(file, canceller).replace(Regex("\\\\[a-zA-Z]+-?\\d* ?|[{}]"), " ")
            "docx", "pptx", "xlsx" -> readOfficeXml(file, canceller)
            else -> throw IllegalArgumentException("No portable text extractor is available for .${file.extension.ifEmpty { "(no extension)" }}.")
        }.trim()
    }

    private fun readText(file: File, canceller: Canceller): String {
        val out = StringBuilder()
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(8192)
            while (out.length < MAX_TEXT_CHARS) {
                canceller.check()
                val size = reader.read(buffer, 0, minOf(buffer.size, MAX_TEXT_CHARS - out.length))
                if (size < 0) break
                out.append(buffer, 0, size)
            }
        }
        return out.toString()
    }

    private fun readOfficeXml(file: File, canceller: Canceller): String {
        val fragments = ArrayList<String>()
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().filter { entry ->
                !entry.isDirectory && when (file.extension.lowercase()) {
                    "docx" -> entry.name == "word/document.xml"
                    "pptx" -> entry.name.startsWith("ppt/slides/slide") && entry.name.endsWith(".xml")
                    else -> entry.name == "xl/sharedStrings.xml" || entry.name.startsWith("xl/worksheets/sheet") && entry.name.endsWith(".xml")
                }
            }.take(100)
            for (entry in entries) {
                canceller.check()
                zip.getInputStream(entry).use { input ->
                    val xml = input.bufferedReader(Charsets.UTF_8).readText().take(MAX_ENTRY_CHARS)
                    val textValues = Regex("<(?:[A-Za-z0-9_]+:)?t(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?t>", setOf(RegexOption.DOT_MATCHES_ALL))
                        .findAll(xml).map { Html.decodeEntities(it.groupValues[1]) }
                    val values = if (file.extension.lowercase() == "xlsx" && !entry.name.contains("sharedStrings")) {
                        textValues + Regex("<(?:[A-Za-z0-9_]+:)?v>(.*?)</(?:[A-Za-z0-9_]+:)?v>", RegexOption.DOT_MATCHES_ALL)
                            .findAll(xml).map { Html.decodeEntities(it.groupValues[1]) }
                    } else textValues
                    fragments.add(values.joinToString(" "))
                }
            }
        }
        return fragments.joinToString("\n")
    }
}
