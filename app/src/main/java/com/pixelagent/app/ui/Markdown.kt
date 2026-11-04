package com.pixelagent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class MdPart(val code: Boolean, val lang: String, val body: String)

private fun splitFences(text: String): List<MdPart> {
    val parts = ArrayList<MdPart>()
    val cur = StringBuilder()
    var inCode = false
    var lang = ""
    for (line in text.split("\n")) {
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                parts.add(MdPart(true, lang, cur.toString().trimEnd('\n')))
                cur.setLength(0)
                inCode = false
            } else {
                if (cur.isNotEmpty()) parts.add(MdPart(false, "", cur.toString().trimEnd('\n')))
                cur.setLength(0)
                inCode = true
                lang = line.trim().removePrefix("```").trim()
            }
        } else {
            cur.append(line).append('\n')
        }
    }
    if (cur.isNotEmpty()) parts.add(MdPart(inCode, lang, cur.toString().trimEnd('\n')))
    return parts
}

fun inlineMarkdown(src: String): AnnotatedString = buildAnnotatedString {
    val lines = src.split("\n")
    for ((li, raw) in lines.withIndex()) {
        var line = raw
        var heading = false
        val m = Regex("^#{1,6}\\s+").find(line)
        if (m != null) {
            heading = true
            line = line.substring(m.value.length)
        } else if (line.startsWith("- ") || line.startsWith("* ")) {
            line = "• " + line.substring(2)
        }
        if (heading) pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Gold))
        var i = 0
        while (i < line.length) {
            if (line.startsWith("**", i)) {
                val e = line.indexOf("**", i + 2)
                if (e > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(line.substring(i + 2, e)) }
                    i = e + 2
                    continue
                }
            }
            if (line[i] == '`') {
                val e = line.indexOf('`', i + 1)
                if (e > 0) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x33FFFFFF))) {
                        append(line.substring(i + 1, e))
                    }
                    i = e + 1
                    continue
                }
            }
            append(line[i])
            i++
        }
        if (heading) pop()
        if (li < lines.size - 1) append("\n")
    }
}

@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val parts = remember(text) { splitFences(text) }
    Column(modifier = modifier) {
        for (p in parts) {
            if (p.code) CodeBlock(p.body) else Text(inlineMarkdown(p.body), color = color, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
fun CodeBlock(code: String) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0D0D0D))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("code", color = Dim, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp).weight(1f))
            TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                Text("Copy", color = Cyan, fontSize = 12.sp)
            }
        }
        Text(
            code,
            color = Color(0xFFE0E0E0),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
        )
    }
}
