package ua.madeisy.atlantic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.madeisy.atlantic.core.Catalog
import ua.madeisy.atlantic.core.Heater
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EnergyTab(st: UiState, h: Heater, vm: AppViewModel) {
    LaunchedEffect(h.device.deviceId) { if (st.consumptions == null) vm.loadConsumptions() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val counters = listOf(57, 59, 60, 298, 106).mapNotNull { id -> h.device.cap(id)?.let { id to it } }
        if (counters.isNotEmpty()) Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Лічильники приладу", style = MaterialTheme.typography.titleSmall)
                counters.forEach { (id, c) ->
                    Row {
                        Text(Catalog.info(id).title, Modifier.weight(1f))
                        Text(Catalog.format(id, c.value), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        val days = st.consumptions
        when {
            days == null -> CircularProgressIndicator()
            days.isEmpty() -> InfoCard("Хмара Atlantic не повертає добового споживання для цього будинку.")
            else -> {
                val last = days.takeLast(30)
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Споживання за днями (оцінка Atlantic)", style = MaterialTheme.typography.titleSmall)
                        val total = last.sumOf { it.kwh }
                        val cost = last.mapNotNull { it.cost }.takeIf { it.isNotEmpty() }?.sum()
                        Text(
                            "За ${last.size} дн.: ${Catalog.fmt(total, 2)} кВт·год" + (cost?.let { " · ${Catalog.fmt(it, 2)} (вартість у валюті акаунта)" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Bars(last.map { it.kwh }, Modifier.fillMaxWidth().height(140.dp))
                        Row(Modifier.fillMaxWidth()) {
                            Text(fmtDay(last.first().dateEpochSec), style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.weight(1f))
                            Text(fmtDay(last.last().dateEpochSec), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        last.reversed().take(14).forEach { d ->
                            Row {
                                Text(fmtDay(d.dateEpochSec), Modifier.weight(1f))
                                Text("${Catalog.fmt(d.kwh, 2)} кВт·год")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Bars(values: List<Double>, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(0.1)
        val w = size.width / values.size
        values.forEachIndexed { i, v ->
            val bh = (size.height * (v / max)).toFloat()
            drawRoundRect(
                color,
                topLeft = Offset(i * w + w * 0.15f, size.height - bh),
                size = Size(w * 0.7f, bh),
                cornerRadius = CornerRadius(3f, 3f),
            )
        }
    }
}

private fun fmtDay(sec: Long) = SimpleDateFormat("dd.MM", Locale.getDefault()).format(Date(sec * 1000))
