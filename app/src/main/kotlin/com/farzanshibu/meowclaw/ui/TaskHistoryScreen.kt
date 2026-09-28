package com.farzanshibu.meowclaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.farzanshibu.meowclaw.data.TaskRecord
import com.farzanshibu.meowclaw.graph
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun TaskHistoryScreen(onBack: () -> Unit) {
    val g = LocalContext.current.graph
    val c = Brutal.colors
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<TaskRecord>?>(null) }
    LaunchedEffect(Unit) { records = g.taskHistory.read() }

    Column(Modifier.fillMaxSize().background(c.paper).statusBarsPadding()) {
        ScreenHeader("History", c.green, onBack) {
            BrutalIconButton(Icons.Rounded.DeleteSweep, "Clear history", {
                scope.launch { g.taskHistory.clear(); records = emptyList() }
            }, fill = c.red)
        }
        val list = records ?: return@Column
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No tasks yet.", fontWeight = FontWeight.Black, fontSize = 18.sp, color = c.muted)
            }
            return@Column
        }
        val success = list.count { it.status == "Success" }
        LazyColumn(
            Modifier.navigationBarsPadding(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Tasks", list.size.toString(), c.yellow, Modifier.weight(1f))
                    Stat("Success", "${success * 100 / list.size}%", c.green, Modifier.weight(1f))
                    Stat("Tokens", list.sumOf { it.totalTokens }.toString(), c.blue, Modifier.weight(1f))
                }
            }
            items(list) { TaskCard(it) }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, accent: Color, modifier: Modifier) {
    val c = Brutal.colors
    BrutalCard(modifier, fill = accent, padding = PaddingValues(12.dp)) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Black, color = c.onAccent, maxLines = 1)
        Text(label.uppercase(), fontSize = 11.sp, fontFamily = Brutal.Mono, fontWeight = FontWeight.Black, color = c.onAccent)
    }
}

@Composable
private fun TaskCard(record: TaskRecord) {
    val c = Brutal.colors
    var expanded by remember { mutableStateOf(false) }
    val accent = when (record.status) {
        "Success" -> c.green
        "Cancelled" -> c.orange
        else -> c.red
    }
    val time = runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
            .format(Instant.parse(record.timestamp))
    }.getOrDefault(record.timestamp)
    BrutalCard(Modifier.fillMaxWidth().clickable { expanded = !expanded }, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(record.goal, Modifier.weight(1f), fontWeight = FontWeight.ExtraBold, color = c.ink)
            Spacer(Modifier.width(8.dp))
            Tag(record.status, accent)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "$time · ${record.stepsTaken} steps · ${record.totalTokens} tokens",
            fontSize = 12.sp, fontFamily = Brutal.Mono, color = c.muted,
        )
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            record.trace.forEach {
                Text("▸ $it", fontSize = 11.5.sp, fontFamily = Brutal.Mono, color = c.ink, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}
