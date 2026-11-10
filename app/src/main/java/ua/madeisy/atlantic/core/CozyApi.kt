package ua.madeisy.atlantic.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/**
 * Клієнт хмари Atlantic Cozytouch (бекенд Magellan, apis.groupe-atlantic.com).
 *
 * Протокол взято з відкритих інтеграцій (gduteil/cozytouch для Home Assistant,
 * NicolasYDDER/homey-cozytouch). Це неофіційний API: Atlantic може його змінити.
 *
 * Усі методи блокуючі — викликайте їх з фонового потоку (Dispatchers.IO).
 */
class CozyApi(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://apis.groupe-atlantic.com"

        /** Публічний client_id застосунку Cozytouch (той самий, що в HA/Homey). */
        const val CLIENT_BASIC =
            "Q3RfMUpWeVRtSUxYOEllZkE3YVVOQmpGblpVYToyRWNORHpfZHkzNDJVSnFvMlo3cFNKTnZVdjBh"

        private const val TIMEOUT_MS = 30_000
    }

    @Volatile private var username: String? = null
    @Volatile private var password: String? = null
    @Volatile private var token: String? = null
    @Volatile private var tokenExpiresAt: Long = 0

    val isLoggedIn: Boolean get() = token != null

    // ───────────────────────── Авторизація ─────────────────────────

    @Synchronized
    fun login(email: String, pass: String) {
        val body = form(
            "grant_type" to "password",
            "scope" to "openid",
            "username" to "GA-PRIVATEPERSON/$email",
            "password" to pass,
        )
        val res = http(
            "POST", "/users/token", body,
            mapOf(
                "Authorization" to "Basic $CLIENT_BASIC",
                "Content-Type" to "application/x-www-form-urlencoded",
            ),
        )
        val json = res.json().asMap()
        if (res.code == 400 || res.code == 401 || json["error"] == "invalid_grant") {
            throw CozyException("Невірний email або пароль Cozytouch", res.code)
        }
        res.requireOk("вхід")
        val access = json["access_token"].asStringOrNull()
            ?: throw CozyException("Сервер не повернув токен (відповідь: ${res.body.take(200)})", res.code)
        val expiresIn = json["expires_in"].asLongOrNull() ?: 3600
        token = access
        tokenExpiresAt = clock() + (expiresIn - 60) * 1000
        username = email
        password = pass
        log("Вхід успішний, токен на ${expiresIn}с")
    }

    fun logout() {
        token = null; username = null; password = null; tokenExpiresAt = 0
    }

    @Synchronized
    private fun ensureToken() {
        if (token != null && clock() < tokenExpiresAt) return
        val u = username ?: throw CozyException("Не виконано вхід")
        val p = password ?: throw CozyException("Не виконано вхід")
        login(u, p)
    }

    // ───────────────────────── Читання ─────────────────────────

    /** Усі «setups» (будинки) акаунта разом з пристроями та їхніми значеннями. */
    fun getSetups(): List<Setup> {
        val json = authed("GET", "/magellan/cozytouch/setupviewv2").also { it.requireOk("список пристроїв") }.json()
        val list = when (json) {
            is List<*> -> json
            is Map<*, *> -> listOf(json)
            else -> emptyList()
        }
        return list.map { parseSetup(it.asMap()) }
    }

    /** Свіжі значення одного пристрою. Порожній список — деякі пристрої віддають значення лише в setupviewv2. */
    fun getCapabilities(deviceId: Long): List<Capability> {
        val res = authed("GET", "/magellan/capabilities/?deviceId=$deviceId")
        res.requireOk("значення пристрою")
        return parseCapabilities(res.json())
    }

    /** Добове споживання (кВт·год) для setup, або порожній список, якщо хмара його не дає. */
    fun getConsumptionsDaily(setupId: String): List<ConsumptionDay> {
        val res = authed("GET", "/magellan/setups/$setupId/consumptions?periodicity=daily")
        if (res.code in 400..499) return emptyList()
        res.requireOk("споживання")
        val out = ArrayList<ConsumptionDay>()
        for (item in res.json().asList()) {
            for (p in item.asMap()["consumptionPeriods"].asList()) {
                val m = p.asMap()
                val date = m["date"].asLongOrNull() ?: continue
                out += ConsumptionDay(date, m["consumedQuantity"].asDoubleOrNull() ?: 0.0, m["cost"].asDoubleOrNull())
            }
        }
        // кілька лічильників на одну дату — сумуємо
        return out.groupBy { it.dateEpochSec }
            .map { (d, l) -> ConsumptionDay(d, l.sumOf { it.kwh }, l.mapNotNull { it.cost }.takeIf { it.isNotEmpty() }?.sum()) }
            .sortedBy { it.dateEpochSec }
    }

    // ───────────────────────── Запис ─────────────────────────

    /**
     * Записує одне значення і чекає, поки пристрій його виконає.
     * Хмара відповідає executionId; стан 1 — в черзі, 2 — виконується, 3 — готово.
     */
    fun writeCapability(deviceId: Long, capabilityId: Int, value: String, maxPolls: Int = 10) {
        val payload = Json.stringify(
            mapOf("capabilityId" to capabilityId, "deviceId" to deviceId, "value" to value)
        )
        val res = authed("POST", "/magellan/executions/writecapability", payload)
        if (res.code !in 200..299) {
            throw CozyException(
                "Пристрій не прийняв зміну параметра $capabilityId: HTTP ${res.code} ${res.body.take(200)}",
                res.code,
            )
        }
        val parsed = runCatching { res.json() }.getOrNull()
        val executionId = when (parsed) {
            is Map<*, *> -> parsed.asMap()["executionId"].asStringOrNull()
            else -> parsed.asStringOrNull()
        }?.trim('"')
        if (executionId.isNullOrBlank()) return // деякі відповіді без executionId — вважаємо прийнятим

        repeat(maxPolls) {
            sleeper(1000)
            val st = authed("GET", "/magellan/executions/$executionId")
            if (st.code == 429) throw CozyException("Хмара Atlantic обмежила кількість запитів, спробуйте за хвилину", 429)
            val state = st.json().asMap()["state"].asLongOrNull()
            when (state) {
                1L, 2L -> Unit
                3L -> return
                null -> Unit
                else -> throw CozyException("Пристрій відхилив команду (стан виконання $state)")
            }
        }
        throw CozyException("Пристрій не підтвердив команду вчасно. Можливо, він не в мережі.")
    }

    /**
     * Режим відсутності (відпустка) на рівні всього setup.
     * start/end — Unix-час у секундах; null обидва = вимкнути відсутність.
     */
    fun setAbsence(setup: Setup, startSec: Long?, endSec: Long?) {
        val writable = listOf(
            "address", "area", "currency", "mainHeatingEnergy", "mainDHWEnergy", "name",
            "numberOfPersons", "numberOfRooms", "setupBuildingDate", "type",
        )
        val body = LinkedHashMap<String, Any?>()
        for (k in writable) if (setup.raw.containsKey(k)) body[k] = setup.raw[k]
        body["absence"] = if (startSec != null && endSec != null) mapOf("startDate" to startSec, "endDate" to endSec) else emptyMap<String, Any?>()
        val res = authed("PUT", "/magellan/v2/setups/${setup.id}", Json.stringify(body))
        res.requireOk("режим відсутності")
    }

    // ───────────────────────── Додавання приладу (як у Cozytouch) ─────────────────────────

    /** id будинків (setups) акаунта. Прилад прив'язується до першого, як в офіційному застосунку. */
    fun getSetupIds(): List<Int> {
        val res = authed("GET", "/magellan/setups")
        res.requireOk("список будинків")
        return res.json().asList().mapNotNull { it.asMap()["id"].asLongOrNull()?.toInt() }
    }

    /**
     * id шлюзу (сам Wi-Fi обігрівач — це «шлюз») за серійним номером XXXX-XXXX-XXXX,
     * або null, якщо хмара ще його не знає. id повертається в заголовку Location.
     */
    fun findGatewayId(serial: String): Int? {
        val res = authed("HEAD", "/magellan/gateways/?serialNumber=" + URLEncoder.encode(serial, "UTF-8"))
        res.location?.substringAfterLast('/')?.trim()?.toIntOrNull()?.let { return it }
        return when (res.code) {
            404 -> null
            403 -> throw CozyException(
                "Цей обігрівач уже прив'язаний до іншого акаунта Cozytouch. Його має відв'язати попередній " +
                    "власник (у своєму застосунку) або підтримка Atlantic (0 800 50-08-85), назвавши PIN-id приладу.", 403,
            )
            451 -> throw CozyException("На приладі діє сервісний контракт — прив'язка можлива лише через підтримку Atlantic.", 451)
            in 200..299 -> null
            else -> { res.requireOk("пошук приладу"); null }
        }
    }

    fun createGateway(serial: String): Int {
        val res = authed("POST", "/magellan/gateways", Json.stringify(mapOf("serialNumber" to serial)))
        if (res.code == 403) throw CozyException("Хмара не дозволила зареєструвати прилад (HTTP 403). Можливо, він належить іншому акаунту.", 403)
        res.requireOk("реєстрація приладу")
        return res.json().asLongOrNull()?.toInt()
            ?: res.location?.substringAfterLast('/')?.toIntOrNull()
            ?: findGatewayId(serial)
            ?: throw CozyException("Хмара не повернула id приладу: ${res.body.take(200)}")
    }

    /** Прив'язати шлюз до будинку. Спершу новий спосіб (через виконання), якщо ні — старий. */
    fun attachGateway(setupId: Int, gatewayId: Int) {
        val body = Json.stringify(mapOf("gatewayId" to gatewayId))
        val res = authed("POST", "/magellan/executions/setups/$setupId/attach-gateway", body)
        when {
            res.code in 200..299 -> {
                val exec = res.json().let { if (it is Map<*, *>) it.asMap()["executionId"] ?: it.asMap()["id"] else it }.asStringOrNull()
                if (!exec.isNullOrBlank()) waitExecution(exec.trim('"'), maxPolls = 90, intervalMs = 2000)
            }
            res.code == 429 -> throw CozyException("Забагато спроб прив'язки — зачекайте кілька хвилин і повторіть.", 429)
            res.code == 451 -> throw CozyException("На приладі діє сервісний контракт — прив'язка лише через підтримку Atlantic.", 451)
            else -> {
                log("attach-gateway -> ${res.code} ${res.body.take(200)}; пробую старий спосіб")
                val old = http("POST", "/magellan/cozytouch/setups/$setupId/attachgateway", body, headers() + ("bypassgam" to "false"))
                if (old.code !in 200..299) {
                    // вже прив'язаний до цього будинку — не помилка
                    if (old.body.contains("already", ignoreCase = true)) return
                    throw CozyException("Не вдалося прив'язати прилад до будинку: HTTP ${old.code} ${old.body.take(200)}", old.code)
                }
            }
        }
    }

    /** Стан виявлення шлюзу: 0 невідомо, 1 ідентифіковано, 2 виявлено, 3 синхронізовано, 4 помилка. */
    fun gatewayDiscoverStatus(gatewayId: Int): Int? {
        for (path in listOf("/magellan/v2/gateways/$gatewayId/discover-status", "/magellan/gateways/$gatewayId/discover-status")) {
            val res = runCatching { authed("GET", path) }.getOrNull() ?: continue
            if (res.code in 200..299) return res.json().asMap()["discoverStatus"].asLongOrNull()?.toInt()
        }
        return null
    }

    /** Попросити хмару опитати шлюз і знайти прилади на ньому (як «discover» у Cozytouch). Помилки не критичні. */
    fun requestDiscover(gatewayId: Int) {
        runCatching { authed("POST", "/magellan/executions/gateways/$gatewayId/discover", "\"\"") }
        runCatching { authed("POST", "/magellan/devices/discover?gatewayId=$gatewayId", "\"\"") }
    }

    fun autoConfigureZones(gatewayId: Int) {
        runCatching { authed("POST", "/magellan/zones/autoconfigure", Json.stringify(mapOf("gatewayId" to gatewayId))) }
    }

    /** Чи синхронізувався шлюз з хмарою (null — хмара не відповіла). */
    fun isGatewaySynchronized(serial: String): Boolean? {
        val res = authed("GET", "/magellan/gateways/issynchronized?serialNumber=" + URLEncoder.encode(serial, "UTF-8"))
        if (res.code !in 200..299) return null
        val j = res.json()
        return when (j) {
            is Boolean -> j
            is Map<*, *> -> j.asMap()["isSynchronized"] as? Boolean
            else -> null
        }
    }

    private fun waitExecution(id: String, maxPolls: Int, intervalMs: Long) {
        repeat(maxPolls) {
            sleeper(intervalMs)
            val st = authed("GET", "/magellan/executions/$id")
            when (st.json().asMap()["state"].asLongOrNull()) {
                3L -> return
                1L, 2L, null -> Unit
                else -> throw CozyException("Хмара відхилила прив'язку (стан ${st.body.take(120)})")
            }
        }
        throw CozyException("Хмара не завершила прив'язку вчасно")
    }

    // ───────────────────────── HTTP ─────────────────────────

    private data class Res(val code: Int, val body: String, val location: String? = null) {
        fun json(): Any? = if (body.isBlank()) null else Json.parse(body)
        fun requireOk(what: String) {
            when {
                code in 200..299 -> Unit
                code == 429 -> throw CozyException("Хмара Atlantic обмежила кількість запитів ($what). Зачекайте хвилину.", code)
                code == 401 || code == 403 -> throw CozyException("Немає доступу ($what): HTTP $code", code)
                code >= 500 -> throw CozyException("Сервер Atlantic недоступний ($what): HTTP $code", code)
                else -> throw CozyException("Помилка ($what): HTTP $code ${body.take(200)}", code)
            }
        }
    }

    private fun authed(method: String, path: String, body: String? = null): Res {
        ensureToken()
        var res = http(method, path, body, headers())
        if (res.code == 401) { // токен протух раніше — перелогін і повтор
            token = null
            ensureToken()
            res = http(method, path, body, headers())
        }
        return res
    }

    private fun headers() = mapOf(
        "Authorization" to "Bearer $token",
        "Content-Type" to "application/json",
    )

    private fun http(method: String, path: String, body: String?, headers: Map<String, String>): Res {
        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = if (method == "HEAD") "" else {
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            }
            log("$method $path -> $code")
            return Res(code, text, conn.getHeaderField("Location"))
        } catch (e: SocketTimeoutException) {
            throw CozyException("Сервер Atlantic не відповідає (тайм-аут)", cause = e)
        } catch (e: IOException) {
            throw CozyException("Немає з'єднання з сервером Atlantic: ${e.message}", cause = e)
        } finally {
            conn.disconnect()
        }
    }

    private fun form(vararg pairs: Pair<String, String>) =
        pairs.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
}
