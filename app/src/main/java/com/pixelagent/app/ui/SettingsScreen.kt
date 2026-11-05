package com.pixelagent.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelagent.app.AgentViewModel
import com.pixelagent.app.core.AgentSettings
import com.pixelagent.app.core.ConfirmMode
import com.pixelagent.app.core.ModelRoutingMode
import com.pixelagent.app.core.MemoryEntry
import com.pixelagent.app.platform.Perms

fun openAllFilesSettings(ctx: Context) {
    try {
        if (Build.VERSION.SDK_INT >= 30) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        } else {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        }
    } catch (e: Exception) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } catch (e2: Exception) {
            // nothing else we can do
        }
    }
}

@Composable
private fun PermRow(title: String, desc: String, granted: Boolean, actionLabel: String, onAction: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Row {
                Text(title, color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.size(8.dp))
                Text(if (granted) "ON" else "off", color = if (granted) Ok else Dim, fontSize = 12.sp)
            }
            Text(desc, color = Dim, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (!granted) {
            OutlinedButton(onClick = onAction) { Text(actionLabel, color = Cyan, fontSize = 12.sp) }
        }
    }
}

@Composable
fun SettingsScreen(vm: AgentViewModel) {
    val ctx = LocalContext.current
    val s = vm.settings
    @Suppress("UNUSED_VARIABLE")
    val tick = vm.permTick  // re-read permission state when returning from system settings
    var newMemory by remember { mutableStateOf("") }
    var memoryQuery by remember { mutableStateOf("") }
    var editingMemory by remember { mutableStateOf<MemoryEntry?>(null) }
    var editingText by remember { mutableStateOf("") }

    fun upd(n: AgentSettings) = vm.updateSettings(n)

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", color = Gold, fontSize = 22.sp)

        SectionTitle("Personality & instructions")
        OutlinedTextField(
            value = s.systemPrompt, onValueChange = { upd(s.copy(systemPrompt = it)) },
            label = { Text("System prompt") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 12
        )
        TextButton(onClick = { upd(s.copy(systemPrompt = AgentSettings.DEFAULT_PERSONA)) }) { Text("Reset to default", color = Cyan, fontSize = 12.sp) }

        SectionTitle("Access (what the agent is allowed to reach)")
        PermRow(
            "All-files access", "Lets the agent use /sdcard/PixelAgent and (if enabled below) any folder.",
            Perms.hasAllFiles(ctx), "Grant"
        ) { openAllFilesSettings(ctx) }
        PermRow(
            "Phone control", "Accessibility service: lets the agent read the screen and tap, type and scroll in other apps. On Android 13+ you may first need App info → ⋮ → Allow restricted settings.",
            Perms.phoneControlEnabled(), "Open"
        ) {
            try { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) { vm.toast = "Could not open Accessibility settings" }
        }
        PermRow(
            "Display over other apps", "Helps the agent bring apps to the front while it works in the background.",
            Perms.canOverlay(ctx), "Grant"
        ) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            } catch (e: Exception) { vm.toast = "Could not open the permission screen" }
        }
        PermRow(
            "Notifications", "Needed for the agent's notify tool and the working indicator.",
            Perms.notificationsGranted(ctx), "Open"
        ) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            } catch (e: Exception) { vm.toast = "Could not open notification settings" }
        }

        SectionTitle("Workspace")
        OutlinedTextField(
            value = s.projectId, onValueChange = { upd(s.copy(projectId = it.trim().take(80).ifEmpty { "general" })) },
            label = { Text("Project knowledge collection") }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = s.workspacePath, onValueChange = { upd(s.copy(workspacePath = it)) },
            label = { Text("Workspace folder (blank = automatic)") }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth()
        )
        Text("Currently: " + s.workspacePath.trim().ifBlank { "(automatic)" }, color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        SwitchRow(
            "Allow access outside the workspace",
            "Lets file tools use any path on the phone (Downloads, DCIM…). Needs all-files access. Off keeps the agent in its sandbox.",
            s.allowOutsideWorkspace
        ) { upd(s.copy(allowOutsideWorkspace = it)) }

        SectionTitle("Safety")
        Text("Ask me before the agent runs…", color = Color.White, fontSize = 13.sp)
        val modes = listOf(
            ConfirmMode.RISKY to "Risky actions only (delete, shell, tapping other apps) — recommended",
            ConfirmMode.WRITES to "Anything that changes something (also writes, downloads, opening apps)",
            ConfirmMode.ALL to "Every tool call",
            ConfirmMode.NEVER to "Nothing — full autonomy (use with care)"
        )
        for ((mode, label) in modes) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.confirmMode == mode, onClick = { upd(s.copy(confirmMode = mode)) })
                Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
        }
        SwitchRow("Shell command tool", "Lets the agent run commands via Android's sh (app permissions only).", s.shellEnabled) { upd(s.copy(shellEnabled = it)) }
        SwitchRow("Phone control tools", "screen_read / click / type / scroll. Also needs the accessibility service above.", s.screenControlEnabled) { upd(s.copy(screenControlEnabled = it)) }

        SectionTitle("Model routing")
        val routingModes = listOf(
            ModelRoutingMode.MANUAL to "Manual provider selection",
            ModelRoutingMode.LOCAL_ONLY to "Local only",
            ModelRoutingMode.LOCAL_FIRST to "Local first",
            ModelRoutingMode.CLOUD_ONLY to "Cloud only"
        )
        for ((mode, label) in routingModes) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.modelRoutingMode == mode, onClick = { upd(s.copy(modelRoutingMode = mode)) })
                Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
        }
        SwitchRow(
            "Allow cloud fallback",
                "When enabled, automatic routing may send this task and relevant history to configured cloud providers. Model capability flags are user-declared because provider model lists do not reliably report them.",
            s.allowCloudFallback
        ) { upd(s.copy(allowCloudFallback = it)) }
        NumberField("Maximum automatic model switches", s.maxModelSwitches) { upd(s.copy(maxModelSwitches = it.coerceIn(0, 5))) }
            NumberField("Model timeout (seconds)", s.modelTimeoutSeconds) { upd(s.copy(modelTimeoutSeconds = it.coerceIn(15, 600))) }

        SectionTitle("Limits")
        NumberField("Max steps per request", s.maxSteps) { upd(s.copy(maxSteps = it.coerceIn(1, 200))) }
        NumberField("Max characters per tool result", s.maxToolOutputChars) { upd(s.copy(maxToolOutputChars = it.coerceIn(500, 200000))) }
        NumberField("Context budget (characters sent to the model)", s.contextChars) { upd(s.copy(contextChars = it.coerceIn(4000, 2000000))) }
        Text("Lower the context budget for small local models (e.g. 12000).", color = Dim, fontSize = 11.sp)

        SectionTitle("Image & web search")
        OutlinedTextField(
            value = s.sauceNaoKey, onValueChange = { upd(s.copy(sauceNaoKey = it.trim())) },
            label = { Text("SauceNAO API key (free at saucenao.com)") }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(
            value = s.searxngUrl, onValueChange = { upd(s.copy(searxngUrl = it.trim())) },
            label = { Text("SearXNG URL (optional, replaces DuckDuckGo)") }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        Text("trace.moe (anime scene finder) needs no key.", color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))

        SectionTitle("Voice")
        SwitchRow("Speak replies aloud", "Uses your phone's text-to-speech voice.", s.speakReplies) {
            upd(s.copy(speakReplies = it))
            if (!it) vm.stopSpeaking()
        }

        SectionTitle("Memory")
        SwitchRow("Exclude this conversation from learning", "This chat will not retrieve or save persistent knowledge. Existing files and memories are unchanged.", vm.currentChatExcludedFromLearning) {
            vm.excludeCurrentChatFromLearning(it)
        }
        SwitchRow("Persistent memory", "Save and retrieve durable preferences, corrections, and project knowledge on this device.", s.persistentMemoryEnabled) {
            upd(s.copy(persistentMemoryEnabled = it, learnFromDocuments = if (it) s.learnFromDocuments else false))
        }
        SwitchRow("Learn from selected documents", "Index only files you explicitly choose in Files. Originals are never modified; unsupported or unreadable files are reported.", s.learnFromDocuments) {
            upd(s.copy(learnFromDocuments = it, persistentMemoryEnabled = s.persistentMemoryEnabled || it))
        }
        Text("Saved facts and indexed excerpts stay on this device. General knowledge is shared across projects.", color = Dim, fontSize = 12.sp)
        OutlinedTextField(
            value = memoryQuery, onValueChange = { memoryQuery = it }, label = { Text("Search saved knowledge") },
            singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth()
        )
        val visibleMemories = vm.memories.filter {
            (it.projectId == "general" || it.projectId == s.projectId) &&
                (memoryQuery.isBlank() || it.text.contains(memoryQuery, ignoreCase = true) || it.source.contains(memoryQuery, ignoreCase = true))
        }
        for (m in visibleMemories) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(m.text, color = Color.White, fontSize = 13.sp)
                    Text("${m.projectId} · ${m.kind}" + if (m.source.isNotEmpty()) " · ${m.source}" else "", color = Dim, fontSize = 10.sp, maxLines = 1)
                }
                if (m.kind == "fact") TextButton(onClick = { editingMemory = m; editingText = m.text }) { Text("Edit", color = Cyan) }
                TextButton(onClick = { vm.deleteMemory(m.id) }) { Text("✕", color = Danger) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newMemory, onValueChange = { newMemory = it }, label = { Text("Add a memory") },
                singleLine = true, colors = fieldColors(), modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { vm.addMemory(newMemory); newMemory = "" },
                enabled = newMemory.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Black)
            ) { Text("Add") }
        }
        TextButton(onClick = { vm.clearProjectMemory() }) { Text("Clear this project's saved knowledge", color = Danger, fontSize = 12.sp) }

        HorizontalDivider(color = Panel2, modifier = Modifier.padding(vertical = 20.dp))
        Text("Pixel Agent 4.0.1 · your agent, your keys, your device.", color = Dim, fontSize = 12.sp)
        Spacer(Modifier.size(24.dp))
    }

    editingMemory?.let { entry ->
        AlertDialog(
            onDismissRequest = { editingMemory = null },
            title = { Text("Edit memory") },
            text = {
                OutlinedTextField(value = editingText, onValueChange = { editingText = it }, label = { Text("Memory") }, colors = fieldColors())
            },
            confirmButton = {
                TextButton(onClick = { vm.updateMemory(entry.id, editingText); editingMemory = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingMemory = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            val clean = t.filter { it.isDigit() }.take(8)
            text = clean
            clean.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) }, singleLine = true, colors = fieldColors(),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}
