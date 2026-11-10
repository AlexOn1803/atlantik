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

/** Підключення телефону до точки доступу приладу (реалізація — на Android). */
interface ApConnector {
    /** Підключитися і повернути спосіб відкривати HTTP-з'єднання через цю мережу. */
    fun connect(ssid: String, password: String, timeoutMs: Long): (URL) -> HttpURLConnection
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
        val opener = try {
            connector.connect(ap.ssid, ap.password, 60_000)
        } catch (e: Exception) {
            throw CozyException(
                "Не вдалося підключитися до мережі ${ap.ssid}. Перевірте, що індикатор Wi‑Fi на обігрівачі " +
                    "повільно блимає (затисніть кнопку режиму ~5 с) і телефон поруч. (${e.message})", cause = e,
            )
        }
        try {
            val local = LocalHeaterApi(opener, log = log)
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
                d.deviceId !in knownBefore && (d.raw["gatewaySerialNumber"].asStringOrNull()?.filter { it.isDigit() } == ap.serial.filter { it.isDigit() } || d.looksLikeHeater)
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
}
