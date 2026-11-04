package com.pixelagent.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelagent.app.core.ApprovalDecision
import com.pixelagent.app.core.Risk
import com.pixelagent.app.core.Role
import com.pixelagent.app.ui.AvatarState
import com.pixelagent.app.ui.ChatScreen
import com.pixelagent.app.ui.Black
import com.pixelagent.app.ui.Cyan
import com.pixelagent.app.ui.Danger
import com.pixelagent.app.ui.DarkGray
import com.pixelagent.app.ui.Dim
import com.pixelagent.app.ui.FilesScreen
import com.pixelagent.app.ui.Gold
import com.pixelagent.app.ui.Panel
import com.pixelagent.app.ui.PixelAgentTheme
import com.pixelagent.app.ui.PixelAvatar
import com.pixelagent.app.ui.ProvidersScreen
import com.pixelagent.app.ui.SettingsScreen
import com.pixelagent.app.ui.fieldColors
import com.pixelagent.app.ui.openAllFilesSettings
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AgentViewModel by viewModels()

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.attachUri(uri)
    }

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) vm.draft = (vm.draft + " " + text).trim()
        }
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBasicPermissions()
        handleIntent(intent)
        setContent {
            PixelAgentTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Black) {
                    AppRoot(
                        vm = vm,
                        onPickImage = { pickImage.launch("image/*") },
                        onVoice = { startVoice() },
                        onGrantFiles = { openAllFilesSettings(this) }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermissions()
    }

    private fun requestBasicPermissions() {
        val wanted = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < 30) {
            wanted.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            wanted.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrBlank()) vm.draft = text
        val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (stream != null) vm.attachUri(stream)
    }

    private fun startVoice() {
        try {
            voice.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Pixel Agent")
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No speech recognizer is installed on this phone.", Toast.LENGTH_LONG).show()
        }
    }
}

private enum class Screen { CHAT, FILES, PROVIDERS, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(vm: AgentViewModel, onPickImage: () -> Unit, onVoice: () -> Unit, onGrantFiles: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.CHAT) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    LaunchedEffect(vm.toast) {
        val t = vm.toast
        if (t != null) {
            Toast.makeText(ctx, t, Toast.LENGTH_LONG).show()
            vm.toast = null
        }
    }
    BackHandler(enabled = screen != Screen.CHAT) { screen = Screen.CHAT }

    fun go(s: Screen) {
        screen = s
        scope.launch { drawer.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = DarkGray) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    PixelAvatar(avatarSize = 48.dp)
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text("Pixel Agent", color = Gold, fontSize = 18.sp)
                        Text("Your agent. Your keys.", color = Dim, fontSize = 11.sp)
                    }
                }
                Button(
                    onClick = { vm.newChat(); go(Screen.CHAT) },
                    enabled = !vm.running,
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Black),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) { Text("+ New chat") }
                Spacer(Modifier.size(8.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(vm.chats) { c ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { vm.openChat(c.id); go(Screen.CHAT) }
                                .background(if (c.id == vm.currentChatId) Panel else Color.Transparent)
                                .padding(start = 16.dp, end = 4.dp)
                        ) {
                            Text(c.title, color = Color.White, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                            TextButton(onClick = { vm.deleteChat(c.id) }) { Text("✕", color = Dim) }
                        }
                    }
                }
                HorizontalDivider(color = Panel)
                val itemColors = NavigationDrawerItemDefaults.colors(
                    unselectedContainerColor = Color.Transparent,
                    selectedContainerColor = Panel
                )
                NavigationDrawerItem(label = { Text("💬  Chat", color = Color.White) }, selected = screen == Screen.CHAT, onClick = { go(Screen.CHAT) }, colors = itemColors)
                NavigationDrawerItem(label = { Text("📁  Files", color = Color.White) }, selected = screen == Screen.FILES, onClick = { go(Screen.FILES) }, colors = itemColors)
                NavigationDrawerItem(label = { Text("🧠  AI providers", color = Color.White) }, selected = screen == Screen.PROVIDERS, onClick = { go(Screen.PROVIDERS) }, colors = itemColors)
                NavigationDrawerItem(label = { Text("⚙  Settings", color = Color.White) }, selected = screen == Screen.SETTINGS, onClick = { go(Screen.SETTINGS) }, colors = itemColors)
            }
        }
    ) {
        Scaffold(
            containerColor = Black,
            topBar = {
                val last = vm.messages.lastOrNull()
                val streaming = vm.running && last != null && last.role == Role.ASSISTANT && last.text.isNotEmpty()
                val avatarState = when {
                    streaming -> AvatarState.TALKING
                    vm.running -> AvatarState.THINKING
                    else -> AvatarState.IDLE
                }
                var providerMenu by remember { mutableStateOf(false) }
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Black, titleContentColor = Gold, navigationIconContentColor = Gold),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, contentDescription = "Menu") }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PixelAvatar(avatarSize = 44.dp, state = avatarState)
                            Spacer(Modifier.size(8.dp))
                            Column {
                                Text(
                                    when (screen) {
                                        Screen.CHAT -> "Pixel Agent"
                                        Screen.FILES -> "Files"
                                        Screen.PROVIDERS -> "Providers"
                                        Screen.SETTINGS -> "Settings"
                                    },
                                    color = Gold, fontSize = 17.sp
                                )
                                Text(
                                    if (vm.running) (if (vm.agentState == "tool") "working…" else "thinking…") else "ready",
                                    color = if (vm.running) Cyan else Dim, fontSize = 11.sp
                                )
                            }
                        }
                    },
                    actions = {
                        Box {
                            TextButton(onClick = { providerMenu = true }) {
                                Text((vm.activeProfile?.name ?: "No provider") + " ▾", color = Cyan, fontSize = 12.sp, maxLines = 1)
                            }
                            DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                                for (p in vm.profiles) {
                                    DropdownMenuItem(
                                        text = { Text((if (p.id == vm.activeProfile?.id) "● " else "○ ") + p.name) },
                                        onClick = { vm.setActive(p.id); providerMenu = false }
                                    )
                                }
                                DropdownMenuItem(text = { Text("Manage providers…") }, onClick = { providerMenu = false; screen = Screen.PROVIDERS })
                            }
                        }
                    }
                )
            }
        ) { pad ->
            Box(modifier = Modifier.padding(pad).fillMaxSize()) {
                when (screen) {
                    Screen.CHAT -> ChatScreen(vm, onPickImage, onVoice, onOpenProviders = { screen = Screen.PROVIDERS }, onGrantFiles = onGrantFiles)
                    Screen.FILES -> FilesScreen(vm) { text ->
                        if (text.isNotEmpty()) vm.draft = text
                        screen = Screen.CHAT
                    }
                    Screen.PROVIDERS -> ProvidersScreen(vm)
                    Screen.SETTINGS -> SettingsScreen(vm)
                }
            }
        }
    }

    // Permission prompt for a risky tool call
    val req = vm.pendingApproval
    if (req != null) {
        AlertDialog(
            onDismissRequest = { vm.resolveApproval(ApprovalDecision.DENY) },
            containerColor = Panel,
            title = {
                Text(
                    (if (req.risk == Risk.DANGEROUS) "⚠ Allow risky action?" else "Allow action?"),
                    color = if (req.risk == Risk.DANGEROUS) Danger else Gold, fontSize = 17.sp
                )
            },
            text = {
                Column {
                    Text(req.summary, color = Color.White, fontSize = 13.sp)
                    Spacer(Modifier.size(10.dp))
                    TextButton(onClick = { vm.resolveApproval(ApprovalDecision.ALLOW_SESSION) }) {
                        Text("Always allow '${req.toolName}' until the app restarts", color = Cyan, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.resolveApproval(ApprovalDecision.ALLOW_ONCE) },
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Black)
                ) { Text("Allow once") }
            },
            dismissButton = { TextButton(onClick = { vm.resolveApproval(ApprovalDecision.DENY) }) { Text("Deny", color = Danger) } }
        )
    }

    // A question from the agent (ask_user tool)
    val q = vm.pendingQuestion
    if (q != null) {
        var answer by remember(q) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { vm.answerQuestion(null) },
            containerColor = Panel,
            title = { Text("Pixel Agent asks", color = Gold, fontSize = 17.sp) },
            text = {
                Column {
                    Text(q, color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.size(10.dp))
                    OutlinedTextField(value = answer, onValueChange = { answer = it }, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.answerQuestion(answer) },
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Black)
                ) { Text("Answer") }
            },
            dismissButton = { TextButton(onClick = { vm.answerQuestion(null) }) { Text("Skip", color = Dim) } }
        )
    }
}
