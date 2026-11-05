package com.pixelagent.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

fun deviceTools(): List<Tool> = listOf(
    Tool(
        "current_time", "Get the current local date, time and timezone.", Risk.READ,
        objectSchema()
    ) { _, _ ->
        val now = Date()
        val tz = TimeZone.getDefault()
        val fmt = SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm:ss Z", Locale.US)
        fmt.timeZone = tz
        "${fmt.format(now)} (${tz.id}), unix=${now.time / 1000}"
    },

    Tool(
        "device_info", "Get phone details: model, Android version, battery, storage, network and which permissions/features are enabled.", Risk.READ,
        objectSchema()
    ) { _, ctx -> ctx.platform.deviceInfo() },

    Tool(
        "list_apps", "List installed apps that can be launched, optionally filtered by name.", Risk.READ,
        objectSchema(P("query", "string", "Part of the app name to filter by"))
    ) { a, ctx -> ctx.platform.listApps(a.str("query")) },

    Tool(
        "open_app", "Launch an installed app by name (e.g. 'YouTube') or package id.", Risk.WRITE,
        objectSchema(P("app", "string", "App name or package id", required = true))
    ) { a, ctx -> ctx.platform.openApp(a.req("app")) },

    Tool(
        "open_url", "Open a link in the phone's apps/browser. Supports https:, geo:, tel: (opens the dialer), mailto: and other VIEW intents.", Risk.WRITE,
        objectSchema(P("url", "string", "URL or URI to open", required = true))
    ) { a, ctx -> ctx.platform.openUrl(a.req("url")) },

    Tool(
        "set_alarm", "Create an alarm in the clock app (24-hour time).", Risk.WRITE,
        objectSchema(
            P("hour", "integer", "Hour 0-23", required = true),
            P("minute", "integer", "Minute 0-59", required = true),
            P("label", "string", "Alarm label")
        )
    ) { a, ctx ->
        val h = a.int("hour", -1)
        val m = a.int("minute", -1)
        if (h !in 0..23 || m !in 0..59) throw ToolError("hour must be 0-23 and minute 0-59")
        ctx.platform.setAlarm(h, m, a.str("label"))
    },

    Tool(
        "set_timer", "Start a countdown timer in the clock app.", Risk.WRITE,
        objectSchema(
            P("seconds", "integer", "Duration in seconds", required = true),
            P("label", "string", "Timer label")
        )
    ) { a, ctx ->
        val s = a.int("seconds", 0)
        if (s <= 0 || s > 86400) throw ToolError("seconds must be between 1 and 86400")
        ctx.platform.setTimer(s, a.str("label"))
    },

    Tool(
        "clipboard", "Read or write the phone clipboard.", Risk.WRITE,
        objectSchema(
            P("action", "string", "get or set", required = true, enum = listOf("get", "set")),
            P("text", "string", "Text to copy (for set)")
        )
    ) { a, ctx ->
        when (a.req("action").lowercase()) {
            "get" -> ctx.platform.clipboardGet()
            "set" -> ctx.platform.clipboardSet(a.str("text"))
            else -> throw ToolError("action must be 'get' or 'set'")
        }
    },

    Tool(
        "notify", "Show a notification on the phone.", Risk.WRITE,
        objectSchema(
            P("title", "string", "Notification title", required = true),
            P("text", "string", "Notification text", required = true)
        )
    ) { a, ctx -> ctx.platform.notify(a.req("title"), a.req("text")) },

    Tool(
        "wait", "Pause for a few seconds (e.g. to let an app finish loading before reading the screen).", Risk.READ,
        objectSchema(P("seconds", "number", "Seconds to wait (max 15)", required = true))
    ) { a, ctx ->
        val s = a.dbl("seconds", 1.0).coerceIn(0.1, 15.0)
        var left = (s * 1000).toLong()
        while (left > 0) {
            ctx.canceller.check()
            val step = minOf(left, 200L)
            Thread.sleep(step)
            left -= step
        }
        "Waited ${"%.1f".format(Locale.US, s)} s"
    }
)

private fun screen(ctx: ToolContext): ScreenController =
    ctx.platform.screen() ?: throw ToolError(
        "Phone control is not enabled. Ask the user to turn on 'Pixel Agent' in Android Settings → Accessibility → Installed apps."
    )

fun screenTools(): List<Tool> = listOf(
    Tool(
        "screen_read",
        "Read what is currently on the phone screen as a list of elements with ids, labels and coordinates. Call this before screen_click/screen_type and again after each action to verify the result.",
        Risk.READ, objectSchema(), group = "screen"
    ) { _, ctx -> screen(ctx).read() },

    Tool(
        "screen_click", "Tap a UI element by its id (from screen_read) or by visible text.", Risk.DANGEROUS,
        objectSchema(
            P("id", "integer", "Element id from the latest screen_read"),
            P("text", "string", "Visible text/description to click if no id is given")
        ),
        group = "screen"
    ) { a, ctx ->
        val sc = screen(ctx)
        if (a.has("id") && !a.isNull("id")) sc.clickById(a.int("id", -1))
        else if (a.str("text").isNotEmpty()) sc.clickByText(a.str("text"))
        else throw ToolError("Provide 'id' or 'text'")
    },

    Tool(
        "screen_tap_xy", "Tap at exact screen coordinates (pixels).", Risk.DANGEROUS,
        objectSchema(
            P("x", "integer", "X in pixels", required = true),
            P("y", "integer", "Y in pixels", required = true)
        ),
        group = "screen"
    ) { a, ctx -> screen(ctx).tap(a.int("x", -1), a.int("y", -1)) },

    Tool(
        "screen_swipe", "Swipe from one point to another (pixels).", Risk.DANGEROUS,
        objectSchema(
            P("x1", "integer", "Start X", required = true),
            P("y1", "integer", "Start Y", required = true),
            P("x2", "integer", "End X", required = true),
            P("y2", "integer", "End Y", required = true),
            P("duration_ms", "integer", "Swipe duration in ms (default 300)")
        ),
        group = "screen"
    ) { a, ctx ->
        screen(ctx).swipe(a.int("x1"), a.int("y1"), a.int("x2"), a.int("y2"), a.int("duration_ms", 300).coerceIn(50, 5000))
    },

    Tool(
        "screen_scroll", "Scroll the current screen content.", Risk.DANGEROUS,
        objectSchema(P("direction", "string", "down (see more below) or up", required = true, enum = listOf("down", "up"))),
        group = "screen"
    ) { a, ctx -> screen(ctx).scroll(a.req("direction").lowercase()) },

    Tool(
        "screen_type", "Set the text of an input field (by id from screen_read, or the focused field if no id).", Risk.DANGEROUS,
        objectSchema(
            P("text", "string", "Text to enter (replaces the field's content)", required = true),
            P("id", "integer", "Element id of the input field")
        ),
        group = "screen"
    ) { a, ctx ->
        val id = if (a.has("id") && !a.isNull("id")) a.int("id", -1) else null
        screen(ctx).typeText(a.str("text"), id)
    },

    Tool(
        "press_key", "Press a system key.", Risk.DANGEROUS,
        objectSchema(
            P(
                "key", "string", "Which key", required = true,
                enum = listOf("back", "home", "recents", "notifications", "quick_settings", "enter", "lock_screen", "screenshot")
            )
        ),
        group = "screen"
    ) { a, ctx -> screen(ctx).pressKey(a.req("key").lowercase()) }
)

fun shellTool(): Tool = Tool(
    "shell",
    "Run a shell command on the phone (Android's sh, app permissions only) in the workspace folder. Output is truncated.",
    Risk.DANGEROUS,
    objectSchema(
        P("command", "string", "Command line to run", required = true),
        P("timeout_sec", "integer", "Timeout in seconds (default 30, max 300)")
    ),
    group = "shell"
) { a, ctx ->
    val cmd = a.req("command")
    val timeout = a.int("timeout_sec", 30).coerceIn(1, 300).toLong()
    val pb = ProcessBuilder("sh", "-c", cmd)
    pb.directory(ctx.workspace)
    pb.redirectErrorStream(true)
    val proc = pb.start()
    ctx.canceller.attachProcess(proc)
    val out = StringBuilder()
    val reader = Thread {
        try {
            proc.inputStream.bufferedReader().use { r ->
                val buf = CharArray(4096)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    synchronized(out) { if (out.length < 200_000) out.append(buf, 0, n) }
                }
            }
        } catch (_: Exception) {
        }
    }
    reader.isDaemon = true
    reader.start()
    val finished = try {
        proc.waitFor(timeout, TimeUnit.SECONDS)
    } catch (e: InterruptedException) {
        proc.destroyForcibly()
        throw CancelledException()
    }
    if (!finished) proc.destroyForcibly()
    reader.join(1500)
    ctx.canceller.attachProcess(null)
    ctx.canceller.check()
    val text = synchronized(out) { out.toString() }
    val status = if (finished) "exit code ${proc.exitValue()}" else "TIMED OUT after ${timeout}s (killed)"
    "[$status]\n" + text.ifEmpty { "(no output)" }
}

fun memoryTools(): List<Tool> = listOf(
    Tool(
        "memory_save", "Remember a fact about the user or their preferences permanently (shown to you in every future chat).", Risk.WRITE,
        objectSchema(P("text", "string", "A short, self-contained fact to remember", required = true)), group = "memory"
    ) { a, ctx ->
        val e = ctx.memory.add(a.req("text"), ctx.settings.projectId)
            ?: throw ToolError("This memory was not saved because it was empty or may contain a secret.")
        "Saved memory ${e.id}"
    },

    Tool(
        "memory_list", "List everything you have remembered.", Risk.READ,
        objectSchema(), group = "memory"
    ) { _, ctx ->
        val all = ctx.memory.all().filter { it.projectId == "general" || it.projectId == ctx.settings.projectId }
        if (all.isEmpty()) "No memories saved." else all.joinToString("\n") { "[${it.id}] ${it.text}" }
    },

    Tool(
        "memory_search", "Search saved general and current-project knowledge for relevant facts or document excerpts.", Risk.READ,
        objectSchema(P("query", "string", "Keywords to search for", required = true)), group = "memory"
    ) { a, ctx ->
        val found = ctx.memory.search(a.req("query"), ctx.settings.projectId, 8)
        if (found.isEmpty()) "No relevant saved knowledge found." else found.joinToString("\n") { "[${it.id}] ${it.text}" }
    },

    Tool(
        "memory_update", "Correct or replace a saved fact by its id.", Risk.WRITE,
        objectSchema(
            P("id", "string", "Memory id from memory_list or memory_search", required = true),
            P("text", "string", "Corrected fact", required = true)
        ), group = "memory"
    ) { a, ctx ->
        if (ctx.memory.update(a.req("id"), a.req("text"), ctx.settings.projectId)) "Updated memory ${a.str("id")}" else throw ToolError("Memory could not be updated; it may be missing, outside this project, empty, or contain a secret.")
    },

    Tool(
        "memory_delete", "Forget a saved memory by its id.", Risk.WRITE,
        objectSchema(P("id", "string", "Memory id from memory_list", required = true)), group = "memory"
    ) { a, ctx ->
        if (ctx.memory.delete(a.req("id"), ctx.settings.projectId)) "Deleted memory ${a.str("id")}" else throw ToolError("No memory with id ${a.str("id")} in this project")
    }
)
