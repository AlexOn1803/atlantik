package ua.madeisy.atlantic.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URL
import java.util.Base64
import javax.net.SocketFactory
import kotlin.concurrent.thread

/** Фейковий Wi‑Fi модуль BD1: WebSocket-сервер з Modbus-JSON. */
private class FakeBd1Module(val pin: String) {
    val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val received = mutableListOf<String>()

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { s.use { handle(it) } }
            }
        }
    }

    private fun handle(s: Socket) {
        val inp = s.getInputStream(); val out = s.getOutputStream()
        var last4 = 0
        while (true) { val b = inp.read(); if (b < 0) return; last4 = (last4 shl 8) or b; if (last4 == 0x0D0A0D0A) break }
        out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: x\r\n\r\n".toByteArray()); out.flush()
        while (true) {
            val text = readFrame(inp) ?: return
            synchronized(received) { received += text }
            val cmds = Json.parse(text).asMap()["modbuslink"].asList()[0].asMap()["execution"].asMap()["commands"].asList()
            val first = cmds[0].asMap()["command"].asMap()
            if (first["function"] == 3L && first["value"].asMap()["startingAddress"] == 180L) {
                send(out, """{"box":{"modules":[{"modbuslink":[{"ack":{}}]}]}}""")
                val v = Base64.getEncoder().encodeToString(pin.toByteArray())
                send(out, """{"box":{"modules":[{"modbuslink":[{"states":{"values":[{"value":"$v"}]}}]}]}}""")
            } else {
                send(out, """{"box":{"modules":[{"modbuslink":[{"commandResult":{"result":4}}]}]}}""")
                out.write(byteArrayOf(0x88.toByte(), 0)); out.flush() // модуль закриває, бо перемикається на роутер
                return
            }
        }
    }

    private fun readFrame(inp: InputStream): String? {
        val b0 = inp.read(); if (b0 < 0) return null
        val b1 = inp.read()
        if ((b0 and 0x0F) == 0x8) return null
        var len = b1 and 0x7F
        if (len == 126) len = (inp.read() shl 8) or inp.read()
        val mask = ByteArray(4) { inp.read().toByte() }
        val data = ByteArray(len); var r = 0
        while (r < len) r += inp.read(data, r, len - r)
        for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        return String(data)
    }

    private fun send(out: OutputStream, text: String) {
        val p = text.toByteArray(); val f = ByteArrayOutputStream()
        f.write(0x81)
        if (p.size < 126) f.write(p.size) else { f.write(126); f.write(p.size shr 8); f.write(p.size and 0xFF) }
        f.write(p); out.write(f.toByteArray()); out.flush()
    }
}

/** Сокети, що замість 192.168.1.99:9998 ідуть на локальний фейковий модуль. */
private class RedirectFactory(val targetPort: Int) : SocketFactory() {
    override fun createSocket(): Socket = object : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) = super.connect(InetSocketAddress("127.0.0.1", targetPort), timeout)
    }
    override fun createSocket(h: String?, p: Int) = createSocket().apply { connect(InetSocketAddress(h, p)) }
    override fun createSocket(h: String?, p: Int, la: InetAddress?, lp: Int) = createSocket(h, p)
    override fun createSocket(h: InetAddress?, p: Int) = createSocket().apply { connect(InetSocketAddress(h, p)) }
    override fun createSocket(a: InetAddress?, p: Int, la: InetAddress?, lp: Int) = createSocket(a, p)
}

class Bd1PairingTest {
    private var failures = 0
    private fun check(cond: Boolean, msg: String) { if (cond) println("OK   $msg") else { println("FAIL $msg"); failures++ } }

    @Test
    fun protocolMessages() {
        val cfg = Json.parse(Bd1Protocol.wifiConfig("3DN9C", "pass1234")).asMap()["modbuslink"].asList()[0].asMap()["execution"].asMap()["commands"].asList()
            .map { it.asMap()["command"].asMap() }
        check(cfg.map { it["value"].asMap()["startingAddress"] } == listOf(134L, 98L, 82L), "order 134,98,82")
        check(cfg[0]["value"].asMap()["data"] == "AAM=", "apply = 3 -> AAM=")
        val ssidBytes = Base64.getDecoder().decode(cfg[2]["value"].asMap()["data"] as String)
        check(ssidBytes.size == 32 && String(ssidBytes, 0, 5) == "3DN9C" && ssidBytes[5].toInt() == 0, "ssid padded to 32")
        check(cfg[1]["value"].asMap()["quantity"] == 32L && cfg[2]["value"].asMap()["quantity"] == 16L, "quantities")
        val open = Json.parse(Bd1Protocol.wifiConfig("Open", "")).asMap()["modbuslink"].asList()[0].asMap()["execution"].asMap()["commands"].asList()
        check(open.size == 2, "no password register for open network")
        check(Bd1Protocol.parse("""{"box":{"modules":[{"modbuslink":[{"commandResult":{"result":2}}]}]}}""").kind == Bd1Protocol.Kind.ERROR, "error result")
        assertEquals("невдалих перевірок", 0, failures)
    }

    @Test
    fun fullBd1Flow() {
        val module = FakeBd1Module("224261234567")
        var gwCreated = false
        var attached = false
        var synced = 0
        val cloud = FakeHttp { m, p, b ->
            when {
                p == "/users/token" -> Triple(200, """{"access_token":"T","expires_in":3600}""", emptyMap())
                p == "/magellan/setups" -> Triple(200, """[{"id":7}]""", emptyMap())
                m == "HEAD" -> if (gwCreated) Triple(200, "", mapOf("Location" to "/magellan/gateways/31")) else Triple(404, "", emptyMap())
                m == "POST" && p == "/magellan/gateways" -> { check(b == """{"serialNumber":"2242-6123-4567"}""", "create $b"); gwCreated = true; Triple(201, "31", emptyMap()) }
                p.startsWith("/magellan/gateways/issynchronized") -> { synced++; Triple(200, """{"isSynchronized":${synced > 1}}""", emptyMap()) }
                p == "/magellan/executions/setups/7/attach-gateway" -> { attached = true; Triple(201, "9", emptyMap()) }
                p == "/magellan/executions/9" -> Triple(200, """{"state":3}""", emptyMap())
                p == "/magellan/cozytouch/setupviewv2" -> Triple(200, if (attached)
                    """[{"id":7,"devices":[{"deviceId":5005,"productId":41,"name":"ALTIS","capabilities":[]}]}]""" else """[{"id":7,"devices":[]}]""", emptyMap())
                else -> Triple(404, "", emptyMap())
            }
        }
        val targets = mutableListOf<ApTarget>()
        val connector = object : ApConnector {
            override fun connect(target: ApTarget, timeoutMs: Long): ApLink {
                targets += target
                return ApLink({ URL("http://127.0.0.1:1/").openConnection() as HttpURLConnection }, RedirectFactory(module.server.localPort))
            }
            override fun disconnect() {}
        }
        val api = CozyApi(baseUrl = "http://127.0.0.1:${cloud.port}", sleeper = {})
        api.login("a@b.c", "x")
        var t = 0L
        val id = Pairer(api, connector, sleeper = { t += it }, clock = { t }, log = { println("  log: $it") }).runBd1("3DN9C", "pass1234")
        check(id == 5005L, "device found $id")
        check(targets.first() == ApTarget(ssidPrefix = "ELEC_HEATER_Wifi_"), "open AP by prefix first ${targets.first()}")
        check(module.received.size == 2 && module.received[1].contains("\"startingAddress\":82"), "pin read + wifi sent")
        check(gwCreated && attached && synced >= 2, "cloud create+sync+attach")
        cloud.close(); module.server.close()
        assertEquals("невдалих перевірок", 0, failures)
    }

    @Test
    fun corruptedFirstPinByte() {
        check(PairingCodes.pinCandidates("\u0016" + "15108524955").size == 10, "10 candidates for bad first byte")
        check(PairingCodes.pinCandidates("123456789012") == listOf("123456789012"), "clean pin")
        val module = FakeBd1Module("\u0016" + "15108524955")
        val heads = mutableListOf<String>()
        var created = false
        var att = false
        val cloud = FakeHttp { m, p, _ ->
            when {
                p == "/users/token" -> Triple(200, """{"access_token":"T","expires_in":3600}""", emptyMap())
                p == "/magellan/setups" -> Triple(200, """[{"id":7}]""", emptyMap())
                m == "HEAD" -> { heads += p; if (p.endsWith("serialNumber=3151-0852-4955")) Triple(200, "", mapOf("Location" to "/magellan/gateways/44")) else Triple(404, "", emptyMap()) }
                m == "POST" && p == "/magellan/gateways" -> { created = true; Triple(201, "1", emptyMap()) }
                p.startsWith("/magellan/gateways/issynchronized") -> Triple(200, """{"isSynchronized":true}""", emptyMap())
                p == "/magellan/executions/setups/7/attach-gateway" -> { att = true; Triple(201, "9", emptyMap()) }
                p == "/magellan/executions/9" -> Triple(200, """{"state":3}""", emptyMap())
                p == "/magellan/cozytouch/setupviewv2" -> Triple(200, if (!att) """[{"id":7,"devices":[]}]""" else """[{"id":7,"devices":[{"deviceId":77,"productId":41,"gatewaySerialNumber":"3151-0852-4955","capabilities":[]}]}]""", emptyMap())
                else -> Triple(404, "", emptyMap())
            }
        }
        val connector = object : ApConnector {
            override fun connect(target: ApTarget, timeoutMs: Long) =
                ApLink({ URL("http://127.0.0.1:1/").openConnection() as HttpURLConnection }, RedirectFactory(module.server.localPort))
            override fun disconnect() {}
        }
        val api = CozyApi(baseUrl = "http://127.0.0.1:${cloud.port}", sleeper = {})
        api.login("a@b.c", "x")
        var t = 0L
        val id = Pairer(api, connector, sleeper = { t += it }, clock = { t }).runBd1("3DN9C", "pw")
        check(id == 77L, "found device via candidate probing: $id")
        check(!created, "no gateway created for guessed pins")
        check(heads.size >= 10, "probed all candidates ${heads.size}")
        cloud.close(); module.server.close()
        assertEquals("невдалих перевірок", 0, failures)
    }
}
