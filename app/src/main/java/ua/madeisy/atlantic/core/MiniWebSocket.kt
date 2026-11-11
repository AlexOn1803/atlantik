package ua.madeisy.atlantic.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64
import javax.net.SocketFactory

/**
 * Мінімальний WebSocket-клієнт (RFC 6455, лише текстові кадри) поверх довільної SocketFactory —
 * щоб на Android відкривати сокет саме через Wi‑Fi обігрівача (Network.socketFactory).
 */
class MiniWebSocket private constructor(private val socket: Socket) {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private val rnd = SecureRandom()
    var closed = false
        private set

    companion object {
        fun connect(host: String, port: Int, path: String = "/", factory: SocketFactory = SocketFactory.getDefault(), timeoutMs: Int = 10_000): MiniWebSocket {
            val s = factory.createSocket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            val ws = MiniWebSocket(s)
            ws.handshake(host, port, path)
            return ws
        }
    }

    private fun handshake(host: String, port: Int, path: String) {
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also { rnd.nextBytes(it) })
        val req = "GET $path HTTP/1.1\r\nHost: $host:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
        output.write(req.toByteArray(Charsets.US_ASCII)); output.flush()
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("WebSocket: з'єднання закрито під час рукостискання")
            head.write(b); last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > 8192) throw IOException("WebSocket: завелика відповідь")
        }
        val status = head.toString("US-ASCII").lineSequence().firstOrNull() ?: ""
        if (!status.contains(" 101")) throw IOException("WebSocket: сервер відповів «$status»")
    }

    fun sendText(text: String) = sendFrame(0x1, text.toByteArray(Charsets.UTF_8))

    @Synchronized
    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val out = ByteArrayOutputStream()
        out.write(0x80 or opcode)
        val n = payload.size
        when {
            n < 126 -> out.write(0x80 or n)
            n < 65536 -> { out.write(0x80 or 126); out.write(n shr 8); out.write(n and 0xFF) }
            else -> { out.write(0x80 or 127); for (i in 7 downTo 0) out.write(((n.toLong() shr (8 * i)) and 0xFF).toInt()) }
        }
        val mask = ByteArray(4).also { rnd.nextBytes(it) }
        out.write(mask)
        for (i in payload.indices) out.write(payload[i].toInt() xor mask[i % 4].toInt())
        output.write(out.toByteArray()); output.flush()
    }

    /** Наступне текстове повідомлення, або null якщо сервер закрив з'єднання. Кидає SocketTimeoutException. */
    fun readText(timeoutMs: Int): String? {
        socket.soTimeout = timeoutMs
        val message = ByteArrayOutputStream()
        while (true) {
            val b0 = readByte() ?: return null.also { closed = true }
            val b1 = readByte() ?: return null.also { closed = true }
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = ((readByte()!! shl 8) or readByte()!!).toLong()
            else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or readByte()!!.toLong() } }
            val masked = (b1 and 0x80) != 0
            val mask = if (masked) ByteArray(4) { readByte()!!.toByte() } else null
            val data = ByteArray(len.toInt())
            var r = 0
            while (r < data.size) { val k = input.read(data, r, data.size - r); if (k < 0) { closed = true; return null }; r += k }
            if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (opcode) {
                0x8 -> { closed = true; runCatching { sendFrame(0x8, ByteArray(0)) }; return null }
                0x9 -> sendFrame(0xA, data)
                0xA -> Unit
                0x0, 0x1, 0x2 -> { message.write(data); if (fin) return message.toString("UTF-8") }
            }
        }
    }

    private fun readByte(): Int? { val b = input.read(); return if (b < 0) null else b }

    fun close() {
        if (!closed) runCatching { sendFrame(0x8, ByteArray(0)) }
        closed = true
        runCatching { socket.close() }
    }
}

/**
 * Протокол Wi‑Fi модуля CHG-BD1/Wi‑Fi (мережа ELEC_HEATER_Wifi_xxxx): JSON-обгортка над Modbus,
 * як у Cozytouch (WSClient / WebSocketManager). Сервер: ws://192.168.1.99:9998.
 */
object Bd1Protocol {
    const val HOST = "192.168.1.99"
    const val PORT = 9998
    const val AP_PREFIX = "ELEC_HEATER_Wifi_"
    const val AP_PASSWORD = "WIFIHVAC"

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    /** Рядок, доповнений нулями до [size] байтів (як WifiUtils.encodeString(s, size)). */
    fun encodeString(s: String, size: Int): String {
        val raw = s.toByteArray(Charsets.UTF_8)
        require(raw.size <= size) { "Задовге значення (${raw.size} байт, максимум $size)" }
        return b64(raw.copyOf(size))
    }

    /** 16-бітне число big-endian (WifiUtils.encode(int)). */
    fun encodeInt(v: Int): String = b64(byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte()))

    private fun command(id: String, function: Int, start: Int, data: String? = null, quantity: Int? = null, dataSize: Int? = null): Map<String, Any?> {
        val value = LinkedHashMap<String, Any?>()
        value["startingAddress"] = start
        if (data != null) value["data"] = data
        if (quantity != null) value["quantity"] = quantity
        if (dataSize != null) value["dataSize"] = dataSize
        return mapOf("command" to mapOf("id" to id, "nodeId" to 1, "function" to function, "value" to value))
    }

    private fun envelope(commands: List<Map<String, Any?>>) =
        Json.stringify(mapOf("modbuslink" to listOf(mapOf("execution" to mapOf("commands" to commands)))))

    /** Прочитати довільні holding registers (для діагностики). */
    fun readHolding(start: Int, quantity: Int): String = envelope(listOf(command("1", 3, start, quantity = quantity)))

    /** Прочитати PIN-id шлюзу: holding registers 180, 6 шт. */
    fun readPin(): String = envelope(listOf(command("1", 3, 180, quantity = 6)))

    /** Записати домашній Wi‑Fi: 134 ← 3 (застосувати), 98 ← пароль (64 байти), 82 ← SSID (32 байти). */
    fun wifiConfig(ssid: String, password: String): String {
        val cmds = ArrayList<Map<String, Any?>>()
        cmds += command("0", 6, 134, encodeInt(3))
        if (password.isNotEmpty()) cmds += command("1", 16, 98, encodeString(password, 64), 32, 16)
        cmds += command("2", 16, 82, encodeString(ssid, 32), 16, 16)
        return envelope(cmds)
    }

    enum class Kind { ACK, OK, DATA, ERROR, OTHER }
    data class Reply(val kind: Kind, val data: String? = null, val raw: String)

    fun parse(text: String): Reply {
        val link = runCatching {
            Json.parse(text).asMap()["box"].asMap()["modules"].asList().firstOrNull().asMap()["modbuslink"].asList().firstOrNull().asMap()
        }.getOrNull() ?: return Reply(Kind.OTHER, raw = text)
        link["commandResult"]?.let { cr ->
            val r = cr.asMap()["result"].asLongOrNull()?.toInt() ?: -1
            return Reply(if (r == 4 || r == 5) Kind.OK else Kind.ERROR, raw = text)
        }
        if (link.containsKey("ack")) return Reply(Kind.ACK, raw = text)
        link["states"]?.let { st ->
            val v = st.asMap()["values"].asList().firstOrNull().asMap()["value"].asStringOrNull()
            val decoded = v?.let { runCatching { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }.getOrNull() }
            return Reply(Kind.DATA, decoded?.trimEnd('\u0000')?.trim(), text)
        }
        return Reply(Kind.OTHER, raw = text)
    }
}

/** Розмова з модулем BD1 по WebSocket. */
class Bd1Local(private val factory: SocketFactory, private val log: (String) -> Unit = {}) {

    private fun session(send: String, waitFor: (Bd1Protocol.Reply) -> Boolean, timeoutMs: Int): Bd1Protocol.Reply? {
        val ws = MiniWebSocket.connect(Bd1Protocol.HOST, Bd1Protocol.PORT, factory = factory)
        try {
            log("WS → $send")
            ws.sendText(send)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val left = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(500)
                val msg = try { ws.readText(left) } catch (e: SocketTimeoutException) { break } ?: break
                log("WS ← ${msg.take(300)}")
                val r = Bd1Protocol.parse(msg)
                if (r.kind == Bd1Protocol.Kind.ERROR) return r
                if (waitFor(r)) return r
            }
            return null
        } finally {
            ws.close()
        }
    }

    /** Сирий вміст регістрів PIN (180×6) як текст, або null. */
    fun readPinRaw(): String? {
        repeat(2) {
            val r = runCatching { session(Bd1Protocol.readPin(), { it.kind == Bd1Protocol.Kind.DATA }, 10_000) }
                .onFailure { log("readPin: ${it.message}") }.getOrNull()
            if (r?.kind == Bd1Protocol.Kind.DATA && r.data != null) {
                log("PIN (сирий): " + r.data.map { c -> if (c.isDigit()) c.toString() else "\\x%02x".format(c.code) }.joinToString(""))
                // діагностика: сусідні регістри, щоб зрозуміти зіпсований перший символ
                for ((st, q) in listOf(170 to 10, 186 to 10, 176 to 4)) {
                    runCatching { session(Bd1Protocol.readHolding(st, q), { x -> x.kind == Bd1Protocol.Kind.DATA }, 6_000) }
                        .onFailure { e -> log("read $st×$q: ${e.message}") }
                }
                return r.data
            }
        }
        return null
    }

    /**
     * Передати Wi‑Fi. Як Cozytouch: з'єднання НЕ закриваємо після першої відповіді — модуль підтверджує
     * кожну з 3 команд окремо (4 = прийнято, 5 = виконано); закриваємо, коли він 4 с мовчить або сам закриє.
     * Повертає true, якщо щось підтверджено.
     */
    fun sendWifi(ssid: String, password: String): Boolean {
        val payload = Bd1Protocol.wifiConfig(ssid, password)
        val expected = Json.parse(payload).asMap()["modbuslink"].asList()[0].asMap()["execution"].asMap()["commands"].asList()
            .map { it.asMap()["command"].asMap()["id"].toString() }
        val results = LinkedHashMap<String, MutableList<Int>>()
        val ws = MiniWebSocket.connect(Bd1Protocol.HOST, Bd1Protocol.PORT, factory = factory)
        try {
            log("WS → " + payload.replace(Regex("\"data\":\"[^\"]*\""), "\"data\":\"…\""))
            ws.sendText(payload)
            val hardEnd = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < hardEnd) {
                val msg = try { ws.readText(4_000) } catch (e: SocketTimeoutException) { log("модуль 4 с мовчить — завершую"); break }
                    ?: run { log("модуль закрив з'єднання (перемикається на роутер)"); null } ?: break
                log("WS ← ${msg.take(300)}")
                val cr = runCatching {
                    Json.parse(msg).asMap()["box"].asMap()["modules"].asList()[0].asMap()["modbuslink"].asList()[0].asMap()["commandResult"]
                }.getOrNull()
                if (cr != null) {
                    val id = cr.asMap()["commandId"]?.toString() ?: "?"
                    val r = cr.asMap()["result"].asLongOrNull()?.toInt() ?: -1
                    results.getOrPut(id) { mutableListOf() } += r
                }
                if (expected.all { 5 in (results[it] ?: emptyList<Int>()) }) {
                    log("усі команди виконані — чекаю, поки модуль застосує")
                    runCatching { ws.readText(4_000) }
                    break
                }
            }
        } finally {
            ws.close()
        }
        log("Підсумок: " + expected.joinToString { "cmd $it: ${results[it] ?: "—"}" } + " (4 прийнято, 5 виконано)")
        if (results.values.flatten().any { it != 4 && it != 5 && it != -1 })
            throw CozyException("Обігрівач відхилив дані Wi‑Fi (перевірте назву і пароль).")
        return results.isNotEmpty()
    }
}
