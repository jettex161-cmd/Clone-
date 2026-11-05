package com.pixelagent.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MemoryStoreTest {
    @Test
    fun factsPersistAndStayWithinTheirProjectCollection() {
        val file = File.createTempFile("pixelagent-memory", ".json")
        try {
            val store = MemoryStore(file)
            val saved = store.add("This project uses Kotlin", "pixel-agent")
            assertNotNull(saved)
            assertNull(store.add("API key: sk-123456789012345678", "pixel-agent"))

            val restored = MemoryStore(file)
            assertEquals(1, restored.search("Kotlin", "pixel-agent").size)
            assertTrue(restored.search("Kotlin", "other-project").isEmpty())
            assertTrue(restored.update(saved!!.id, "This project uses Kotlin and Compose"))
            assertTrue(MemoryStore(file).search("Compose", "pixel-agent").isNotEmpty())
        } finally {
            file.delete()
        }
    }

    @Test
    fun documentChunksDeduplicateUpdateAndDeleteWithoutTouchingSource() {
        val storeFile = File.createTempFile("pixelagent-memory", ".json")
        val source = File.createTempFile("pixelagent-source", ".txt")
        source.writeText("original", Charsets.UTF_8)
        try {
            val store = MemoryStore(storeFile)
            assertEquals(2, store.replaceDocument(source.path, "project-a", "hash-1", listOf("alpha", "beta")))
            assertEquals(0, store.replaceDocument(source.path, "project-a", "hash-1", listOf("alpha", "beta")))
            assertEquals(1, store.replaceDocument(source.path, "project-a", "hash-2", listOf("updated")))
            assertEquals("updated", store.search("updated", "project-a").single().text)
            assertEquals(1, store.removeDocument(source.path, "project-a"))
            assertTrue(source.isFile)
            assertEquals("original", source.readText())
        } finally {
            storeFile.delete()
            source.delete()
        }
    }
}