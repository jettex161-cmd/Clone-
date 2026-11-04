package com.pixelagent.app.ui

import android.content.Intent
import android.webkit.MimeTypeMap
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.pixelagent.app.AgentViewModel
import com.pixelagent.app.core.humanSize
import java.io.File

private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

@Composable
fun FilesScreen(vm: AgentViewModel, onAskAbout: (String) -> Unit) {
    val ctx = LocalContext.current
    val root = remember(vm.settings.workspacePath, vm.permTick) { vm.workspaceDir() }
    var dir by remember(root) { mutableStateOf(root) }
    var refresh by remember { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<File?>(null) }

    val entries = remember(dir, refresh) {
        (dir.listFiles() ?: emptyArray()).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text("Files", color = Gold, fontSize = 20.sp)
        Text(dir.path, color = Dim, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (dir.path != root.path && dir.parentFile != null) {
                TextButton(onClick = { dir = dir.parentFile ?: root }) { Text("↑ Up", color = Cyan) }
            }
            TextButton(onClick = { refresh++ }) { Text("Refresh", color = Cyan) }
        }
        if (entries.isEmpty()) {
            Text("This folder is empty. Ask the agent to create or download something.", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(entries) { f ->
                val isImage = !f.isDirectory && f.extension.lowercase() in IMAGE_EXTS
                Box {
                    Surface(
                        color = Panel, shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (f.isDirectory) dir = f else menuFor = f.path
                        }
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (isImage) Thumb(f.path, 40.dp) else Text(if (f.isDirectory) "📁" else "📄", fontSize = 26.sp)
                            Spacer(Modifier.size(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(f.name, color = Color.White, fontSize = 14.sp, maxLines = 1)
                                if (!f.isDirectory) Text(humanSize(f.length()), color = Dim, fontSize = 11.sp)
                            }
                            if (f.isDirectory) {
                                TextButton(onClick = { confirmDelete = f }) { Text("🗑", fontSize = 16.sp) }
                            }
                        }
                    }
                    DropdownMenu(expanded = menuFor == f.path, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(text = { Text("Open") }, onClick = {
                            menuFor = null
                            try {
                                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
                                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
                                ctx.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                        "Open with"
                                    )
                                )
                            } catch (e: Exception) {
                                vm.toast = "No app can open this file"
                            }
                        })
                        if (isImage) {
                            DropdownMenuItem(text = { Text("Attach to chat") }, onClick = {
                                menuFor = null
                                vm.attachPath(f.path)
                                onAskAbout("")
                            })
                            DropdownMenuItem(text = { Text("Reverse image search") }, onClick = {
                                menuFor = null
                                vm.attachPath(f.path)
                                onAskAbout("Reverse search this image: where is it from? If it is an anime screenshot, tell me the title, episode and timestamp.")
                            })
                        }
                        DropdownMenuItem(text = { Text("Delete", color = Danger) }, onClick = {
                            menuFor = null
                            confirmDelete = f
                        })
                    }
                }
            }
        }
    }

    val del = confirmDelete
    if (del != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${del.name}?") },
            text = { Text(if (del.isDirectory) "The folder and everything inside it will be deleted." else "This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    del.deleteRecursively()
                    confirmDelete = null
                    refresh++
                }) { Text("Delete", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}
