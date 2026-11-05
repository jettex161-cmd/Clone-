package com.pixelagent.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentTextExtractorTest {
    @Test
    fun extractsPlainTextAndStripsHtmlMarkup() {
        val markdown = File.createTempFile("pixelagent-doc", ".md")
        val html = File.createTempFile("pixelagent-doc", ".html")
        try {
            markdown.writeText("# Project\nBuild with Kotlin", Charsets.UTF_8)
            html.writeText("<html><title>Title</title><body>Hello <b>world</b></body></html>", Charsets.UTF_8)

            assertEquals("# Project\nBuild with Kotlin", DocumentTextExtractor.extract(markdown, Canceller()))
            assertTrue(DocumentTextExtractor.extract(html, Canceller()).contains("Hello world"))
        } finally {
            markdown.delete()
            html.delete()
        }
    }

    @Test
    fun extractsOfficeXmlTextIncrementally() {
        val docx = File.createTempFile("pixelagent-doc", ".docx")
        val xlsx = File.createTempFile("pixelagent-doc", ".xlsx")
        try {
            writeZip(docx, mapOf("word/document.xml" to "<w:document><w:p><w:r><w:t>Roadmap &amp; tasks</w:t></w:r></w:p></w:document>"))
            writeZip(xlsx, mapOf("xl/sharedStrings.xml" to "<sst><si><t>Quarterly plan</t></si></sst>"))

            assertTrue(DocumentTextExtractor.extract(docx, Canceller()).contains("Roadmap & tasks"))
            assertTrue(DocumentTextExtractor.extract(xlsx, Canceller()).contains("Quarterly plan"))
        } finally {
            docx.delete()
            xlsx.delete()
        }
    }

    @Test
    fun unsupportedFormatsAndCancelledReadsAreExplicit() {
        val unsupported = File.createTempFile("pixelagent-doc", ".bin")
        val text = File.createTempFile("pixelagent-doc", ".txt")
        try {
            unsupported.writeBytes(byteArrayOf(1, 2, 3))
            text.writeText("some text")
            assertThrows(IllegalArgumentException::class.java) { DocumentTextExtractor.extract(unsupported, Canceller()) }
            val cancelled = Canceller().also { it.cancel() }
            assertThrows(CancelledException::class.java) { DocumentTextExtractor.extract(text, cancelled) }
        } finally {
            unsupported.delete()
            text.delete()
        }
    }

    private fun writeZip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, value) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
