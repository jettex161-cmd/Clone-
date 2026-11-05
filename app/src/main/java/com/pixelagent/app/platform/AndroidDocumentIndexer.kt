package com.pixelagent.app.platform

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pixelagent.app.core.Canceller
import com.pixelagent.app.core.DocumentIndexResult
import com.pixelagent.app.core.DocumentIndexer
import com.pixelagent.app.core.DocumentTextExtractor
import com.pixelagent.app.core.MemoryStore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.min

class AndroidDocumentIndexer(context: Context, private val memory: MemoryStore) : DocumentIndexer {
    private val appContext = context.applicationContext

    override fun index(file: File, projectId: String, canceller: Canceller): DocumentIndexResult {
        if (!file.isFile || !file.canRead()) throw IllegalArgumentException("The selected document is not readable.")
        if (file.length() > MAX_FILE_BYTES) throw IllegalArgumentException("Document exceeds the 100 MB indexing limit; the original was left unchanged.")
        val digest = sha256(file, canceller)
        val text = when (file.extension.lowercase()) {
            "pdf" -> readPdf(file, canceller).ifBlank { readPdfWithOcr(file, canceller) }
            "jpg", "jpeg", "png", "webp", "bmp" -> readImageText(file, canceller)
            else -> try {
                DocumentTextExtractor.extract(file, canceller)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("${e.message} Nothing was indexed.")
            }
        }.trim()
        if (text.isBlank()) throw IllegalArgumentException("No readable text was extracted from ${file.name}; nothing was indexed.")
        val chunks = chunk(text.take(MAX_EXTRACTED_CHARS))
        val count = memory.replaceDocument(file.canonicalPath, projectId, digest, chunks)
        return DocumentIndexResult(file.canonicalPath, count, duplicate = count == 0)
    }

    private fun readPdf(file: File, canceller: Canceller): String {
        PDFBoxResourceLoader.init(appContext)
        PDDocument.load(file).use { document ->
            val stripper = PDFTextStripper()
            val out = StringBuilder()
            for (page in 1..document.numberOfPages) {
                canceller.check()
                stripper.startPage = page
                stripper.endPage = page
                val pageText = stripper.getText(document)
                if (out.length + pageText.length > MAX_EXTRACTED_CHARS) {
                    out.append(pageText.take(MAX_EXTRACTED_CHARS - out.length))
                    break
                }
                out.append(pageText).append('\n')
            }
            return out.toString()
        }
    }

    private fun readImageText(file: File, canceller: Canceller): String {
        canceller.check()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("Image could not be decoded; nothing was indexed.")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_OCR_DIMENSION) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("Image could not be decoded; nothing was indexed.")
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            Tasks.await(recognizer.process(image), 45, TimeUnit.SECONDS).text
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    private fun readPdfWithOcr(file: File, canceller: Canceller): String {
        val out = StringBuilder()
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(descriptor).use { renderer ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                for (pageIndex in 0 until min(renderer.pageCount, MAX_OCR_PDF_PAGES)) {
                    canceller.check()
                    renderer.openPage(pageIndex).use { page ->
                        val scale = min(1f, MAX_OCR_DIMENSION.toFloat() / maxOf(page.width, page.height))
                        val bitmap = Bitmap.createBitmap(
                            (page.width * scale).toInt().coerceAtLeast(1),
                            (page.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        )
                        try {
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            out.append(Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 45, TimeUnit.SECONDS).text).append('\n')
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    if (out.length >= MAX_EXTRACTED_CHARS) break
                }
            } finally {
                recognizer.close()
            }
        }
        return out.toString()
    }

    private fun chunk(text: String): List<String> {
        val chunks = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + CHUNK_CHARS, text.length)
            if (end < text.length) {
                val boundary = text.lastIndexOf('\n', end).takeIf { it > start + CHUNK_CHARS / 2 }
                if (boundary != null) end = boundary
            }
            val part = text.substring(start, end).trim()
            if (part.isNotEmpty()) chunks.add(part)
            start = if (end >= text.length) end else maxOf(end - CHUNK_OVERLAP, start + 1)
        }
        return chunks
    }

    private fun sha256(file: File, canceller: Canceller): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                canceller.check()
                val size = input.read(buffer)
                if (size < 0) break
                md.update(buffer, 0, size)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_FILE_BYTES = 100L * 1024 * 1024
        private const val MAX_EXTRACTED_CHARS = 2 * 1024 * 1024
        private const val CHUNK_CHARS = 1800
        private const val CHUNK_OVERLAP = 160
        private const val MAX_OCR_DIMENSION = 2048
        private const val MAX_OCR_PDF_PAGES = 30
    }
}
