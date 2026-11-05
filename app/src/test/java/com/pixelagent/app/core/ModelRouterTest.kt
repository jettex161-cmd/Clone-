package com.pixelagent.app.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class ModelRouterTest {
    @Test
    fun localFirstDoesNotUseCloudWithoutConsent() {
        val localFile = File.createTempFile("pixelagent-model", ".litertlm")
        try {
            val local = ProviderProfile(name = "Gemma local", type = ProviderType.LOCAL, model = localFile.path, toolMode = ToolMode.TEXT, vision = false)
            val cloud = ProviderProfile(name = "Cloud", baseUrl = "https://example.invalid/v1", model = "gpt-4o", vision = true)
            val route = ModelRouter.route(cloud, listOf(local, cloud), AgentSettings(modelRoutingMode = ModelRoutingMode.LOCAL_FIRST), Msg(Role.USER, "analyze this"))

            assertEquals(listOf(local.id), route.candidates.map { it.id })
        } finally {
            localFile.delete()
        }
    }

    @Test
    fun imageRequestSelectsConfiguredVisionProfileWhenCloudIsAllowed() {
        val localFile = File.createTempFile("pixelagent-model", ".litertlm")
        try {
            val local = ProviderProfile(name = "Gemma local", type = ProviderType.LOCAL, model = localFile.path, toolMode = ToolMode.TEXT, vision = false)
            val vision = ProviderProfile(name = "Gemini vision", baseUrl = "https://example.invalid/v1", model = "gemini-2.0-flash", vision = true, visionVerified = true)
            val route = ModelRouter.route(local, listOf(local, vision), AgentSettings(modelRoutingMode = ModelRoutingMode.LOCAL_FIRST, allowCloudFallback = true), Msg(Role.USER, "What is shown?", images = listOf("image.jpg")))

            assertEquals(vision.id, route.candidates.first().id)
            assertTrue(route.candidates.all { it.vision })
        } finally {
            localFile.delete()
        }
    }

    @Test
    fun unavailableVisionCapabilityReturnsNoCandidates() {
        val profile = ProviderProfile(name = "Text only", baseUrl = "https://example.invalid/v1", model = "model", vision = false)
        val route = ModelRouter.route(profile, listOf(profile), AgentSettings(), Msg(Role.USER, "Describe", images = listOf("image.jpg")))

        assertTrue(route.candidates.isEmpty())
        assertTrue(route.explanation.contains("image was not sent"))
    }

    @Test
    fun manualVisionFallbackRequiresCloudConsent() {
        val localFile = File.createTempFile("pixelagent-model", ".litertlm")
        try {
            val local = ProviderProfile(name = "Gemma local", type = ProviderType.LOCAL, model = localFile.path, toolMode = ToolMode.TEXT, vision = false)
            val vision = ProviderProfile(name = "Vision model", baseUrl = "https://example.invalid/v1", model = "vision-model", vision = true, visionVerified = true)
            val request = Msg(Role.USER, "Describe", images = listOf("photo.jpg"))
            val blocked = ModelRouter.route(local, listOf(local, vision), AgentSettings(), request)
            val allowed = ModelRouter.route(local, listOf(local, vision), AgentSettings(allowCloudFallback = true), request)

            assertTrue(blocked.candidates.isEmpty())
            assertEquals(vision.id, allowed.candidates.first().id)
        } finally {
            localFile.delete()
        }
    }

    @Test
    fun localFailureSwitchesToCloudWithCompletedToolResultWithoutRepeatingAction() {
        val localFile = File.createTempFile("pixelagent-model", ".litertlm")
        val workspace = createTempDir(prefix = "pixelagent-workspace")
        val memoryFile = File.createTempFile("pixelagent-memory", ".json")
        val actions = AtomicInteger()
        var localTurns = 0
        var cloudRequest: ChatRequest? = null
        try {
            val local = ProviderProfile(name = "Gemma local", type = ProviderType.LOCAL, model = localFile.path, toolMode = ToolMode.TEXT, vision = false)
            val cloud = ProviderProfile(name = "Cloud", baseUrl = "https://example.invalid/v1", model = "gpt-4o", vision = true)
            val action = Tool("record_action", "Record a completed action", Risk.READ, objectSchema()) { _, _ ->
                actions.incrementAndGet()
                "recorded"
            }
            val localClient = object : LlmClient {
                override fun chat(req: ChatRequest, canceller: Canceller, onText: (String) -> Unit): AssistantTurn {
                    localTurns++
                    if (localTurns == 1) return AssistantTurn("<tool_call>{\"name\":\"record_action\",\"arguments\":{}}</tool_call>", emptyList(), "stop")
                    throw IOException("local model failed after tool completion")
                }
                override fun listModels(profile: ProviderProfile, canceller: Canceller) = emptyList<String>()
            }
            val cloudClient = object : LlmClient {
                override fun chat(req: ChatRequest, canceller: Canceller, onText: (String) -> Unit): AssistantTurn {
                    cloudRequest = req
                    return AssistantTurn("Finished", emptyList(), "stop")
                }
                override fun listModels(profile: ProviderProfile, canceller: Canceller) = emptyList<String>()
            }
            val listener = object : AgentListener {
                override fun onMessages(messages: List<Msg>) {}
                override fun onState(state: String, detail: String) {}
                override fun confirm(request: ApprovalRequest) = ApprovalDecision.ALLOW_ONCE
                override fun ask(question: String): String? = null
            }
            val engine = AgentEngine(
                local, AgentSettings(modelRoutingMode = ModelRoutingMode.LOCAL_FIRST, allowCloudFallback = true, maxModelSwitches = 1),
                workspace, TestPlatform, MemoryStore(memoryFile), ToolRegistry(listOf(action)), HashSet(), listener,
                listOf(local, cloud), clientFactory = { if (it.id == local.id) localClient else cloudClient }
            )

            val result = engine.run(listOf(Msg(Role.USER, "Please do this simple task")))

            assertEquals(1, actions.get())
            assertEquals("Finished", result.last { it.role == Role.ASSISTANT }.text)
            assertTrue(cloudRequest!!.messages.any { it.role == Role.TOOL && it.toolName == "record_action" && it.text == "recorded" })
            assertEquals(ToolExecutionState.SUCCEEDED, result.first { it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() }.toolCalls.first().executionState)
        } finally {
            localFile.delete()
            memoryFile.delete()
            workspace.deleteRecursively()
        }
    }

    @Test
    fun cloudFailureFallsBackToLocalForComplexTask() {
        val localFile = File.createTempFile("pixelagent-model", ".litertlm")
        val workspace = java.nio.file.Files.createTempDirectory("pixelagent-workspace").toFile()
        val memoryFile = File.createTempFile("pixelagent-memory", ".json")
        try {
            val local = ProviderProfile(name = "Gemma local", type = ProviderType.LOCAL, model = localFile.path, toolMode = ToolMode.TEXT, vision = false)
            val cloud = ProviderProfile(name = "Reasoning cloud", baseUrl = "https://example.invalid/v1", model = "reasoning-model", vision = true)
            val failingCloud = object : LlmClient {
                override fun chat(req: ChatRequest, canceller: Canceller, onText: (String) -> Unit): AssistantTurn = throw IOException("provider unavailable")
                override fun listModels(profile: ProviderProfile, canceller: Canceller) = emptyList<String>()
            }
            val localClient = object : LlmClient {
                override fun chat(req: ChatRequest, canceller: Canceller, onText: (String) -> Unit) = AssistantTurn("Local answer", emptyList(), "stop")
                override fun listModels(profile: ProviderProfile, canceller: Canceller) = emptyList<String>()
            }
            val listener = object : AgentListener {
                override fun onMessages(messages: List<Msg>) {}
                override fun onState(state: String, detail: String) {}
                override fun confirm(request: ApprovalRequest) = ApprovalDecision.ALLOW_ONCE
                override fun ask(question: String): String? = null
            }
            val engine = AgentEngine(
                local, AgentSettings(modelRoutingMode = ModelRoutingMode.LOCAL_FIRST, allowCloudFallback = true, maxModelSwitches = 1),
                workspace, TestPlatform, MemoryStore(memoryFile), ToolRegistry(emptyList()), HashSet(), listener,
                listOf(local, cloud), clientFactory = { if (it.id == cloud.id) failingCloud else localClient }
            )

            val result = engine.run(listOf(Msg(Role.USER, "Analyze the architecture and explain it")))

            assertEquals("Local answer", result.last { it.role == Role.ASSISTANT }.text)
        } finally {
            localFile.delete()
            memoryFile.delete()
            workspace.deleteRecursively()
        }
    }
}

private object TestPlatform : Platform {
    override fun deviceInfo() = ""
    override fun listApps(query: String) = ""
    override fun openApp(app: String) = ""
    override fun openUrl(url: String) = ""
    override fun setAlarm(hour: Int, minute: Int, label: String) = ""
    override fun setTimer(seconds: Int, label: String) = ""
    override fun clipboardGet() = ""
    override fun clipboardSet(text: String) = ""
    override fun notify(title: String, text: String) = ""
    override fun imageInfo(path: String) = ""
    override fun encodeImage(path: String): EncodedImage? = null
    override fun screen(): ScreenController? = null
}