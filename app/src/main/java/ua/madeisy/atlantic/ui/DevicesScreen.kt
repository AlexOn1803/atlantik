package ua.madeisy.atlantic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.madeisy.atlantic.core.Heater

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(st: UiState, vm: AppViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Мої пристрої") },
                actions = {
                    IconButton(onClick = { vm.loadDevices() }) { Icon(Icons.Default.Refresh, "Оновити") }
                    IconButton(onClick = { vm.logout() }) { Icon(Icons.AutoMirrored.Filled.ExitToApp, "Вийти") }
                },
            )
        },
        floatingActionButton = {
            if (st.allDevices.isNotEmpty()) ExtendedFloatingActionButton(
                onClick = { vm.openAddHeater() },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Додати обігрівач") },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                st.loadingDevices && st.setups.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                st.allDevices.isEmpty() -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("В акаунті немає пристроїв", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Додайте свій Wi‑Fi обігрівач Atlantic прямо звідси — знадобиться PIN-id з наклейки " +
                            "і пароль від домашнього Wi‑Fi 2.4 ГГц.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { vm.openAddHeater() }) {
                        Icon(Icons.Default.Add, null)
                        Text("  Додати обігрівач")
                    }
                    st.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    st.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                    items(st.allDevices.sortedByDescending { it.second.looksLikeHeater }, key = { it.second.deviceId }) { (setup, d) ->
                        val h = Heater(d)
                        Card(
                            Modifier.fillMaxWidth().clickable { vm.openDevice(d.deviceId) },
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(d.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    listOfNotNull(
                                        setup.name,
                                        h.currentTemp?.let { "в кімнаті ${ua.madeisy.atlantic.core.Catalog.fmt(it)} °C" },
                                        h.target?.let { "ціль ${ua.madeisy.atlantic.core.Catalog.fmt(it)} °C" },
                                        if (h.hasPower && !h.isOn) "вимкнено" else null,
                                    ).joinToString(" · "),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (!d.looksLikeHeater) Text(
                                    "model ${d.modelId} · product ${d.productId}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
