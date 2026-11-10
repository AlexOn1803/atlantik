package ua.madeisy.atlantic.core

/** Як показувати і чи можна змінювати значення. */
enum class Kind {
    SETPOINT,   // записувана температура °C
    TEMP,       // температура лише для читання
    SWITCH,     // 0/1, записуваний
    FLAG,       // 0/1, лише читання
    ENUM,       // перелік значень
    MINUTES,    // записувані хвилини
    SECONDS,    // тривалість у секундах (читання)
    ENERGY_WH,  // лічильник у Вт·год
    POWER_W,
    PERCENT,
    PROG_DAY,   // тижнева програма: [[хвилина, °C], ...]
    AWAY_WINDOW,// "[start,end]" Unix-секунди
    ACTION,     // кнопка (напр. «підсвітити прилад»)
    TEXT,
}

data class CapInfo(
    val id: Int,
    val title: String,
    val kind: Kind,
    val writable: Boolean = false,
    val values: Map<String, String> = emptyMap(),
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val group: Group = Group.OTHER,
)

enum class Group(val title: String) {
    MAIN("Основне"),
    COMFORT("Комфорт і економія"),
    BOOST("Boost"),
    WINDOW("Вікно і присутність"),
    AWAY("Відсутність"),
    PROGRAM("Програма"),
    ENERGY("Споживання"),
    LIMITS("Межі та кроки"),
    NETWORK("Зв'язок"),
    SERVICE("Сервіс і помилки"),
    OTHER("Інше"),
}

/**
 * Каталог значень Magellan, що стосуються електричних обігрівачів (BD0/BD1 Wi‑Fi,
 * рушникосушки, панелі). Номери й значення взято з інтеграції gduteil/cozytouch.
 * Пристрій сам повідомляє, які номери в нього є, — показуємо тільки їх.
 */
object Catalog {

    private val HEATING_STATUS = mapOf("0" to "Не гріє", "1" to "Гріє", "2" to "Охолоджує")
    private val ON_OFF = mapOf("0" to "Вимкнено", "1" to "Увімкнено")
    private val AWAY_STATE = mapOf("0" to "Вимкнено", "1" to "Увімкнено", "2" to "Заплановано")
    private val PROFILE = mapOf(
        "0" to "Стоп", "1" to "Комфорт", "2" to "Еко", "3" to "Зовнішня уставка",
        "4" to "Відсутність", "5" to "Ручний", "6" to "Антизамерзання", "7" to "Тимчасова зміна",
    )

    /** Режим роботи (id 7) для обігрівача: 0 — вимкнено, 4 — обігрів. */
    val HEATER_MODES = mapOf("0" to "Вимкнено", "4" to "Обігрів")

    private val list = listOf(
        // Основне
        CapInfo(7, "Режим роботи", Kind.ENUM, writable = true, values = HEATER_MODES, group = Group.MAIN),
        CapInfo(40, "Бажана температура", Kind.SETPOINT, writable = true, group = Group.MAIN),
        CapInfo(117, "Температура в кімнаті", Kind.TEMP, group = Group.MAIN),
        CapInfo(103150, "Датчик температури доступний", Kind.FLAG, group = Group.MAIN),
        CapInfo(153, "Стан нагріву", Kind.ENUM, values = HEATING_STATUS, group = Group.MAIN),
        CapInfo(181, "Фактичний режим", Kind.ENUM, values = mapOf("0" to "Вимкнено", "1" to "Авто", "4" to "Обігрів"), group = Group.MAIN),
        CapInfo(17, "Діюча уставка", Kind.TEMP, group = Group.MAIN),
        CapInfo(19, "Уставка температури", Kind.TEMP, group = Group.MAIN),
        CapInfo(3, "Поточний профіль", Kind.ENUM, values = PROFILE, group = Group.MAIN),

        // Комфорт і економія
        CapInfo(100507, "Режим Еко", Kind.SWITCH, writable = true, values = ON_OFF, group = Group.COMFORT),
        CapInfo(41, "Температура Еко", Kind.SETPOINT, writable = true, group = Group.COMFORT),
        CapInfo(58773, "Температура Комфорт", Kind.SETPOINT, writable = true, group = Group.COMFORT),
        CapInfo(103199, "Температура антизамерзання", Kind.TEMP, group = Group.COMFORT),
        CapInfo(100100, "Запитаний комфорт", Kind.TEXT, group = Group.COMFORT),

        // Boost
        CapInfo(104044, "Boost", Kind.SWITCH, writable = true, values = ON_OFF, group = Group.BOOST),
        CapInfo(100505, "Посилений режим", Kind.SWITCH, writable = true, values = ON_OFF, group = Group.BOOST),
        CapInfo(104047, "Тривалість Boost (макс.)", Kind.MINUTES, writable = true, min = 5.0, max = 60.0, step = 5.0, group = Group.BOOST),
        CapInfo(103115, "Тривалість Boost", Kind.SECONDS, group = Group.BOOST),
        CapInfo(103116, "Макс. тривалість Boost", Kind.SECONDS, group = Group.BOOST),
        CapInfo(232, "Boost: загальний час", Kind.SECONDS, group = Group.BOOST),
        CapInfo(233, "Boost: залишилось", Kind.SECONDS, group = Group.BOOST),
        CapInfo(234, "Boost: температура", Kind.TEMP, group = Group.BOOST),
        CapInfo(360, "Boost: кінець", Kind.TEXT, group = Group.BOOST),

        // Вікно і присутність
        CapInfo(104050, "Виявлення відкритого вікна", Kind.SWITCH, writable = true, values = ON_OFF, group = Group.WINDOW),
        CapInfo(104051, "Вікно відкрите зараз", Kind.FLAG, group = Group.WINDOW),
        CapInfo(100506, "Датчик присутності", Kind.SWITCH, writable = true, values = ON_OFF, group = Group.WINDOW),
        CapInfo(100078, "Підсвітити прилад (знайти)", Kind.ACTION, writable = true, group = Group.WINDOW),

        // Відсутність
        CapInfo(152, "Відсутність", Kind.ENUM, values = AWAY_STATE, group = Group.AWAY),
        CapInfo(227, "Відсутність", Kind.ENUM, values = AWAY_STATE, group = Group.AWAY),
        CapInfo(100261, "Відсутність", Kind.ENUM, values = AWAY_STATE, group = Group.AWAY),
        CapInfo(172, "Температура під час відсутності", Kind.SETPOINT, writable = true, group = Group.AWAY),
        CapInfo(222, "Період відсутності", Kind.AWAY_WINDOW, group = Group.AWAY),
        CapInfo(226, "Період відсутності", Kind.AWAY_WINDOW, group = Group.AWAY),
        CapInfo(100260, "Період відсутності", Kind.AWAY_WINDOW, group = Group.AWAY),

        // Програма
        CapInfo(184, "Тижнева програма", Kind.SWITCH, writable = true, values = ON_OFF, group = Group.PROGRAM),
        CapInfo(157, "Тимчасова зміна температури", Kind.SWITCH, writable = true, values = ON_OFF, group = Group.PROGRAM),
        CapInfo(158, "Тривалість тимчасової зміни", Kind.MINUTES, writable = true, min = 60.0, max = 1440.0, step = 60.0, group = Group.PROGRAM),
        CapInfo(159, "Тимчасова зміна: залишилось", Kind.SECONDS, group = Group.PROGRAM),
        CapInfo(176, "Програма доступна", Kind.FLAG, group = Group.PROGRAM),
        CapInfo(244, "Макс. інтервалів на день", Kind.TEXT, group = Group.PROGRAM),
        CapInfo(306, "Макс. змін на день", Kind.TEXT, group = Group.PROGRAM),
        CapInfo(295, "Крок розкладу", Kind.TEXT, group = Group.PROGRAM),
        CapInfo(100450, "Випередження розкладу", Kind.SWITCH, writable = true, values = ON_OFF, group = Group.PROGRAM),
        CapInfo(100102, "Адаптивне планування", Kind.FLAG, group = Group.PROGRAM),

        // Споживання
        CapInfo(57, "Спожито (обігрів)", Kind.ENERGY_WH, group = Group.ENERGY),
        CapInfo(59, "Спожито", Kind.ENERGY_WH, group = Group.ENERGY),
        CapInfo(60, "Спожито всього", Kind.ENERGY_WH, group = Group.ENERGY),
        CapInfo(298, "Загальне споживання", Kind.ENERGY_WH, group = Group.ENERGY),
        CapInfo(106, "Номінальна потужність", Kind.POWER_W, group = Group.ENERGY),
        CapInfo(104025, "Макс. потужність зони", Kind.POWER_W, group = Group.ENERGY),
        CapInfo(164, "Підтримка обліку енергії", Kind.TEXT, group = Group.ENERGY),

        // Межі
        CapInfo(160, "Мінімальна температура", Kind.TEMP, group = Group.LIMITS),
        CapInfo(161, "Максимальна температура", Kind.TEMP, group = Group.LIMITS),
        CapInfo(294, "Крок температури", Kind.TEXT, group = Group.LIMITS),
        CapInfo(252, "Макс. уставка", Kind.TEMP, group = Group.LIMITS),
        CapInfo(253, "Мін. уставка", Kind.TEMP, group = Group.LIMITS),

        // Зв'язок
        CapInfo(179, "Сигнал Wi‑Fi", Kind.PERCENT, group = Group.NETWORK),
        CapInfo(169, "Радіосигнал", Kind.PERCENT, group = Group.NETWORK),
        CapInfo(170, "Якість сигналу", Kind.TEXT, group = Group.NETWORK),
        CapInfo(219, "Мережа Wi‑Fi (SSID)", Kind.TEXT, group = Group.NETWORK),
        CapInfo(218, "Діагностика зв'язку", Kind.TEXT, group = Group.NETWORK),
        CapInfo(100503, "Прошивка Wi‑Fi", Kind.TEXT, group = Group.NETWORK),
        CapInfo(316, "Прошивка інтерфейсу", Kind.TEXT, group = Group.NETWORK),
        CapInfo(315, "Часовий пояс", Kind.TEXT, group = Group.NETWORK),
        CapInfo(304, "Час синхронізовано", Kind.FLAG, group = Group.NETWORK),

        // Сервіс
        CapInfo(150, "Код помилки", Kind.TEXT, group = Group.SERVICE),
        CapInfo(303, "Код помилки", Kind.TEXT, group = Group.SERVICE),
        CapInfo(182, "Активні помилки", Kind.TEXT, group = Group.SERVICE),
        CapInfo(328, "Основна помилка", Kind.TEXT, group = Group.SERVICE),
        CapInfo(88, "Модель", Kind.TEXT, group = Group.SERVICE),
        CapInfo(94, "Номер виробу", Kind.TEXT, group = Group.SERVICE),
        CapInfo(98, "Номер виробу", Kind.TEXT, group = Group.SERVICE),
        CapInfo(120, "Тип виробу", Kind.TEXT, group = Group.SERVICE),
        CapInfo(121, "Версія", Kind.TEXT, group = Group.SERVICE),
        CapInfo(335, "Серійний номер", Kind.TEXT, group = Group.SERVICE),
        CapInfo(166, "Дозволені режими (маска)", Kind.TEXT, group = Group.SERVICE),
        CapInfo(100022, "Підтримувані режими (маска)", Kind.TEXT, group = Group.SERVICE),
        CapInfo(359, "Тип генератора тепла", Kind.TEXT, group = Group.SERVICE),
    ) + (0..6).flatMap { d ->
        listOf(
            CapInfo(196 + d, "Програма: ${Program.DAY_NAMES[d]}", Kind.PROG_DAY, writable = true, group = Group.PROGRAM),
            CapInfo(100320 + d, "Програма: ${Program.DAY_NAMES[d]}", Kind.PROG_DAY, writable = true, group = Group.PROGRAM),
        )
    }

    private val byId: Map<Int, CapInfo> = list.associateBy { it.id }

    fun info(id: Int): CapInfo = byId[id] ?: CapInfo(id, "Параметр $id", Kind.TEXT, writable = false)

    fun isKnown(id: Int) = byId.containsKey(id)

    /** Людське представлення значення. */
    fun format(id: Int, value: String?): String {
        if (value == null) return "—"
        val i = info(id)
        val d = value.asDoubleOrNull()
        return when (i.kind) {
            Kind.SETPOINT, Kind.TEMP -> d?.let { if (it <= -300) "немає датчика" else "${fmt(it)} °C" } ?: value
            Kind.SWITCH, Kind.FLAG -> if ((d ?: 0.0) != 0.0) "Так" else "Ні"
            Kind.ENUM -> i.values[d?.toLong()?.toString() ?: value] ?: value
            Kind.MINUTES -> d?.let { "${it.toLong()} хв" } ?: value
            Kind.SECONDS -> d?.let { duration(it.toLong()) } ?: value
            Kind.ENERGY_WH -> d?.let { "${fmt(it / 1000.0, 2)} кВт·год" } ?: value
            Kind.POWER_W -> d?.let { "${it.toLong()} Вт" } ?: value
            Kind.PERCENT -> d?.let { "${it.toLong()} %" } ?: value
            Kind.PROG_DAY -> Program.parseOrNull(value)?.let { Program.summary(it) } ?: value
            Kind.AWAY_WINDOW -> value
            Kind.ACTION -> "—"
            Kind.TEXT -> value
        }
    }

    fun fmt(v: Double, decimals: Int = 1): String {
        val s = String.format(java.util.Locale.US, "%.${decimals}f", v)
        return s.trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }

    fun duration(sec: Long): String {
        if (sec <= 0) return "0"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return when {
            h > 0 && m > 0 -> "$h год $m хв"
            h > 0 -> "$h год"
            m > 0 -> "$m хв"
            else -> "$sec с"
        }
    }
}
