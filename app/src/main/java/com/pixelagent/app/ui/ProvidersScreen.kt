package com.pixelagent.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pixelagent.app.AgentViewModel
import com.pixelagent.app.core.ProviderPresets
import com.pixelagent.app.core.ProviderProfile
import com.pixelagent.app.core.ProviderType
import com.pixelagent.app.core.ToolMode

@Composable
fun ProvidersScreen(vm: AgentViewModel) {
    var editing by remember { mutableStateOf<ProviderProfile?>(null) }
    var choosingPreset by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        Text("AI providers", color = Gold, fontSize = 20.sp)
        Text(
            "Pick the brain behind the agent. Keys stay on this phone and are only sent to the provider you configure.",
            color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
        )
        Button(
            onClick = { choosingPreset = true },
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Black),
            modifier = Modifier.fillMaxWidth()
        ) { Text("+ Add provider") }
        Spacer(Modifier.size(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.profiles) { p ->
                val active = p.id == vm.activeProfile?.id
                Surface(
                    color = Panel, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().clickable { editing = p }
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(p.name, color = Color.White, fontSize = 15.sp)
                            Text(
                                (when (p.type) { ProviderType.ANTHROPIC -> "Anthropic"; ProviderType.LOCAL -> "On-device"; else -> "OpenAI-compatible" }) + " · " + p.model.substringAfterLast('/').ifEmpty { "no model set" },
                                color = Dim, fontSize = 12.sp
                            )
                            Text(p.baseUrl, color = Dim, fontSize = 11.sp, maxLines = 1)
                        }
                        if (active) {
                            Text("ACTIVE", color = Ok, fontSize = 11.sp)
                        } else {
                            TextButton(onClick = { vm.setActive(p.id) }) { Text("Use", color = Cyan) }
                        }
                    }
                }
            }
        }
    }

    if (choosingPreset) {
        Dialog(onDismissRequest = { choosingPreset = false }) {
            Surface(shape = RoundedCornerShape(16.dp), color = Panel) {
                Column(modifier = Modifier.padding(14.dp).verticalScroll(rememberScrollState())) {
                    Text("Choose a starting point", color = Gold, fontSize = 16.sp)
                    Spacer(Modifier.size(8.dp))
                    for (preset in ProviderPresets.all) {
                        Column(
                            modifier = Modifier.fillMaxWidth().clickable {
                                choosingPreset = false
                                editing = preset.toProfile()
                            }.padding(vertical = 8.dp)
                        ) {
                            Text(preset.label, color = Color.White, fontSize = 14.sp)
                            if (preset.note.isNotEmpty()) Text(preset.note, color = Dim, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }

    val ed = editing
    if (ed != null) {
        ProviderEditor(
            initial = ed,
            isNew = vm.profiles.none { it.id == ed.id },
            vm = vm,
            onClose = { editing = null }
        )
    }
}

@Composable
private fun ProviderEditor(initial: ProviderProfile, isNew: Boolean, vm: AgentViewModel, onClose: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var type by remember { mutableStateOf(initial.type) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var temperature by remember { mutableStateOf(initial.temperature.toFloat()) }
    var maxTokens by remember { mutableStateOf(initial.maxTokens.toString()) }
    var toolMode by remember { mutableStateOf(initial.toolMode) }
    var vision by remember { mutableStateOf(initial.vision) }
    var visionVerified by remember { mutableStateOf(initial.visionVerified) }
    var showKey by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var menu by remember { mutableStateOf(false) }
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            vm.importModel(uri, { status = it }) { path -> busy = false; if (path != null) model = path }
        }
    }

    fun current() = initial.copy(
        name = name.trim().ifEmpty { "Provider" },
        type = type,
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        model = model.trim(),
        temperature = temperature.toDouble(),
        maxTokens = maxTokens.toIntOrNull()?.coerceIn(64, 200000) ?: 4096,
        toolMode = toolMode,
        vision = vision,
        visionVerified = visionVerified
    )

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(16.dp), color = Panel, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text(if (isNew) "New provider" else "Edit provider", color = Gold, fontSize = 18.sp)
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = type == ProviderType.LOCAL, onClick = { type = ProviderType.LOCAL; toolMode = ToolMode.TEXT; vision = false })
                    Text("On-device", color = Color.White, fontSize = 13.sp)
                    Spacer(Modifier.size(10.dp))
                    RadioButton(selected = type == ProviderType.OPENAI, onClick = { type = ProviderType.OPENAI })
                    Text("OpenAI-compatible", color = Color.White, fontSize = 13.sp)
                    Spacer(Modifier.size(10.dp))
                    RadioButton(selected = type == ProviderType.ANTHROPIC, onClick = { type = ProviderType.ANTHROPIC })
                    Text("Anthropic", color = Color.White, fontSize = 13.sp)
                }
                if (type == ProviderType.LOCAL) {
                    OutlinedTextField(
                        value = model, onValueChange = { model = it }, label = { Text("Model file (.litertlm)") }, singleLine = true,
                        colors = fieldColors(), modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.size(6.dp))
                    OutlinedButton(onClick = { pickModel.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Pick model file…", color = Cyan) }
                    Text(
                        "Download a .litertlm model (e.g. a Gemma model from huggingface.co/litert-community) to your phone, then pick it here. It is copied into the app once, then runs fully offline. You can also paste a path such as /sdcard/Download/model.litertlm.",
                        color = Dim, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") }, singleLine = true,
                    colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it }, label = { Text("API key (blank for local servers)") }, singleLine = true,
                    colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show", color = Cyan, fontSize = 12.sp) } }
                )
                Spacer(Modifier.size(8.dp))
                Box {
                    OutlinedTextField(
                        value = model, onValueChange = { model = it }, label = { Text("Model") }, singleLine = true,
                        colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            TextButton(onClick = {
                                busy = true
                                status = "Fetching models…"
                                vm.fetchModels(current()) { list, err ->
                                    busy = false
                                    models = list
                                    status = err ?: "${list.size} models found. Tap the model field's menu."
                                    if (list.isNotEmpty()) menu = true
                                }
                            }) { Text("Fetch", color = Cyan, fontSize = 12.sp) }
                        }
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        for (m in models.take(200)) {
                            DropdownMenuItem(text = { Text(m, fontSize = 13.sp) }, onClick = { model = m; menu = false })
                        }
                    }
                }
                }
                Spacer(Modifier.size(8.dp))
                Text("Temperature: " + "%.2f".format(temperature), color = Color.White, fontSize = 13.sp)
                Slider(value = temperature, onValueChange = { temperature = it }, valueRange = 0f..2f)
                OutlinedTextField(
                    value = maxTokens, onValueChange = { maxTokens = it.filter { c -> c.isDigit() } }, label = { Text("Max reply tokens") }, singleLine = true,
                    colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(Modifier.size(10.dp))
                Text("How tools are used", color = Color.White, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = toolMode == ToolMode.NATIVE, onClick = { toolMode = ToolMode.NATIVE })
                    Text("Native", color = Color.White, fontSize = 13.sp)
                    RadioButton(selected = toolMode == ToolMode.TEXT, onClick = { toolMode = ToolMode.TEXT })
                    Text("Text", color = Color.White, fontSize = 13.sp)
                    RadioButton(selected = toolMode == ToolMode.NONE, onClick = { toolMode = ToolMode.NONE })
                    Text("Off", color = Color.White, fontSize = 13.sp)
                }
                Text(
                    "Native = the model's built-in function calling (best for Claude, GPT, Gemini…). Text = prompt-based, works with any model including small local ones. Off = plain chat.",
                    color = Dim, fontSize = 11.sp, lineHeight = 15.sp
                )
                SwitchRow(
                    "Verified image support",
                    "Enable only after confirming this exact model accepts image input. Unverified models will never receive attached images.",
                    visionVerified
                ) {
                    visionVerified = it
                    vision = it
                }
                if (status.isNotEmpty()) Text(status, color = if (status.startsWith("✗")) Danger else Ok, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            busy = true
                            status = "Testing…"
                            vm.testProvider(current()) { r -> busy = false; status = r }
                        },
                        enabled = !busy
                    ) { Text("Test", color = Cyan) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Cancel", color = Dim) }
                    Button(
                        onClick = { vm.saveProfile(current()); onClose() },
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Black)
                    ) { Text("Save") }
                }
                if (!isNew) {
                    TextButton(onClick = { vm.deleteProfile(initial.id); onClose() }) { Text("Delete this provider", color = Danger, fontSize = 12.sp) }
                }
            }
        }
    }
}
