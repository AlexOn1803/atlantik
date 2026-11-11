package ua.madeisy.atlantic.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Дані для підключення до точки доступу обігрівача BD0/BD1 Wi‑Fi.
 * Логіка повторює офіційний Cozytouch (PairingConnectionViewModel / QRCodeContent).
 */
data class DeviceAp(val ssid: String, val password: String, val serial: String)

object PairingCodes {
    /**
     * Можливі PIN-id з сирого значення модуля. Деякі модулі BD1 віддають перший символ
     * зіпсованим (напр. 0x16 + 11 цифр) — тоді повертаємо 10 варіантів першої цифри.
     */
    fun pinCandidates(raw: String?): List<String> {
        if (raw == null) return emptyList()
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.length == 12 -> listOf(digits)
            digits.length == 11 -> {
                val firstBad = raw.isNotEmpty() && !raw[0].isDigit()
                if (firstBad) (0..9).map { "$it$digits" } else (0..9).map { "$it$digits" } + (0..9).map { "$digits$it" }
            }
            else -> emptyList()
        }
    }

    const val SSID_PREFIX = "GA-"

    /**
     * З PIN-id з наклейки або меню приладу (12 цифр, напр. 1234-5678-9012):
     * SSID = "GA-" + цифри, пароль = кожна цифра d замінена на 9−d.
     */
    fun fromPin(pin: String): DeviceAp {
        val digits = pin.filter { it.isDigit() }
        require(digits.length == 12) { "PIN-id має містити 12 цифр (зараз ${digits.length})" }
        val password = digits.map { ('0' + (9 - (it - '0'))) }.joinToString("")
        return DeviceAp(SSID_PREFIX + digits, password, formatSerial(digits))
    }

    /**
     * З тексту QR-коду на панелі приладу. Формати, які розуміє Cozytouch:
     * «…$S:<ssid>$P:<пароль>…» — готові дані точки доступу;
     * «V:…$P:<12 цифр>» або «M:…$P:<12 цифр>» — PIN-id, з якого все обчислюється.
     * Якщо в тексті просто 12 цифр — теж вважаємо їх PIN-id.
     */
    fun fromQr(content: String): DeviceAp? {
        fun match(p: String) = Regex(p).find(content)?.groupValues?.getOrNull(1)
        val ssid = match("\\\$S:(.*?)[%|\$]") ?: match("\\\$S:(.*)")
        val pass = match("\\\$P:(.*?)[%|\$]") ?: match("\\\$P:(.*)")
        if (ssid != null && pass != null) {
            val fromSsid = ssid.removePrefix(SSID_PREFIX).takeIf { s -> s.length == 12 && s.all { it.isDigit() } }
            val serial = fromSsid ?: return null
            return DeviceAp(ssid, pass, formatSerial(serial))
        }
        val pin = match("V:.*\\\$P:(\\d{12})") ?: match("M:.*\\\$P:(\\d{12})")
            ?: Regex("(\\d{4}-?\\d{4}-?\\d{4})").find(content)?.value
        return pin?.let { runCatching { fromPin(it) }.getOrNull() }
    }

    /** 123456789012 → 1234-5678-9012 (так серійний номер знає хмара). */
    fun formatSerial(raw: String): String {
        if (raw.contains('-')) return raw
        val sb = StringBuilder()
        for (c in raw) {
            if (sb.length == 4 || sb.length == 9) sb.append('-')
            sb.append(c)
        }
        return sb.toString()
    }
}

/** Мережа, яку обігрівач бачить сам (passthrough/scan_network). */
data class ApNetwork(val ssid: String, val rssi: Int, val secured: Boolean)

/**
 * Локальний API обігрівача, доступний коли телефон підключений до його точки доступу.
 * [opener] відкриває з'єднання через саме ту Wi‑Fi мережу (на Android — Network.openConnection).
 */
class LocalHeaterApi(
    private val opener: (URL) -> HttpURLConnection,
    private val base: String = "http://192.168.4.1/",
    private val log: (String) -> Unit = {},
) {
    data class Status(val mode: String?, val status: String?, val error: String?, val message: String?)

    fun info(): Map<String, Any?> = call("GET", "passthrough/info").asMap()

    fun status(): Status {
        val m = call("GET", "passthrough/status").asMap()
        return Status(
            m["connectivity_mode"].asStringOrNull(), m["connectivity_status"].asStringOrNull(),
            m["error"].asStringOrNull(), m["message"].asStringOrNull(),
        )
    }

    fun scanNetworks(): List<ApNetwork> =
        call("GET", "passthrough/scan_network").asMap()["networks"].asList().mapNotNull {
            val m = it.asMap()
            val ssid = m["ssid"].asStringOrNull()?.takeIf { s -> s.isNotBlank() } ?: return@mapNotNull null
            ApNetwork(ssid, m["rssi"].asLongOrNull()?.toInt() ?: -100, m["password"] == true)
        }.distinctBy { it.ssid }.sortedByDescending { it.rssi }

    fun removeCredential() { call("GET", "passthrough/remove_credential") }

    /** Передати домашній Wi‑Fi. connect = "cloud" — прилад підключиться до хмари Atlantic. */
    fun sendCredential(ssid: String, password: String) {
        val body = LinkedHashMap<String, Any?>()
        body["ssid"] = ssid
        if (password.isNotEmpty()) body["password"] = password
        body["connect"] = "cloud"
        val r = call("POST", "passthrough/credential", Json.stringify(body)).asMap()
        if (r["success"] == false) throw CozyException("Обігрівач не прийняв дані Wi‑Fi: ${r["message"] ?: "без пояснень"}")
    }

    fun closeAccessPoint() { call("POST", "passthrough/close_ap", "") }

    private fun call(method: String, path: String, body: String? = null): Any? {
        val conn = opener(URL(base + path))
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            log("AP $method $path -> $code ${text.take(200)}")
            if (code !in 200..299) throw CozyException("Обігрівач відповів HTTP $code на $path")
            return if (text.isBlank()) null else runCatching { Json.parse(text) }.getOrNull()
        } finally {
            conn.disconnect()
        }
    }
}

/** Яку мережу шукати: точну назву або префікс; password = null — відкрита мережа. */
data class ApTarget(val ssid: String? = null, val ssidPrefix: String? = null, val password: String? = null)

/** Канал через Wi‑Fi приладу: HTTP-з'єднання і сокети. */
class ApLink(val open: (URL) -> HttpURLConnection, val socketFactory: javax.net.SocketFactory)

/** Підключення телефону до точки доступу приладу (реалізація — на Android). */
interface ApConnector {
    fun connect(target: ApTarget, timeoutMs: Long): ApLink
    fun disconnect()
}

enum class PairStep(val title: String) {
    CLOUD_REGISTER("Реєстрація приладу в хмарі"),
    CLOUD_ATTACH("Прив'язка до вашого акаунта"),
    AP_CONNECT("Підключення до точки доступу обігрівача"),
    SEND_WIFI("Передача даних домашнього Wi‑Fi"),
    WAIT_CLOUD("Обігрівач підключається до інтернету"),
    DISCOVER("Пошук обігрівача в акаунті"),
}

/**
 * Повний сценарій додавання BD0/BD1 Wi‑Fi обігрівача, як в офіційному застосунку:
 * хмара (знайти/створити шлюз → прив'язати до будинку) → точка доступу (передати Wi‑Fi,
 * дочекатись «cloud») → хмара (дочекатись появи приладу).
 */
class Pairer(
    private val api: CozyApi,
    private val connector: ApConnector,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onStep: (PairStep) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    /** Повертає deviceId нового обігрівача, якщо вдалося його знайти. */
    fun run(ap: DeviceAp, homeSsid: String, homePassword: String): Long? {
        require(homeSsid.isNotBlank()) { "Вкажіть назву домашньої Wi‑Fi мережі" }

        // 1. Хмара: знайти або зареєструвати шлюз
        onStep(PairStep.CLOUD_REGISTER)
        val setupId = api.getSetupIds().firstOrNull()
            ?: throw CozyException("В акаунті немає жодного «будинку». Створіть його один раз в офіційному Cozytouch і повторіть.")
        val gatewayId = api.findGatewayId(ap.serial) ?: api.createGateway(ap.serial)
        log("setupId=$setupId gatewayId=$gatewayId serial=${ap.serial}")
        val knownBefore = api.getSetups().flatMap { it.devices }.map { it.deviceId }.toSet()

        // 2. Хмара: прив'язати до будинку
        onStep(PairStep.CLOUD_ATTACH)
        api.attachGateway(setupId, gatewayId)

        // 3. Точка доступу приладу
        onStep(PairStep.AP_CONNECT)
        val link = try {
            connector.connect(ApTarget(ssid = ap.ssid, password = ap.password), 60_000)
        } catch (e: Exception) {
            throw CozyException(
                "Не вдалося підключитися до мережі ${ap.ssid}. Перевірте, що індикатор Wi‑Fi на обігрівачі " +
                    "повільно блимає (затисніть кнопку режиму ~5 с) і телефон поруч. (${e.message})", cause = e,
            )
        }
        try {
            val local = LocalHeaterApi(link.open, log = log)
            runCatching { log("info: ${local.info()}") }
            runCatching { local.removeCredential() }

            onStep(PairStep.SEND_WIFI)
            local.sendCredential(homeSsid.trim(), homePassword)

            // 4. Чекати, поки прилад скаже «cloud»
            onStep(PairStep.WAIT_CLOUD)
            val deadline = clock() + 120_000
            var lost = 0
            while (clock() < deadline) {
                sleeper(3000)
                val st = try { local.status() } catch (e: IOException) { lost++; if (lost >= 3) break else continue }
                catch (e: CozyException) { lost++; if (lost >= 3) break else continue }
                lost = 0
                log("status: $st")
                if (st.status == "cloud") break
                val err = (st.error ?: "").lowercase()
                when {
                    err.contains("password") -> throw CozyException("Обігрівач не зміг підключитися: невірний пароль Wi‑Fi «$homeSsid».")
                    err.contains("ssid") -> throw CozyException("Обігрівач не бачить мережу «$homeSsid». Потрібна мережа 2.4 ГГц поруч з приладом.")
                }
            }
            runCatching { local.closeAccessPoint() }
        } finally {
            connector.disconnect()
        }

        // 5. Хмара: дочекатися приладу
        onStep(PairStep.DISCOVER)
        sleeper(8000) // телефон повертається в інтернет
        return waitForDevice(gatewayId, ap.serial, knownBefore)
    }

    private fun waitForDevice(gatewayId: Int, serial: String, knownBefore: Set<Long>): Long {
        api.requestDiscover(gatewayId)
        val deadline = clock() + 180_000
        var autoConfigured = false
        while (clock() < deadline) {
            val status = runCatching { api.gatewayDiscoverStatus(gatewayId) }.getOrNull()
            log("discoverStatus=$status")
            if (status == 4) throw CozyException("Хмара повідомила про помилку виявлення приладу. Спробуйте ще раз.")
            if ((status == 2 || status == 3) && !autoConfigured) { api.autoConfigureZones(gatewayId); autoConfigured = true }
            val devices = runCatching { api.getSetups().flatMap { it.devices } }.getOrElse { emptyList() }
            val mine = devices.firstOrNull { d ->
                d.deviceId !in knownBefore && (d.raw["gatewaySerialNumber"].asStringOrNull()?.filter { it.isDigit() } == serial.filter { it.isDigit() } || d.looksLikeHeater)
            }
            if (mine != null) return mine.deviceId
            sleeper(6000)
            if (status == null || status < 2) api.requestDiscover(gatewayId)
        }
        throw CozyException(
            "Обігрівач отримав Wi‑Fi, але в акаунті ще не з'явився. Зачекайте кілька хвилин і оновіть список. " +
                "Якщо індикатор Wi‑Fi на приладі світиться постійно — він уже в мережі.",
        )
    }

    /**
     * Сценарій CHG-BD1/Wi‑Fi (мережа ELEC_HEATER_Wifi_xxxx), як у Cozytouch:
     * точка доступу → прочитати PIN з приладу → передати Wi‑Fi → у хмарі знайти/створити шлюз,
     * дочекатися синхронізації, прив'язати до будинку → дочекатися приладу.
     * [knownPin] — якщо прилад не віддав PIN сам.
     */
    fun runBd1(homeSsid: String, homePassword: String, knownPin: String? = null, apSuffix: String? = null): Long? {
        require(homeSsid.isNotBlank()) { "Вкажіть назву домашньої Wi‑Fi мережі" }
        val setupId = api.getSetupIds().firstOrNull()
            ?: throw CozyException("В акаунті немає жодного «будинку». Створіть його один раз в офіційному Cozytouch і повторіть.")
        val knownBefore = runCatching { api.getSetups().flatMap { it.devices }.map { it.deviceId }.toSet() }.getOrElse { emptySet() }

        onStep(PairStep.AP_CONNECT)
        val ssidExact = apSuffix?.trim()?.takeIf { it.isNotEmpty() }?.let { Bd1Protocol.AP_PREFIX + it }
        val link = connectBd1Ap(ssidExact)
        val manual = knownPin?.filter { it.isDigit() }?.takeIf { it.length == 12 }
        var candidates: List<String> = emptyList()
        var rawPin: String? = null
        try {
            val local = Bd1Local(link.socketFactory, log)
            rawPin = local.readPinRaw()
            candidates = if (manual != null) listOf(manual) else PairingCodes.pinCandidates(rawPin)
            log("Варіанти PIN: ${candidates.size} ${candidates.take(3)}${if (candidates.size > 3) "…" else ""}")
            if (candidates.isEmpty()) throw CozyException(
                "Обігрівач не повідомив свій PIN-id. Введіть його вручну (12 цифр з меню приладу: Зв'язок → Інформація) і повторіть.",
            )
            onStep(PairStep.SEND_WIFI)
            val confirmed = local.sendWifi(homeSsid.trim(), homePassword)
            log(if (confirmed) "Модуль підтвердив Wi‑Fi" else "Модуль не відповів — ймовірно, вже перемикається")
        } finally {
            connector.disconnect()
        }

        onStep(PairStep.WAIT_CLOUD)
        sleeper(10_000) // прилад підключається до роутера, телефон — назад в інтернет
        var gatewayId: Int? = null
        var serial: String? = null
        val until = clock() + 240_000
        while (clock() < until && gatewayId == null) {
            val found = ArrayList<Pair<String, Int>>()
            val foreign = ArrayList<String>()
            for (c in candidates) {
                val sn = PairingCodes.formatSerial(c)
                try {
                    api.findGatewayId(sn)?.let { found += sn to it }
                } catch (e: CozyException) {
                    if (e.httpCode == 403 || e.httpCode == 451) foreign += sn else log("$sn: ${e.message}")
                }
            }
            log("Хмара: знайдено ${found.map { it.first }}, чужі ${foreign}")
            when {
                found.size == 1 -> { serial = found[0].first; gatewayId = found[0].second }
                found.size > 1 -> throw CozyException(
                    "Підходить кілька PIN-id: ${found.joinToString { it.first }}. Подивіться PIN у меню обігрівача і введіть його вручну.",
                )
                candidates.size == 1 && foreign.isNotEmpty() -> throw CozyException(
                    "Цей обігрівач (${foreign[0]}) прив'язаний до іншого акаунта Cozytouch. Його має відв'язати попередній " +
                        "власник або підтримка Atlantic (0 800 50-08-85).", 403,
                )
                else -> {
                    // Хмара знає тип шлюзу за серійним номером із заводської бази: для неправильного номера
                    // реєстрація дає 400 «Unknown gateway type». Тож пробуємо варіанти по черзі — підходить лише
                    // справжній (так знайшли 5151… замість зіпсованого \x16151…). Чужі (403/451) пропускаємо.
                    for (c in candidates) {
                        val sn = PairingCodes.formatSerial(c)
                        if (sn in foreign) continue
                        val id = runCatching { api.createGateway(sn) }
                            .onFailure { log("$sn: ${it.message?.take(100)}") }.getOrNull()
                        if (id != null) { serial = sn; gatewayId = id; log("Справжній PIN-id: $c"); break }
                    }
                }
            }
            if (gatewayId == null) sleeper(10_000)
        }
        if (gatewayId == null && candidates.size > 1) {
            val shown = "?" + (rawPin ?: "").filter { it.isDigit() }
            throw CozyException(
                "Обігрівач отримав Wi‑Fi, але його PIN-id прийшов неповним ($shown — перша цифра невідома) і хмара не впізнала жоден варіант. " +
                    "Подивіться повний PIN у меню обігрівача (Зв'язок → Інформація), введіть у поле PIN і повторіть.",
            )
        }
        while (gatewayId != null && clock() < until) {
            val sync = runCatching { api.isGatewaySynchronized(serial!!) }.getOrNull()
            log("gatewayId=$gatewayId synchronized=$sync")
            if (sync != false) break
            sleeper(10_000)
        }
        val serialFinal = serial ?: ""
        val gid = gatewayId ?: throw CozyException("Хмара не бачить обігрівач. Перевірте пароль Wi‑Fi і що мережа 2.4 ГГц, та повторіть.")

        onStep(PairStep.CLOUD_REGISTER)
        onStep(PairStep.CLOUD_ATTACH)
        api.attachGateway(setupId, gid)

        onStep(PairStep.DISCOVER)
        return waitForDevice(gid, serialFinal, knownBefore)
    }

    private fun connectBd1Ap(exact: String?): ApLink {
        val targets = listOf(
            ApTarget(ssid = exact, ssidPrefix = if (exact == null) Bd1Protocol.AP_PREFIX else null, password = null),
            ApTarget(ssid = exact, ssidPrefix = if (exact == null) Bd1Protocol.AP_PREFIX else null, password = Bd1Protocol.AP_PASSWORD),
        )
        var last: Exception? = null
        for (t in targets) {
            try { return connector.connect(t, 60_000) } catch (e: Exception) { last = e; log("AP ${t.password ?: "(відкрита)"}: ${e.message}") }
        }
        throw CozyException(
            "Не вдалося підключитися до мережі ${exact ?: Bd1Protocol.AP_PREFIX + "…"}. Переконайтесь, що вона є у списку Wi‑Fi " +
                "і телефон поруч з обігрівачем. (${last?.message})", cause = last,
        )
    }
}
