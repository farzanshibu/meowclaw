package com.farzanshibu.meowclaw.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.farzanshibu.meowclaw.data.ChatMessage
import com.farzanshibu.meowclaw.data.ReplySource
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor

@Composable
fun MessageBubble(message: ChatMessage) {
    if (message.content.isEmpty()) return
    val c = Brutal.colors
    val isUser = message.isUser
    if (message.isProgress) {
        Text(
            "▸ " + message.content.removePrefix("⏳ "),
            Modifier.padding(start = 6.dp, top = 2.dp, bottom = 2.dp),
            fontSize = 12.sp, fontFamily = Brutal.Mono, color = c.muted,
        )
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier.widthIn(max = 330.dp).padding(end = Brutal.Shadow, bottom = Brutal.Shadow)
                .brutal(if (isUser) c.yellow else c.surface, c.ink, c.shadow, 14.dp)
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            if (isUser) {
                Text(message.content, color = c.onAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            } else {
                SelectionContainer {
                    Markdown(
                        // Tool output is line-based; markdown would join single lines into one paragraph.
                        if (message.actionResult != null) message.content.replace("\n", "  \n") else message.content,
                        colors = markdownColor(text = c.ink),
                    )
                }
                val result = message.actionResult
                if (result != null || message.source != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        result?.let {
                            Tag(
                                it.actionType.replace('_', ' '),
                                if (it.success) c.green else c.red,
                                icon = if (it.success) Icons.Rounded.Check else Icons.Rounded.Close,
                            )
                        }
                        SourceTag(message.source, message.latencyMs)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceTag(source: ReplySource?, latencyMs: Long?) {
    val c = Brutal.colors
    val time = latencyMs?.let { if (it < 1000) " · $it ms" else " · %.1f s".format(it / 1000.0) }.orEmpty()
    when (source) {
        ReplySource.NEEDLE -> Tag("needle$time", c.green, icon = Icons.Rounded.Bolt)
        ReplySource.LOCAL -> Tag("on-device$time", c.yellow, icon = Icons.Rounded.Memory)
        ReplySource.LLM -> Tag("api$time", c.blue, icon = Icons.Rounded.Cloud)
        else -> Unit
    }
}
