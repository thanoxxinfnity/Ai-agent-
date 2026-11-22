package com.aura.agent.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.agent.R

sealed interface Block {
    data class Para(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Code(val lang: String, val code: String) : Block
    data class Item(val indent: Int, val marker: String, val text: String) : Block
    data class Quote(val text: String) : Block
    data class Table(val lines: List<String>) : Block
    data object Rule : Block
}

private val headingRe = Regex("^(#{1,4})\\s+(.*)$")
private val itemRe = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
private val ruleRe = Regex("^\\s*([-*_])\\1{2,}\\s*$")

fun parseBlocks(src: String): List<Block> {
    val out = ArrayList<Block>()
    val lines = src.split("\n")
    var i = 0
    val para = ArrayList<String>()
    fun flush() { if (para.isNotEmpty()) { out += Block.Para(para.joinToString("\n")); para.clear() } }
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trimStart()
        when {
            t.startsWith("```") -> {
                flush()
                val lang = t.removePrefix("```").trim()
                val sb = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { sb.append(lines[i]).append('\n'); i++ }
                out += Block.Code(lang, sb.toString().trimEnd('\n'))
            }
            line.isBlank() -> flush()
            ruleRe.matches(line) -> { flush(); out += Block.Rule }
            headingRe.matches(line) -> { flush(); val m = headingRe.find(line)!!; out += Block.Heading(m.groupValues[1].length, m.groupValues[2]) }
            itemRe.matches(line) -> { flush(); val m = itemRe.find(line)!!; out += Block.Item(m.groupValues[1].length / 2, m.groupValues[2], m.groupValues[3]) }
            t.startsWith(">") -> { flush(); out += Block.Quote(t.removePrefix(">").trim()) }
            t.startsWith("|") -> {
                flush()
                val rows = ArrayList<String>()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) { rows += lines[i]; i++ }
                i--
                out += Block.Table(rows)
            }
            else -> para += line
        }
        i++
    }
    flush()
    return out
}

private fun inline(s: String, c: AuraColors): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        if (s.startsWith("**", i)) {
            val e = s.indexOf("**", i + 2)
            if (e > i + 2) { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline(s.substring(i + 2, e), c)) }; i = e + 2; continue }
        }
        if (ch == '`') {
            val e = s.indexOf('`', i + 1)
            if (e > i + 1) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = c.code, fontSize = 14.sp, color = c.accent)) { append(s.substring(i + 1, e)) }
                i = e + 1; continue
            }
        }
        if (ch == '*' && i + 1 < s.length && s[i + 1] != ' ' && s[i + 1] != '*') {
            val e = s.indexOf('*', i + 1)
            if (e > i + 1) { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, e)) }; i = e + 1; continue }
        }
        if (ch == '[') {
            val close = s.indexOf("](", i + 1)
            val end = if (close > 0) s.indexOf(')', close + 2) else -1
            if (close > 0 && end > 0) {
                val label = s.substring(i + 1, close)
                val url = s.substring(close + 2, end)
                pushStringAnnotation("URL", url)
                withStyle(SpanStyle(color = c.accent, textDecoration = TextDecoration.Underline)) { append(label) }
                pop()
                i = end + 1; continue
            }
        }
        if (s.startsWith("https://", i) || s.startsWith("http://", i)) {
            var e = i
            while (e < s.length && !s[e].isWhitespace() && s[e] != ')' && s[e] != '>') e++
            val url = s.substring(i, e).trimEnd('.', ',', ';')
            pushStringAnnotation("URL", url)
            withStyle(SpanStyle(color = c.accent, textDecoration = TextDecoration.Underline)) { append(url) }
            pop()
            i += url.length; continue
        }
        append(ch); i++
    }
}

@Composable
private fun RichText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val c = A
    val uri = LocalUriHandler.current
    val annotated = remember(text, c) { inline(text, c) }
    ClickableText(annotated, modifier = modifier, style = style) { off ->
        annotated.getStringAnnotations("URL", off, off).firstOrNull()?.let { runCatching { uri.openUri(it.item) } }
    }
}

@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
    val c = A
    val blocks = remember(text) { parseBlocks(text) }
    val body = TextStyle(color = c.text, fontSize = 16.sp, lineHeight = 25.sp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Block.Para -> RichText(b.text, body)
                is Block.Heading -> RichText(
                    b.text,
                    TextStyle(color = c.text, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                        fontSize = when (b.level) { 1 -> 24.sp; 2 -> 21.sp; else -> 18.sp }, lineHeight = 28.sp),
                    Modifier.padding(top = 6.dp),
                )
                is Block.Item -> Row(Modifier.padding(start = (b.indent * 16).dp)) {
                    Text(if (b.marker.first().isDigit()) b.marker else "•", style = body.copy(color = c.dim), modifier = Modifier.width(22.dp))
                    RichText(b.text, body, Modifier.weight(1f))
                }
                is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.border))
                    RichText(b.text, body.copy(color = c.dim), Modifier.padding(start = 12.dp))
                }
                is Block.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
                is Block.Table -> CodeBox("", b.lines.joinToString("\n"))
                is Block.Code -> CodeBox(b.lang, b.code)
            }
        }
    }
}

@Composable
private fun CodeBox(lang: String, code: String) {
    val c = A
    val clip = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.code).border(1.dp, c.border, RoundedCornerShape(12.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(lang.ifBlank { "text" }, color = c.dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            IconBtn(if (copied) R.drawable.ic_check else R.drawable.ic_copy, { clip.setText(AnnotatedString(code)); copied = true }, tint = c.dim, size = 36.dp)
        }
        Text(
            code, color = c.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        )
    }
}
