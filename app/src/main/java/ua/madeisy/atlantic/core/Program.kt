package ua.madeisy.atlantic.core

/** Один інтервал денної програми: з [minute] (від 00:00) тримати [temp] °C до наступного інтервалу. */
data class Slot(val minute: Int, val temp: Double) {
    val time: String get() = "%02d:%02d".format(minute / 60, minute % 60)
}

/**
 * Денна програма обігрівача. Пристрій зберігає її рядком "[[хвилина,°C],...]",
 * перший інтервал завжди з 00:00 (хвилина 0).
 */
object Program {
    val DAY_NAMES = listOf("Понеділок", "Вівторок", "Середа", "Четвер", "П'ятниця", "Субота", "Неділя")
    val DAY_SHORT = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд")

    /** Перший id блоку днів: 196..202 (класичний) або 100320..100326 (новіші пристрої). */
    fun dayBlockStart(device: Device): Int? = listOf(196, 100320).firstOrNull { start ->
        (0..6).all { device.has(start + it) }
    }

    fun parseOrNull(value: String?): List<Slot>? = runCatching { parse(value ?: return null) }.getOrNull()

    fun parse(value: String): List<Slot> {
        val arr = Json.parse(value).asList()
        return arr.map { e ->
            val pair = e.asList()
            require(pair.size >= 2) { "Невірний інтервал: $e" }
            Slot(pair[0].asLongOrNull()!!.toInt(), pair[1].asDoubleOrNull()!!)
        }.sortedBy { it.minute }
    }

    /** Перевіряє і перетворює в рядок для запису. Кидає IllegalArgumentException з поясненням. */
    fun encode(slots: List<Slot>, maxSlots: Int = 10): String {
        require(slots.isNotEmpty()) { "Програма не може бути порожньою" }
        val sorted = slots.sortedBy { it.minute }
        require(sorted.first().minute == 0) { "Перший інтервал має починатися о 00:00" }
        require(sorted.map { it.minute }.toSet().size == sorted.size) { "Два інтервали з однаковим часом" }
        require(sorted.all { it.minute in 0 until 24 * 60 }) { "Час має бути між 00:00 і 23:59" }
        require(sorted.size <= maxSlots) { "Не більше $maxSlots інтервалів на день" }
        require(sorted.all { it.temp in 5.0..35.0 }) { "Температура має бути від 5 до 35 °C" }
        return Json.stringify(sorted.map { listOf(it.minute, wholeOrHalf(it.temp)) })
    }

    private fun wholeOrHalf(t: Double): Any = if (t == Math.floor(t)) t.toLong() else t

    fun summary(slots: List<Slot>): String =
        slots.joinToString("  ") { "${it.time}→${Catalog.fmt(it.temp)}°" }

    fun parseTime(text: String): Int? {
        val m = Regex("""^\s*(\d{1,2})[:.](\d{2})\s*$""").find(text) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h !in 0..23 || min !in 0..59) return null
        return h * 60 + min
    }
}
