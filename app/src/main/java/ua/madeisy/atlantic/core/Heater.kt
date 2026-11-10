package ua.madeisy.atlantic.core

/**
 * Зручний погляд на обігрівач поверх сирих значень і послідовності запису,
 * як їх робить офіційний застосунок.
 */
class Heater(val device: Device) {

    val currentTemp: Double? get() {
        val available = device.cap(103150)?.let { it.isOn } ?: true
        val t = device.cap(117)?.doubleValue ?: return null
        return if (!available || t <= -300) null else t
    }
    val target: Double? get() = device.cap(40)?.doubleValue
    val minTemp: Double get() = device.cap(160)?.doubleValue?.takeIf { it > 0 } ?: 7.0
    val maxTemp: Double get() = device.cap(161)?.doubleValue?.takeIf { it > minTemp } ?: 28.0
    val step: Double get() = device.cap(294)?.doubleValue?.takeIf { it in 0.1..5.0 } ?: 0.5

    val hasPower get() = device.has(7)
    val isOn: Boolean get() = device.cap(7)?.let { (it.intValue ?: 0) != 0 } ?: true
    val isHeatingNow: Boolean get() = device.cap(153)?.intValue == 1

    val hasProgram get() = device.has(184)
    val programOn: Boolean get() = device.cap(184)?.isOn == true
    val hasOverride get() = device.has(157)
    val overrideOn: Boolean get() = device.cap(157)?.isOn == true

    val hasBoost get() = device.has(104044) || device.has(100505)
    val boostId: Int get() = if (device.has(104044)) 104044 else 100505
    val boostOn: Boolean get() = device.cap(boostId)?.isOn == true

    val hasEco get() = device.has(100507)
    val ecoOn get() = device.cap(100507)?.isOn == true

    val windowOpen get() = device.cap(104051)?.isOn == true

    val awaySwitchId: Int? get() = listOf(152, 227, 100261).firstOrNull { device.has(it) }
    val awayTimestampsId: Int? get() = listOf(222, 226, 100260).firstOrNull { device.has(it) }
    val awayState: Int get() = awaySwitchId?.let { device.cap(it)?.intValue } ?: 0

    val programDayStart: Int? get() = Program.dayBlockStart(device)
    val maxSlotsPerDay: Int get() =
        (device.cap(244)?.intValue ?: device.cap(306)?.intValue)?.takeIf { it in 2..20 } ?: 10

    /** Нова бажана температура, обмежена межами пристрою і округлена до його кроку. */
    fun clampTarget(t: Double): Double {
        val s = step
        val r = Math.round(t / s) * s
        return r.coerceIn(minTemp, maxTemp)
    }

    // ───── Команди (блокуючі) ─────

    fun setTarget(api: CozyApi, t: Double) {
        val v = clampTarget(t)
        // Якщо діє програма — як і офіційний застосунок, вмикаємо тимчасову зміну
        if (programOn && hasOverride && !overrideOn) {
            device.cap(158)?.value?.let { total -> api.writeCapability(device.deviceId, 158, total) }
            api.writeCapability(device.deviceId, 157, "1")
        }
        api.writeCapability(device.deviceId, 40, String.format(java.util.Locale.US, "%.1f", v))
    }

    fun setPower(api: CozyApi, on: Boolean) =
        api.writeCapability(device.deviceId, 7, if (on) "4" else "0")

    fun setProgram(api: CozyApi, on: Boolean) {
        if (!on && hasOverride && overrideOn) api.writeCapability(device.deviceId, 157, "0")
        api.writeCapability(device.deviceId, 184, if (on) "1" else "0")
    }

    /** Повернутись до програми, скасувавши тимчасову зміну. */
    fun cancelOverride(api: CozyApi) {
        if (device.has(159)) runCatching { api.writeCapability(device.deviceId, 159, "0") }
        api.writeCapability(device.deviceId, 157, "0")
    }

    fun setBoost(api: CozyApi, on: Boolean) =
        api.writeCapability(device.deviceId, boostId, if (on) "1" else "0")

    fun setSwitch(api: CozyApi, capId: Int, on: Boolean) =
        api.writeCapability(device.deviceId, capId, if (on) "1" else "0")

    fun writeDay(api: CozyApi, dayIndex: Int, slots: List<Slot>) {
        val start = programDayStart ?: throw CozyException("Цей пристрій не повідомляє тижневу програму")
        api.writeCapability(device.deviceId, start + dayIndex, Program.encode(slots, maxSlotsPerDay))
    }

    /** Відсутність задається на весь будинок (setup), а прилади її віддзеркалюють. */
    fun setAway(api: CozyApi, setup: Setup, startSec: Long?, endSec: Long?, nowSec: Long) {
        api.setAbsence(setup, startSec, endSec)
        val away = startSec != null && endSec != null
        awayTimestampsId?.let {
            runCatching { api.writeCapability(device.deviceId, it, if (away) "[$startSec,$endSec]" else "[0,0]") }
        }
        awaySwitchId?.let {
            val state = when {
                !away -> "0"
                startSec!! > nowSec -> "2"
                else -> "1"
            }
            runCatching { api.writeCapability(device.deviceId, it, state) }
        }
    }

    /** Текстовий звіт усіх значень — щоб надіслати розробнику і уточнити відповідність. */
    fun dump(): String = buildString {
        appendLine("Atlantic Heater — діагностика")
        appendLine("deviceId=${device.deviceId} modelId=${device.modelId} productId=${device.productId} family=${device.modelFamily}")
        appendLine("name=${device.name}")
        for (c in device.capabilities) {
            appendLine("${c.id}\t${c.value}\t${Catalog.info(c.id).title}")
        }
    }
}
