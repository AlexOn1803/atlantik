package ua.madeisy.atlantic.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread

/** Мінімальний HTTP-сервер для тестів (без com.sun.*, щоб працювало і під Android unit tests). */
private class FakeHttp(val handler: (method: String, path: String, body: String) -> Triple<Int, String, Map<String, String>>) {
    val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port get() = server.localPort

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                sock.use { so ->
                    val inp = so.getInputStream()
                    val head = java.io.ByteArrayOutputStream()
                    var last4 = 0
                    while (true) {
                        val b = inp.read(); if (b < 0) break
                        head.write(b); last4 = (last4 shl 8) or b
                        if (last4 == 0x0D0A0D0A) break
                    }
                    val lines = head.toString("UTF-8").split("\r\n")
                    val (method, path) = lines[0].split(" ").let { it[0] to it[1] }
                    val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                    val len = headers["content-length"]?.toIntOrNull() ?: 0
                    val bb = ByteArray(len); var r = 0
                    while (r < len) { val n = inp.read(bb, r, len - r); if (n < 0) break; r += n }
                    val (code, resp, extra) = handler(method, path, String(bb, Charsets.UTF_8))
                    val rb = if (method == "HEAD") ByteArray(0) else resp.toByteArray(Charsets.UTF_8)
                    val sb = StringBuilder("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${rb.size}\r\nConnection: close\r\n")
                    extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
                    sb.append("\r\n")
                    so.getOutputStream().apply { write(sb.toString().toByteArray()); write(rb); flush() }
                }
            }
        }
    }
    fun close() = server.close()
}

class PairingTest {
    private var failures = 0
    private fun check(cond: Boolean, msg: String) { if (cond) println("OK   $msg") else { println("FAIL $msg"); failures++ } }

    @Test
    fun pinAndQrCodes() {
        val ap = PairingCodes.fromPin("1234-5678-9012")
        check(ap.ssid == "GA-123456789012" && ap.password == "876543210987" && ap.serial == "1234-5678-9012", "pin -> ap $ap")
        val q = PairingCodes.fromQr("\$V:1%M:x\$S:GA-111122223333\$P:888877776666")
        check(q?.ssid == "GA-111122223333" && q.password == "888877776666" && q.serial == "1111-2222-3333", "qr -> ap $q")
        val q2 = PairingCodes.fromQr("V:2\$P:123456789012")
        check(q2 == PairingCodes.fromPin("123456789012"), "qr with pin -> derived $q2")
        check(PairingCodes.fromQr("PIN 1234-5678-9012") == PairingCodes.fromPin("123456789012"), "plain pin text")
        check(runCatching { PairingCodes.fromPin("123") }.isFailure, "short pin rejected")
        assertEquals("невдалих перевірок", 0, failures)
    }

    @Test
    fun fullPairingFlow() {
        val calls = mutableListOf<String>()
        var created = false
        var attached = false
        var statusPolls = 0
        var deviceVisible = false
        var credentialBody = ""

        val cloud = FakeHttp { m, p, b ->
            calls += "$m $p"
            when {
                p == "/users/token" -> Triple(200, """{"access_token":"T","expires_in":3600}""", emptyMap())
                p == "/magellan/setups" -> Triple(200, """[{"id":42,"name":"Дім"}]""", emptyMap())
                m == "HEAD" && p.startsWith("/magellan/gateways/?serialNumber=1234-5678-9012") ->
                    if (created) Triple(200, "", mapOf("Location" to "/magellan/gateways/777")) else Triple(404, "", emptyMap())
                m == "POST" && p == "/magellan/gateways" -> { check(b == """{"serialNumber":"1234-5678-9012"}""", "create body $b"); created = true; Triple(201, "777", emptyMap()) }
                p == "/magellan/executions/setups/42/attach-gateway" -> { check(b == """{"gatewayId":777}""", "attach body $b"); attached = true; Triple(201, "55", emptyMap()) }
                p == "/magellan/executions/55" -> Triple(200, """{"state":3}""", emptyMap())
                p == "/magellan/cozytouch/setupviewv2" -> Triple(200, if (deviceVisible)
                    """[{"id":42,"devices":[{"deviceId":9001,"productId":41,"name":"ALTIS","gatewaySerialNumber":"1234-5678-9012","capabilities":[]}]}]"""
                else """[{"id":42,"devices":[]}]""", emptyMap())
                p.endsWith("/discover-status") -> { deviceVisible = true; Triple(200, """{"discoverStatus":2,"id":777}""", emptyMap()) }
                p.contains("discover") || p.contains("autoconfigure") -> Triple(201, "1", emptyMap())
                else -> Triple(404, "", emptyMap())
            }
        }
        val device = FakeHttp { m, p, b ->
            calls += "AP $m $p"
            when (p) {
                "/passthrough/info" -> Triple(200, """{"success":true}""", emptyMap())
                "/passthrough/remove_credential" -> Triple(200, """{"success":true}""", emptyMap())
                "/passthrough/credential" -> { credentialBody = b; Triple(200, """{"success":true}""", emptyMap()) }
                "/passthrough/status" -> { statusPolls++; Triple(200, if (statusPolls < 2) """{"connectivity_status":"fai"}""" else """{"connectivity_status":"cloud"}""", emptyMap()) }
                "/passthrough/close_ap" -> Triple(200, """{"success":true}""", emptyMap())
                else -> Triple(404, "", emptyMap())
            }
        }
        var connectedTo: Pair<String, String>? = null
        var disconnected = false
        val connector = object : ApConnector {
            override fun connect(ssid: String, password: String, timeoutMs: Long): (URL) -> HttpURLConnection {
                connectedTo = ssid to password
                return { url -> URL("http://127.0.0.1:${device.port}${url.path}").openConnection() as HttpURLConnection }
            }
            override fun disconnect() { disconnected = true }
        }
        var t = 0L
        val api = CozyApi(baseUrl = "http://127.0.0.1:${cloud.port}", sleeper = {})
        api.login("me@mail.com", "pw")
        val steps = mutableListOf<PairStep>()
        val id = Pairer(api, connector, sleeper = { t += it }, clock = { t }, onStep = { steps += it })
            .run(PairingCodes.fromPin("123456789012"), "MyWiFi", "secret")

        check(id == 9001L, "new device found: $id")
        check(created && attached, "gateway created and attached")
        check(connectedTo == ("GA-123456789012" to "876543210987"), "AP creds $connectedTo")
        check(credentialBody == """{"ssid":"MyWiFi","password":"secret","connect":"cloud"}""", "credential body $credentialBody")
        check(disconnected, "AP released")
        check(steps == PairStep.values().toList(), "all steps in order $steps")
        val order = calls.map { it.substringBefore("?") }
        check(order.indexOf("POST /magellan/executions/setups/42/attach-gateway") < order.indexOf("AP POST /passthrough/credential"), "attach before sending wifi")
        cloud.close(); device.close()
        assertEquals("невдалих перевірок", 0, failures)
    }

    @Test
    fun foreignAccountIsExplained() {
        val cloud = FakeHttp { m, p, _ ->
            when {
                p == "/users/token" -> Triple(200, """{"access_token":"T","expires_in":3600}""", emptyMap())
                m == "HEAD" -> Triple(403, "", emptyMap())
                else -> Triple(404, "", emptyMap())
            }
        }
        val api = CozyApi(baseUrl = "http://127.0.0.1:${cloud.port}", sleeper = {})
        api.login("a@b.c", "x")
        val e = runCatching { api.findGatewayId("1234-5678-9012") }.exceptionOrNull()
        check(e?.message?.contains("іншого акаунта") == true, "403 explained: ${e?.message}")
        cloud.close()
        assertEquals("невдалих перевірок", 0, failures)
    }
}
