package ua.madeisy.atlantic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.madeisy.atlantic.core.Catalog
import ua.madeisy.atlantic.core.Heater
import ua.madeisy.atlantic.core.Program
import ua.madeisy.atlantic.core.Slot

@Composable
fun ScheduleTab(st: UiState, h: Heater, vm: AppViewModel) {
    val start = h.programDayStart
    if (start == null) {
        Column(Modifier.padding(16.dp)) {
            InfoCard(
                "Пристрій не повідомив тижневу програму у хмару. Якщо в офіційному Cozytouch програма є, " +
                    "надішліть «Діагностику» з вкладки «Усі параметри» — додам підтримку.",
            )
        }
        return
    }
    val week = (0..6).map { Program.parseOrNull(h.device.cap(start + it)?.value) ?: emptyList() }
    var day by remember { mutableIntStateOf(todayIndex()) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (h.hasProgram && !h.programOn) InfoCard("Програма зараз вимкнена — розклад почне діяти, коли увімкнете «Тижнева програма» на вкладці «Керування».")

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Тиждень", style = MaterialTheme.typography.titleSmall)
                week.forEachIndexed { i, slots ->
                    Row(
                        Modifier.fillMaxWidth().clickable { day = i },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            Program.DAY_SHORT[i], Modifier.width(32.dp),
                            fontWeight = if (i == day) FontWeight.Bold else FontWeight.Normal,
                            color = if (i == day) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                        DayBar(slots, Modifier.weight(1f).height(18.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 32.dp)) {
                    for (t in listOf("0", "6", "12", "18", "24")) {
                        Text(t, style = MaterialTheme.typography.labelSmall)
                        if (t != "24") Spacer(Modifier.weight(1f))
                    }
                }
                Text("Колір: синій — холодніше, помаранчевий — тепліше", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Program.DAY_SHORT.forEachIndexed { i, d -> FilterChip(selected = day == i, onClick = { day = i }, label = { Text(d) }) }
        }

        // key = day + серверне значення — редактор скидається, коли змінюється день або прийшли нові дані
        val serverValue = h.device.cap(start + day)?.value
        DayEditor(
            key = "$day|$serverValue",
            dayIndex = day,
            initial = week[day],
            maxSlots = h.maxSlotsPerDay,
            busy = (start + day) in st.writing,
            onSave = { days, slots -> vm.writeDays(days, slots) },
        )
    }
}

@Composable
private fun DayEditor(
    key: String,
    dayIndex: Int,
    initial: List<Slot>,
    maxSlots: Int,
    busy: Boolean,
    onSave: (List<Int>, List<Slot>) -> Unit,
) {
    val slots = remember(key) { mutableStateListOf<Slot>().apply { addAll(initial.ifEmpty { listOf(Slot(0, 17.0)) }) } }
    val timeText = remember(key) { mutableStateListOf<String>().apply { addAll(slots.map { it.time }) } }
    var copyDialog by remember(key) { mutableStateOf(false) }
    var problem by remember(key) { mutableStateOf<String?>(null) }

    fun collect(): List<Slot>? {
        val out = ArrayList<Slot>()
        for (i in slots.indices) {
            val m = Program.parseTime(timeText[i])
            if (m == null) { problem = "Невірний час «${timeText[i]}» — формат ГГ:ХХ"; return null }
            out += Slot(m, slots[i].temp)
        }
        return runCatching { Program.encode(out, maxSlots); out.sortedBy { it.minute } }
            .getOrElse { problem = it.message; null }
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Program.DAY_NAMES[dayIndex], style = MaterialTheme.typography.titleMedium)
            Text("З якого часу яку температуру тримати. Перший рядок — з 00:00.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            slots.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = timeText[i],
                        onValueChange = { v -> timeText[i] = v.filter { it.isDigit() || it == ':' }.take(5); problem = null },
                        enabled = i != 0,
                        singleLine = true,
                        modifier = Modifier.width(96.dp),
                    )
                    TextButton(onClick = { slots[i] = s.copy(temp = (s.temp - 0.5).coerceAtLeast(5.0)) }) { Text("−") }
                    Text("${Catalog.fmt(s.temp)}°", Modifier.width(52.dp), fontWeight = FontWeight.Medium)
                    TextButton(onClick = { slots[i] = s.copy(temp = (s.temp + 0.5).coerceAtMost(35.0)) }) { Text("+") }
                    Spacer(Modifier.weight(1f))
                    if (i != 0) IconButton(onClick = { slots.removeAt(i); timeText.removeAt(i) }) { Icon(Icons.Default.Delete, "Видалити") }
                }
            }
            if (slots.size < maxSlots) OutlinedButton(onClick = {
                val lastMin = Program.parseTime(timeText.last()) ?: 0
                val next = (lastMin + 120).coerceAtMost(23 * 60 + 30)
                slots.add(Slot(next, slots.last().temp))
                timeText.add("%02d:%02d".format(next / 60, next % 60))
            }) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Додати інтервал")
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { collect()?.let { onSave(listOf(dayIndex), it) } }, enabled = !busy) {
                    Text(if (busy) "Зберігаю…" else "Зберегти день")
                }
                OutlinedButton(onClick = { if (collect() != null) copyDialog = true }, enabled = !busy) { Text("На кілька днів…") }
            }
        }
    }

    if (copyDialog) {
        val chosen = remember { mutableStateListOf(dayIndex) }
        AlertDialog(
            onDismissRequest = { copyDialog = false },
            title = { Text("Записати цей розклад у дні") },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { chosen.clear(); chosen.addAll(0..6) }) { Text("Усі") }
                        TextButton(onClick = { chosen.clear(); chosen.addAll(0..4) }) { Text("Будні") }
                        TextButton(onClick = { chosen.clear(); chosen.addAll(5..6) }) { Text("Вихідні") }
                    }
                    Program.DAY_NAMES.forEachIndexed { i, n ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = i in chosen, onCheckedChange = { c -> if (c) chosen.add(i) else chosen.remove(i) })
                            Text(n)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    collect()?.let { if (chosen.isNotEmpty()) onSave(chosen.sorted(), it) }
                    copyDialog = false
                }) { Text("Записати") }
            },
            dismissButton = { TextButton(onClick = { copyDialog = false }) { Text("Скасувати") } },
        )
    }
}

@Composable
private fun DayBar(slots: List<Slot>, modifier: Modifier) {
    val empty = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        if (slots.isEmpty()) { drawRect(empty); return@Canvas }
        val minT = 7.0
        val maxT = 24.0
        for ((i, s) in slots.withIndex()) {
            val end = slots.getOrNull(i + 1)?.minute ?: (24 * 60)
            val x0 = size.width * s.minute / (24f * 60f)
            val x1 = size.width * end / (24f * 60f)
            val f = ((s.temp - minT) / (maxT - minT)).toFloat().coerceIn(0f, 1f)
            drawRect(lerp(Cool, Warm, f), topLeft = Offset(x0, 0f), size = Size(x1 - x0, size.height))
        }
    }
}

private fun todayIndex(): Int {
    // Calendar: неділя=1 … субота=7 → Пн=0 … Нд=6
    val d = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
    return (d + 5) % 7
}
