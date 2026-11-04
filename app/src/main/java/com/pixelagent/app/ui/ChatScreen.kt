package com.pixelagent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelagent.app.AgentViewModel
import com.pixelagent.app.core.Msg
import com.pixelagent.app.core.Role
import com.pixelagent.app.core.ToolCall
import com.pixelagent.app.platform.Perms
import java.io.File

private sealed class Row2 {
    class User(val msg: Msg) : Row2()
    class Assistant(val text: String) : Row2()
    class Tool(val call: ToolCall, val result: Msg?) : Row2()
    class Note(val msg: Msg) : Row2()
}

private fun buildRows(messages: List<Msg>): List<Row2> {
    val results = HashMap<String, Msg>()
    for (m in messages) if (m.role == Role.TOOL) results[m.toolCallId] = m
    val rows = ArrayList<Row2>()
    for (m in messages) {
        when (m.role) {
            Role.USER -> rows.add(Row2.User(m))
            Role.ASSISTANT -> {
                if (m.text.isNotBlank()) rows.add(Row2.Assistant(m.text))
                for (c in m.toolCalls) rows.add(Row2.Tool(c, results[c.id]))
            }
            Role.NOTE -> rows.add(Row2.Note(m))
            Role.TOOL -> {}
        }
    }
    return rows
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    vm: AgentViewModel,
    onPickImage: () -> Unit,
    onVoice: () -> Unit,
    onOpenProviders: () -> Unit,
    onGrantFiles: () -> Unit
) {
    val rows = buildRows(vm.messages)
    val listState = rememberLazyListState()
    val tailLen = vm.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(rows.size, tailLen / 40, vm.running) {
        if (rows.isNotEmpty()) listState.scrollToItem(rows.size)
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var hideBanner by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!hideBanner && vm.permTick >= 0 && !Perms.hasAllFiles(ctx)) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Files live in the app's private folder. Grant all-files access to use /sdcard/PixelAgent.",
                    color = Dim, fontSize = 12.sp, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onGrantFiles) { Text("Grant", color = Cyan, fontSize = 12.sp) }
                TextButton(onClick = { hideBanner = true }) { Text("✕", color = Dim, fontSize = 12.sp) }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (rows.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        PixelAvatar(avatarSize = 96.dp)
                        Gap(12.dp)
                        Text("Pixel Agent", color = Gold, fontSize = 20.sp)
                        Gap(4.dp)
                        if (vm.profiles.isEmpty()) {
                            Text(
                                "Connect an AI model to get started. You can use Claude, OpenAI, OpenRouter, Gemini, or a model running on your own phone or PC.",
                                color = Dim, fontSize = 13.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                            Gap(12.dp)
                            Button(onClick = onOpenProviders, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Black)) {
                                Text("Add a provider")
                            }
                        } else {
                            Text(
                                "Ask me to manage files, search the web, identify an anime scene, or operate your phone.",
                                color = Dim, fontSize = 13.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                            Gap(14.dp)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                for (s in listOf(
                                    "List my files",
                                    "Organize my workspace by type",
                                    "Find duplicate files",
                                    "What can you do on my phone?",
                                    "Search the web for today's top tech news"
                                )) {
                                    Surface(
                                        color = Panel2, shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.clickable { vm.send(s) }
                                    ) {
                                        Text(s, color = Cyan, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            itemsIndexed(rows) { _, row ->
                when (row) {
                    is Row2.User -> UserBubble(row.msg)
                    is Row2.Assistant -> AssistantBubble(row.text)
                    is Row2.Tool -> ToolCard(row.call, row.result, vm.running)
                    is Row2.Note -> NoteBubble(row.msg)
                }
            }
            if (vm.running) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                        PixelAvatar(avatarSize = 40.dp, state = AvatarState.THINKING)
                        Spacer(Modifier.size(8.dp))
                        val label = when {
                            vm.pendingApproval != null -> "Waiting for your approval…"
                            vm.agentState == "tool" -> "Running ${vm.agentDetail}…"
                            else -> "Thinking…"
                        }
                        Text(label, color = Cyan, fontSize = 13.sp)
                    }
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
        InputBar(vm, onPickImage, onVoice)
    }
}

@Composable
private fun UserBubble(msg: Msg) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(color = DarkGold, shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp), modifier = Modifier.widthIn(max = 320.dp)) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (msg.images.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (p in msg.images) Thumb(p, 72.dp)
                    }
                    Gap(6.dp)
                }
                val shown = msg.text.lines().filterNot { it.startsWith("[Attached image:") }.joinToString("\n").trim()
                if (shown.isNotEmpty()) SelectionContainer { Text(shown, color = Black, fontSize = 14.sp, lineHeight = 20.sp) }
            }
        }
    }
}

@Composable
private fun AssistantBubble(text: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(color = Panel2, shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp), modifier = Modifier.widthIn(max = 340.dp)) {
            SelectionContainer {
                MarkdownText(text, Color.White, Modifier.padding(10.dp))
            }
        }
    }
}

@Composable
private fun NoteBubble(msg: Msg) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(color = if (msg.isError) Color(0xFF3A1A1A) else Panel, shape = RoundedCornerShape(12.dp)) {
            Text(
                msg.text,
                color = if (msg.isError) Color(0xFFFF8A80) else Dim,
                fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun ToolCard(call: ToolCall, result: Msg?, running: Boolean) {
    var open by remember { mutableStateOf(false) }
    val status = when {
        result == null && running -> "⏳"
        result == null -> "—"
        result.isError -> "✗"
        else -> "✓"
    }
    val statusColor = when {
        result == null -> Dim
        result.isError -> Danger
        else -> Ok
    }
    Surface(color = Panel, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().clickable { open = !open }) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status, color = statusColor, fontSize = 13.sp)
                Spacer(Modifier.size(8.dp))
                Text(call.name, color = Cyan, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.size(8.dp))
                Text(
                    call.args.toString().take(80),
                    color = Dim, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f),
                    fontFamily = FontFamily.Monospace
                )
                Text(if (open) "▴" else "▾", color = Dim, fontSize = 12.sp)
            }
            if (open) {
                Gap(6.dp)
                SelectionContainer {
                    Column {
                        Text("args: " + call.args.toString().take(1500), color = Dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        if (result != null) {
                            Gap(6.dp)
                            Text(result.text.take(3000), color = if (result.isError) Color(0xFFFF8A80) else Color(0xFFCCCCCC), fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InputBar(vm: AgentViewModel, onPickImage: () -> Unit, onVoice: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(DarkGray)) {
        if (vm.attachments.isNotEmpty()) {
            Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in vm.attachments) {
                    Box {
                        Thumb(p, 56.dp)
                        Surface(
                            color = Black, shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.align(Alignment.TopEnd).clickable { vm.removeAttachment(p) }
                        ) {
                            Text(" ✕ ", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            IconButton(onClick = onPickImage) { Text("📎", fontSize = 20.sp) }
            OutlinedTextField(
                value = vm.draft,
                onValueChange = { vm.draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message Pixel Agent…", color = Dim) },
                maxLines = 5,
                shape = RoundedCornerShape(22.dp),
                colors = fieldColors()
            )
            IconButton(onClick = onVoice) { Text("🎤", fontSize = 20.sp) }
            if (vm.running) {
                Button(
                    onClick = { vm.stop() },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) { Text("Stop") }
            } else {
                Button(
                    onClick = { vm.send(vm.draft) },
                    enabled = vm.draft.isNotBlank() || vm.attachments.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Black),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            }
        }
    }
}
