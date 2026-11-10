package ua.madeisy.atlantic.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.madeisy.atlantic.core.Capability
import ua.madeisy.atlantic.core.Catalog
import ua.madeisy.atlantic.core.Group
import ua.madeisy.atlantic.core.Heater
import ua.madeisy.atlantic.core.Kind

@Composable
fun AllParamsTab(st: UiState, h: Heater, vm: AppViewModel, setupName: String?) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<Capability?>(null) }
    var showUnknown by remember { mutableStateOf(true) }

    val grouped = h.device.capabilities
        .filter { showUnknown || Catalog.isKnown(it.id) }
        .groupBy { Catalog.info(it.id).group }
        .toSortedMap(compareBy<Group> { it.ordinal })

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Усе, що обігрівач повідомляє в хмару (${h.device.capabilities.size} значень). " +
                        "Натисніть на значення з олівцем ✎, щоб змінити.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Будинок: ${setupName ?: "—"} · deviceId ${h.device.deviceId} · model ${h.device.modelId} · product ${h.device.productId}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Atlantic діагностика ${h.device.modelId}")
                            putExtra(Intent.EXTRA_TEXT, h.dump())
                        }
                        ctx.startActivity(Intent.createChooser(send, "Надіслати діагностику"))
                    }) {
                        Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Діагностика")
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Невідомі", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = showUnknown, onCheckedChange = { showUnknown = it })
                }
            }
        }
        grouped.forEach { (group, caps) ->
            item(key = "g-${group.name}") {
                Text(
                    group.title,
                    Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(caps, key = { "c-${it.id}" }) { c ->
                val info = Catalog.info(c.id)
                val editable = info.writable && info.kind != Kind.PROG_DAY
                Row(
                    Modifier.fillMaxWidth().clickable { editing = c }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(info.title + if (editable) "  ✎" else "", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "#${c.id}" + if (c.id in st.writing) " · записую…" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        Catalog.format(c.id, c.value),
                        Modifier.weight(1f, fill = false),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium,
                        fontFamily = if (Catalog.isKnown(c.id)) null else FontFamily.Monospace,
                    )
                }
                HorizontalDivider(Modifier.padding(start = 16.dp))
            }
        }
    }

    editing?.let { c -> EditDialog(c, h, onDismiss = { editing = null }, vm = vm) }
}

@Composable
private fun EditDialog(c: Capability, h: Heater, onDismiss: () -> Unit, vm: AppViewModel) {
    val info = Catalog.info(c.id)
    var rawMode by remember { mutableStateOf(!info.writable || info.kind == Kind.PROG_DAY) }
    var text by remember { mutableStateOf(c.value ?: "") }
    var choice by remember { mutableStateOf(c.value?.let { v -> v.toDoubleOrNull()?.toLong()?.toString() ?: v } ?: "") }
    var confirmRaw by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(info.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Номер параметра: ${c.id}", style = MaterialTheme.typography.bodySmall)
                Text("Поточне значення: ${c.value ?: "—"}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                if (!rawMode) when (info.kind) {
                    Kind.SWITCH -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Увімкнено", Modifier.weight(1f))
                        Switch(checked = choice != "0" && choice.isNotEmpty(), onCheckedChange = { choice = if (it) "1" else "0" })
                    }
                    Kind.ENUM -> info.values.forEach { (v, label) ->
                        Row(Modifier.fillMaxWidth().clickable { choice = v }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = choice == v, onClick = { choice = v })
                            Text(label)
                        }
                    }
                    Kind.SETPOINT, Kind.MINUTES -> {
                        OutlinedTextField(
                            value = text, onValueChange = { text = it.replace(',', '.') },
                            label = { Text(if (info.kind == Kind.MINUTES) "Хвилини" else "°C") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                        )
                        val range = if (info.kind == Kind.SETPOINT) "${Catalog.fmt(h.minTemp)}…${Catalog.fmt(h.maxTemp)} °C"
                        else listOfNotNull(info.min, info.max).joinToString("…") { it.toLong().toString() } + " хв"
                        Text("Діапазон: $range", style = MaterialTheme.typography.bodySmall)
                    }
                    Kind.ACTION -> Text("Надіслати команду на прилад?")
                    else -> Unit
                }
                if (rawMode) {
                    Text(
                        if (info.writable) "Ручний запис сирого значення."
                        else "Цей параметр не позначений як змінюваний. Ручний запис — на ваш ризик: " +
                            "прилад може відхилити його або змінити поведінку.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Значення") }, singleLine = false)
                } else {
                    TextButton(onClick = { rawMode = true }) { Text("Ручне значення…") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = click@{
                if (rawMode) {
                    if (!info.writable && !confirmRaw) { confirmRaw = true; return@click }
                    if (text.isNotBlank()) vm.writeRaw(c.id, text.trim())
                } else when (info.kind) {
                    Kind.SWITCH -> vm.setSwitch(c.id, choice == "1")
                    Kind.ENUM -> if (choice.isNotEmpty()) {
                        if (c.id == 7) vm.setPower(choice != "0") else vm.writeRaw(c.id, choice)
                    }
                    Kind.SETPOINT -> text.toDoubleOrNull()?.let { v ->
                        if (c.id == 40) vm.setTarget(v) else vm.writeRaw(c.id, String.format(java.util.Locale.US, "%.1f", v))
                    }
                    Kind.MINUTES -> text.toDoubleOrNull()?.let { v ->
                        val clamped = v.coerceIn(info.min ?: 0.0, info.max ?: 100000.0).toLong()
                        vm.writeRaw(c.id, clamped.toString())
                    }
                    Kind.ACTION -> if (c.id == 100078) vm.identify() else vm.writeRaw(c.id, "1")
                    else -> Unit
                }
                onDismiss()
            }) {
                Text(if (rawMode && !info.writable && !confirmRaw) "Продовжити" else if (rawMode && confirmRaw) "Так, записати" else "Записати")
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}
