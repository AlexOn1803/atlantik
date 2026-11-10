package ua.madeisy.atlantic.core

/** Одне значення пристрою (Magellan capability). */
data class Capability(
    val id: Int,
    val value: String?,
    val raw: Map<String, Any?> = emptyMap(),
) {
    val doubleValue: Double? get() = value.asDoubleOrNull()
    val intValue: Int? get() = value.asDoubleOrNull()?.toInt()
    val isOn: Boolean get() = (intValue ?: 0) != 0
}

data class Device(
    val deviceId: Long,
    val modelId: Int?,
    val productId: Int?,
    val name: String,
    val zoneName: String?,
    val modelFamily: String?,
    val capabilities: List<Capability>,
    val raw: Map<String, Any?>,
) {
    fun cap(id: Int): Capability? = capabilities.firstOrNull { it.id == id }
    fun has(id: Int): Boolean = cap(id) != null

    /** productId 41 = BD0/BD1 Wi‑Fi конвектор у класифікації Atlantic; також визначаємо за набором значень. */
    val looksLikeHeater: Boolean
        get() = productId == 41 || productId == 53 || productId == 64 || productId == 121 ||
            modelFamily == "Radiator" || (has(40) && has(117) && !has(22) && !has(111))

    val title: String get() = listOfNotNull(name.ifBlank { null }, zoneName?.takeIf { it != name }).joinToString(" · ")
        .ifBlank { "Пристрій $deviceId" }
}

data class Setup(
    val id: String,
    val name: String?,
    val devices: List<Device>,
    val raw: Map<String, Any?>,
)

class CozyException(message: String, val httpCode: Int? = null, cause: Throwable? = null) : Exception(message, cause)

internal fun parseCapabilities(list: Any?): List<Capability> =
    list.asList().mapNotNull { item ->
        val m = item.asMap()
        val id = m["capabilityId"].asLongOrNull()?.toInt() ?: return@mapNotNull null
        Capability(id, m["value"].asStringOrNull(), m)
    }.sortedBy { it.id }

internal fun parseSetup(raw: Map<String, Any?>): Setup {
    val zones = raw["zones"].asList().map { it.asMap() }
    val zoneNames = zones.associate { (it["id"].asStringOrNull() ?: "") to (it["name"].asStringOrNull() ?: "") }
    val devices = raw["devices"].asList().mapNotNull { d ->
        val m = d.asMap()
        val id = m["deviceId"].asLongOrNull() ?: return@mapNotNull null
        Device(
            deviceId = id,
            modelId = m["modelId"].asLongOrNull()?.toInt(),
            productId = m["productId"].asLongOrNull()?.toInt(),
            name = m["name"].asStringOrNull() ?: "",
            zoneName = m["zoneId"].asStringOrNull()?.let { zoneNames[it] }?.ifBlank { null },
            modelFamily = m["modelFamily"].asStringOrNull()
                ?: m["productModel"].asMap()["modelFamily"].asStringOrNull(),
            capabilities = parseCapabilities(m["capabilities"]),
            raw = m,
        )
    }
    return Setup(
        id = raw["id"].asStringOrNull() ?: "",
        name = raw["name"].asStringOrNull(),
        devices = devices,
        raw = raw,
    )
}

/** Добова споживання з /magellan/setups/{id}/consumptions. */
data class ConsumptionDay(val dateEpochSec: Long, val kwh: Double, val cost: Double?)
