package ua.madeisy.atlantic.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ua.madeisy.atlantic.core.PairStep
import ua.madeisy.atlantic.core.PairingCodes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddHeaterScreen(st: UiState, vm: AppViewModel) {
    val p = st.pair
    // поля форми живуть тут, щоб «Спробувати ще» не стирало введене
    val code = rememberSaveable { mutableStateOf("") }
    val bd1 = rememberSaveable { mutableStateOf(true) }
    val ssid = rememberSaveable { mutableStateOf("") }
    val password = rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = !p.running) { vm.closeAddHeater() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Додати обігрівач") },
                navigationIcon = {
                    IconButton(onClick = { vm.closeAddHeater() }, enabled = !p.running) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (p.running || p.success || p.step != null) Progress(p, vm)
            else Form(st, vm, bd1, code, ssid, password)
        }
    }
}

@Composable
private fun Form(
    st: UiState,
    vm: AppViewModel,
    bd1State: androidx.compose.runtime.MutableState<Boolean>,
    codeState: androidx.compose.runtime.MutableState<String>,
    ssidState: androidx.compose.runtime.MutableState<String>,
    passwordState: androidx.compose.runtime.MutableState<String>,
) {
    val ctx = LocalContext.current
    var bd1 by bd1State
    var code by codeState
    var ssid by ssidState
    var password by passwordState
    var show by remember { mutableStateOf(false) }

    val needed = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) vm.startPairing(bd1, code, ssid, password)
        else vm.startPairing(bd1, code, ssid, password) // спробуємо й так — на багатьох телефонах діалог Wi‑Fi працює без дозволу
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("1. Увімкніть на обігрівачі режим підключення", style = MaterialTheme.typography.titleSmall)
            Text(
                "На обігрівачі: Меню → Зв'язок (Connectivity) → Підключення (Connection). " +
                    "У списку Wi‑Fi телефона має з'явитися мережа ELEC_HEATER_Wifi_xxxx. Пароль їй не потрібен " +
                    "(якщо з замком — WIFIHVAC), застосунок підключиться сам. Тримайте телефон поруч.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("2. Яка мережа з'явилася у списку Wi‑Fi?", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(selected = bd1, onClick = { bd1 = true })
                Column(Modifier.weight(1f)) {
                    Text("ELEC_HEATER_Wifi_xxxx", fontFamily = FontFamily.Monospace)
                    Text("CHG-BD1/Wi‑Fi (ALTIS). PIN не потрібен — прилад повідомить його сам.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(selected = !bd1, onClick = { bd1 = false })
                Column(Modifier.weight(1f)) {
                    Text("GA-xxxxxxxxxxxx", fontFamily = FontFamily.Monospace)
                    Text("BD0 Wi‑Fi. Потрібен PIN-id — це 12 цифр із назви мережі.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(
                value = code, onValueChange = { code = it.take(200) },
                label = { Text(if (bd1) "PIN-id (необов'язково)" else "PIN-id або текст QR") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (!bd1) {
                val ap = PairingCodes.fromQr(code) ?: runCatching { PairingCodes.fromPin(code) }.getOrNull()
                if (ap != null) Text("Мережа обігрівача: ${ap.ssid}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("3. Ваш домашній Wi‑Fi", style = MaterialTheme.typography.titleSmall)
            Text(
                "Лише 2.4 ГГц (обігрівач не бачить 5 ГГц). Назву вводьте точно, з урахуванням регістру.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = ssid, onValueChange = { ssid = it }, label = { Text("Назва мережі") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it }, label = { Text("Пароль") }, singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Сховати" else "Показати") } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    st.pair.error?.let { InfoCard(it, warn = true) }

    Button(
        onClick = {
            val missing = needed.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) vm.startPairing(bd1, code, ssid, password) else launcher.launch(missing.toTypedArray())
        },
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text("Підключити обігрівач") }

    Text(
        "Під час підключення Android покаже мережу обігрівача і запитає дозвіл — оберіть її і натисніть «Підключити». " +
            "Телефон на хвилину втратить інтернет через Wi‑Fi, це нормально.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Progress(p: PairUi, vm: AppViewModel) {
    var showLog by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (s in p.order) {
                val state = when {
                    s in p.done -> 2
                    s == p.step && p.error != null -> 3
                    s == p.step -> 1
                    else -> 0
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (state) {
                        2 -> Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                        3 -> Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error)
                        1 -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else -> Spacer(Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        s.title,
                        fontWeight = if (state == 1) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (state == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }

    when {
        p.success -> {
            InfoCard("Готово! Обігрівач доданий до вашого акаунта.")
            Button(onClick = { vm.finishPairing() }, modifier = Modifier.fillMaxWidth()) { Text("Відкрити обігрівач") }
        }
        p.error != null -> {
            InfoCard(p.error, warn = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.openAddHeater() }) { Text("Спробувати ще") }
                OutlinedButton(onClick = { vm.finishPairing() }) { Text("До списку") }
            }
        }
        else -> Text(
            "Не закривайте застосунок. Усе разом займає 1–4 хвилини.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Сховати журнал" else "Показати журнал") }
    if (showLog) Card {
        Text(
            p.log.joinToString("\n"),
            Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
        )
    }
}
