package com.pixelagent.app.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiMetadataTest {
    @Test
    fun toolCallMetadataSurvivesConversationRestoration() {
        val metadata = JSONObject()
            .put("extra_content", JSONObject().put("google", JSONObject().put("thought_signature", "opaque-signature")))
        val original = Msg(
            role = Role.ASSISTANT,
            toolCalls = listOf(
                ToolCall("call-1", "shell", JSONObject().put("command", "pwd"), metadata),
                ToolCall("call-2", "screen_read", JSONObject(), JSONObject().put("vendor_extension", "opaque-value"))
            )
        )

        val restored = Msg.fromJson(original.toJson())

        assertEquals(2, restored.toolCalls.size)
        assertEquals("opaque-signature", restored.toolCalls[0].providerMetadata
            .getJSONObject("extra_content").getJSONObject("google").getString("thought_signature"))
        assertEquals("opaque-value", restored.toolCalls[1].providerMetadata.getString("vendor_extension"))
    }

    @Test
    fun interruptedToolCallIsNotTreatedAsCompletedOrSafeToRepeat() {
        val call = ToolCall("call-1", "shell", JSONObject().put("command", "touch marker"), executionState = ToolExecutionState.EXECUTING)

        val repaired = Sanitizer.repair(listOf(Msg(Role.ASSISTANT, toolCalls = listOf(call))))

        assertEquals(ToolExecutionState.INTERRUPTED, repaired.first().toolCalls.first().executionState)
        assertEquals(Role.TOOL, repaired.last().role)
        assertTrue(repaired.last().text.contains("may already have taken effect"))
    }

    @Test
    fun chatStoreRestoresProviderMetadataExecutionStateAndLearningExclusion() {
        val directory = java.nio.file.Files.createTempDirectory("pixelagent-chats").toFile()
        try {
            val store = ChatStore(directory)
            val call = ToolCall(
                "call-1", "shell", JSONObject().put("command", "pwd"),
                JSONObject().put("extra_content", JSONObject().put("google", JSONObject().put("thought_signature", "opaque"))),
                ToolExecutionState.SUCCEEDED
            )
            store.save("chat-1", "title", listOf(Msg(Role.ASSISTANT, toolCalls = listOf(call))), excludedFromLearning = true)

            val restored = ChatStore(directory)

            assertTrue(restored.list().single().excludedFromLearning)
            val restoredCall = restored.load("chat-1").single().toolCalls.single()
            assertEquals(ToolExecutionState.SUCCEEDED, restoredCall.executionState)
            assertEquals("opaque", restoredCall.providerMetadata.getJSONObject("extra_content").getJSONObject("google").getString("thought_signature"))
        } finally {
            directory.deleteRecursively()
        }
    }
}