package ua.madeisy.atlantic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ua.madeisy.atlantic.core.Catalog
import ua.madeisy.atlantic.core.Heater
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeaterScreen(st: UiState, vm: AppViewModel) {
    val (setup, device) = st.selected ?: return
    val heater = Heater(device)
    val snack = remember { SnackbarHostState() }
    var tab by remember { mutableIntStateOf(0) }

    LaunchedEffect(st.message) { st.message?.let { snack.showSnackbar(it); vm.consumeMessage() } }
    LaunchedEffect(st.error) { st.error?.let { snack.showSnackbar("⚠ $it"); vm.consumeError() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(device.title, maxLines = 1)
                        if (st.lastUpdate > 0) Text(
                            "оновлено ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(st.lastUpdate))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    if (st.allDevices.size > 1) IconButton(onClick = { vm.closeDevice() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                actions = {
                    if (st.refreshing) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { vm.refreshSelected() }) { Icon(Icons.Default.Refresh, "Оновити") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val tabs = listOf("Керування", "Розклад", "Споживання", "Усі параметри")
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            when (tab) {
                0 -> ControlTab(st, heater, vm)
                1 -> ScheduleTab(st, heater, vm)
                2 -> EnergyTab(st, heater, vm)
                else -> AllParamsTab(st, heater, vm, setupName = setup.name)
            }
        }
    }
}

@Composable
private fun ControlTab(st: UiState, h: Heater, vm: AppViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TemperatureCard(st, h, vm)

        if (h.windowOpen) InfoCard("Виявлено відкрите вікно — обігрівач тимчасово знизив потужність.", warn = true)

        Card {
            Column(Modifier.padding(vertical = 4.dp)) {
                if (h.hasPower) SwitchRow("Обігрівач", if (h.isOn) "Увімкнено" else "Вимкнено", h.isOn, 7 in st.writing) { vm.setPower(it) }
                if (h.hasProgram) {
                    SwitchRow(
                        "Тижнева програма",
                        when {
                            !h.programOn -> "Ручне керування"
                            h.overrideOn -> "Тимчасова зміна температури"
                            else -> "Працює за розкладом"
                        },
                        h.programOn, 184 in st.writing,
                    ) { vm.setProgram(it) }
                    if (h.programOn && h.overrideOn) Row(Modifier.padding(horizontal = 16.dp)) {
                        val left = h.device.cap(159)?.intValue
                        Text(
                            "Тимчасова зміна" + (left?.takeIf { it > 0 }?.let { " ще ${Catalog.duration(it.toLong())}" } ?: ""),
                            Modifier.weight(1f).align(Alignment.CenterVertically),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { vm.cancelOverride() }) { Text("До програми") }
                    }
                }
                if (h.hasBoost) SwitchRow("Boost", "Швидкий нагрів на максимальній потужності", h.boostOn, h.boostId in st.writing) { vm.setBoost(it) }
                if (h.hasEco) SwitchRow("Режим Еко", "Знижена температура для економії", h.ecoOn, 100507 in st.writing) { vm.setSwitch(100507, it) }
                if (h.device.has(104050)) SwitchRow(
                    "Виявлення відкритого вікна", null, h.device.cap(104050)?.isOn == true, 104050 in st.writing,
                ) { vm.setSwitch(104050, it) }
                if (h.device.has(100506)) SwitchRow(
                    "Датчик присутності", null, h.device.cap(100506)?.isOn == true, 100506 in st.writing,
                ) { vm.setSwitch(100506, it) }
                if (h.device.has(100450)) SwitchRow(
                    "Випередження розкладу", "Починати гріти заздалегідь", h.device.cap(100450)?.isOn == true, 100450 in st.writing,
                ) { vm.setSwitch(100450, it) }
            }
        }

        AwayCard(st, h, vm)

        if (h.device.has(100078)) OutlinedButton(onClick = { vm.identify() }, modifier = Modifier.fillMaxWidth()) {
            Text("Знайти прилад (блимнути індикатором)")
        }

        if (!h.device.looksLikeHeater) InfoCard(
            "Цей пристрій не схожий на обігрівач (model ${h.device.modelId}, product ${h.device.productId}). " +
                "Показано лише ті налаштування, які він повідомляє.",
        )
    }
}

@Composable
private fun TemperatureCard(st: UiState, h: Heater, vm: AppViewModel) {
    val serverTarget = h.target
    var pending by remember(serverTarget) { mutableStateOf(serverTarget) }
    var dirtyAt by remember { mutableStateOf(0L) }

    // +/− відправляємо через 1.2 с після останнього натискання
    LaunchedEffect(dirtyAt) {
        if (dirtyAt == 0L) return@LaunchedEffect
        delay(1200)
        pending?.let { if (it != serverTarget) vm.setTarget(it) }
    }

    val heating = h.isHeatingNow && h.isOn
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (heating) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(if (heating) Warm else if (h.isOn) Cool else Color.Gray, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        h.hasPower && !h.isOn -> "Вимкнено"
                        heating -> "Гріє"
                        else -> "Тримає температуру"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("В кімнаті", style = MaterialTheme.typography.bodyMedium)
            Text(
                h.currentTemp?.let { "${Catalog.fmt(it)}°" } ?: "—",
                fontSize = 64.sp, fontWeight = FontWeight.Light,
            )
            if (serverTarget != null) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("Бажана температура", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = { pending = h.clampTarget((pending ?: serverTarget) - h.step); dirtyAt = System.nanoTime() },
                        enabled = h.isOn,
                    ) { Text("−", fontSize = 24.sp) }
                    Text(
                        "${Catalog.fmt(pending ?: serverTarget)}°",
                        Modifier.width(140.dp), textAlign = TextAlign.Center,
                        fontSize = 44.sp, fontWeight = FontWeight.Medium,
                        color = if (pending != serverTarget) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    )
                    FilledTonalIconButton(
                        onClick = { pending = h.clampTarget((pending ?: serverTarget) + h.step); dirtyAt = System.nanoTime() },
                        enabled = h.isOn,
                    ) { Icon(Icons.Default.Add, "Більше") }
                }
                Slider(
                    value = (pending ?: serverTarget).toFloat().coerceIn(h.minTemp.toFloat(), h.maxTemp.toFloat()),
                    onValueChange = { pending = h.clampTarget(it.toDouble()) },
                    onValueChangeFinished = { pending?.let { if (it != serverTarget) vm.setTarget(it) } },
                    valueRange = h.minTemp.toFloat()..h.maxTemp.toFloat(),
                    enabled = h.isOn,
                )
                Row(Modifier.fillMaxWidth()) {
                    Text("${Catalog.fmt(h.minTemp)}°", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    if (40 in st.writing) Text("надсилаю…", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Text("${Catalog.fmt(h.maxTemp)}°", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (preset in listOf(7.0, 16.0, 19.0, 21.0, 23.0)) {
                        if (preset in h.minTemp..h.maxTemp) OutlinedButton(
                            onClick = { pending = preset; vm.setTarget(preset) },
                            enabled = h.isOn,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                        ) { Text(if (preset == 7.0) "❄ 7°" else "${preset.toInt()}°") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AwayCard(st: UiState, h: Heater, vm: AppViewModel) {
    if (h.awaySwitchId == null && h.awayTimestampsId == null) return
    var dialog by remember { mutableStateOf(false) }
    val state = h.awayState
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Відсутність (відпустка)", style = MaterialTheme.typography.titleSmall)
            val window = h.awayTimestampsId?.let { h.device.cap(it)?.value }
            val range = parseWindow(window)
            Text(
                when {
                    state == 1 && range != null -> "Увімкнено до ${fmtDate(range.second)}"
                    state == 2 && range != null -> "Заплановано з ${fmtDate(range.first)} до ${fmtDate(range.second)}"
                    state != 0 -> "Увімкнено"
                    else -> "Вимкнено. Під час відсутності прилад тримає знижену температуру."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            h.device.cap(172)?.doubleValue?.let { Text("Температура відсутності: ${Catalog.fmt(it)} °C", style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { dialog = true }) { Text("Поїхати…") }
                if (state != 0) OutlinedButton(onClick = { vm.setAway(null) }) { Text("Я вдома") }
            }
        }
    }
    if (dialog) {
        var days by remember { mutableStateOf("3") }
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text("На скільки днів?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (d in listOf("1", "3", "7", "14")) OutlinedButton(
                            onClick = { days = d },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                        ) { Text(d) }
                    }
                    OutlinedTextField(value = days, onValueChange = { days = it.filter(Char::isDigit).take(3) }, label = { Text("Днів") }, singleLine = true)
                    Text("Відсутність діє для всього будинку в акаунті Cozytouch.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    days.toIntOrNull()?.takeIf { it in 1..365 }?.let { vm.setAway(it) }
                    dialog = false
                }) { Text("Увімкнути") }
            },
            dismissButton = { TextButton(onClick = { dialog = false }) { Text("Скасувати") } },
        )
    }
}

internal fun parseWindow(v: String?): Pair<Long, Long>? {
    val nums = Regex("-?\\d+").findAll(v ?: return null).map { it.value.toLong() }.toList()
    if (nums.size < 2 || nums[1] <= 0) return null
    return nums[0] to nums[1]
}

internal fun fmtDate(sec: Long): String = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(sec * 1000))

@Composable
internal fun SwitchRow(title: String, subtitle: String?, checked: Boolean, busy: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (busy) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = !busy)
    }
}

@Composable
internal fun InfoCard(text: String, warn: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
        shape = RoundedCornerShape(12.dp),
    ) { Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium) }
}
