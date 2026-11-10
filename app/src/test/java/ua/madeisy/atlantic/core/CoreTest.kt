package ua.madeisy.atlantic.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class CoreTest {
    private var failures = 0
    private fun check(cond: Boolean, msg: String) { if (cond) println("OK   $msg") else { println("FAIL $msg"); failures++ } }

    @Test
    fun coreAgainstFakeCloud() {
    // ---- JSON
    val j = Json.parse("""{"a":[1,2.5,"xA\n",true,null],"b":{"c":-3}}""").asMap()
    check(j["a"].asList()[0] == 1L && j["a"].asList()[1] == 2.5 && j["a"].asList()[2] == "xA\n", "json parse")
    check(Json.stringify(mapOf("k" to listOf(0, 21.0, 21.5, "q\"")) ) == """{"k":[0,21,21.5,"q\""]}""", "json stringify")

    // ---- Program
    val p = Program.parse("[[0,17],[420,21],[1320,18]]")
    check(p.size == 3 && p[1].time == "07:00" && p[1].temp == 21.0, "program parse")
    check(Program.encode(listOf(Slot(420, 21.5), Slot(0, 17.0))) == "[[0,17],[420,21.5]]", "program encode sorted")
    check(runCatching { Program.encode(listOf(Slot(60, 17.0))) }.isFailure, "program must start 00:00")
    check(Program.parseTime("7:05") == 425 && Program.parseTime("24:00") == null, "parse time")

    // ---- Fake cloud
    val writes = mutableListOf<String>()
    var polls = 0
    var tokenCalls = 0
    var setupPut: String? = null
    class Req(val path: String, val body: String, val auth: String)
    val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    fun handle(r: Req): Pair<Int, String> {
        val path = r.path; val body = r.body; val auth = r.auth
        return when {
            path == "/users/token" -> {
                tokenCalls++
                check(auth == "Basic ${CozyApi.CLIENT_BASIC}", "token uses basic client")
                check(body.contains("username=GA-PRIVATEPERSON%2Fme%40mail.com"), "token username prefix")
                if (body.contains("password=bad")) 400 to """{"error":"invalid_grant"}"""
                else 200 to """{"access_token":"T$tokenCalls","token_type":"Bearer","expires_in":3600}"""
            }
            !auth.startsWith("Bearer ") -> 401 to ""
            path == "/magellan/cozytouch/setupviewv2" -> 200 to SETUP
            path.startsWith("/magellan/capabilities/?deviceId=555") -> 200 to """[{"capabilityId":"117","value":"20.0"},{"capabilityId":40,"value":22}]"""
            path == "/magellan/executions/writecapability" -> { writes += body; 201 to "\"exec-${writes.size}\"" }
            path.startsWith("/magellan/executions/exec-") -> { polls++; 200 to (if (polls % 2 == 1) """{"state":2}""" else """{"state":3}""") }
            path == "/magellan/v2/setups/abc-1" -> { setupPut = body; 204 to "" }
            path.startsWith("/magellan/setups/abc-1/consumptions") -> 200 to
                """[{"consumptionPeriods":[{"consumedQuantity":4,"cost":0.5,"date":100},{"consumedQuantity":1.5,"date":200}]},{"consumptionPeriods":[{"consumedQuantity":1,"date":100}]}]"""
            else -> 404 to "nope $path"
        }
    }
    thread(isDaemon = true) {
        while (!server.isClosed) {
            val sock = runCatching { server.accept() }.getOrNull() ?: break
            sock.use { so ->
                val inp = so.getInputStream()
                val headerBytes = java.io.ByteArrayOutputStream()
                var last4 = 0
                while (true) {
                    val b = inp.read(); if (b < 0) break
                    headerBytes.write(b)
                    last4 = (last4 shl 8) or b
                    if (last4 == 0x0D0A0D0A) break
                }
                val lines = headerBytes.toString("UTF-8").split("\r\n")
                val path = lines[0].split(" ")[1]
                val headers = lines.drop(1).filter { it.contains(':') }
                    .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                val bodyBytes = ByteArray(len); var read = 0
                while (read < len) { val n = inp.read(bodyBytes, read, len - read); if (n < 0) break; read += n }
                val (code, resp) = handle(Req(path, String(bodyBytes, Charsets.UTF_8), headers["authorization"] ?: ""))
                val rb = resp.toByteArray(Charsets.UTF_8)
                val out = so.getOutputStream()
                out.write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${rb.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(rb); out.flush()
            }
        }
    }
    val api = CozyApi(baseUrl = "http://127.0.0.1:${server.localPort}", sleeper = {})

    check(runCatching { api.login("me@mail.com", "bad") }.exceptionOrNull()?.message?.contains("Невірний") == true, "bad password message")
    api.login("me@mail.com", "good")
    val setups = api.getSetups()
    val dev = setups[0].devices[0]
    check(dev.title == "ALTIS · Спальня" && dev.looksLikeHeater, "device parsed: ${dev.title}")
    val h = Heater(dev)
    check(h.currentTemp == 19.5 && h.target == 21.0 && h.isOn && h.programOn && h.isHeatingNow, "heater view")
    check(h.programDayStart == 196, "program block detected")
    check(h.clampTarget(30.0) == 28.0 && h.clampTarget(21.3) == 21.5, "clamp target")

    val fresh = api.getCapabilities(555)
    check(fresh.first { it.id == 40 }.value == "22" && fresh.first { it.id == 117 }.value == "20.0", "capabilities numeric/str ids")

    h.setTarget(api, 22.0)
    check(writes.size == 3 && writes[0].contains("\"capabilityId\":158") && writes[1].contains("\"capabilityId\":157") &&
        writes[2] == """{"capabilityId":40,"deviceId":555,"value":"22.0"}""", "setTarget in program -> override then 40: $writes")

    writes.clear(); h.setPower(api, false)
    check(writes.single().contains("\"value\":\"0\"") && writes.single().contains(":7,"), "power off")

    writes.clear(); h.writeDay(api, 0, listOf(Slot(0, 16.0), Slot(390, 21.0)))
    check(writes.single().contains("\"capabilityId\":196") && writes.single().contains("\"value\":\"[[0,16],[390,21]]\""), "write day: ${writes.single()}")

    writes.clear(); h.setAway(api, setups[0], 1000, 2000, 500)
    check(setupPut!!.contains("\"absence\":{\"startDate\":1000,\"endDate\":2000}") && setupPut!!.contains("\"name\":\"Дім\""), "absence put: $setupPut")
    check(writes[0].contains("[1000,2000]") && writes[1].contains(":152,") && writes[1].contains("\"value\":\"2\""), "away mirrored: $writes")

    val cons = api.getConsumptionsDaily("abc-1")
    check(cons.size == 2 && cons[0].kwh == 5.0 && cons[1].kwh == 1.5, "consumptions grouped")

    check(Catalog.format(117, "19.5") == "19.5 °C" && Catalog.format(153, "1") == "Гріє" && Catalog.format(60, "12500") == "12.5 кВт·год", "formatting")
    check(Catalog.format(99999, "x") == "x" && !Catalog.isKnown(99999), "unknown capability raw")
    print(h.dump().lines().take(3).joinToString("\n")); println()

    server.close()
    assertEquals("невдалих перевірок", 0, failures)
    }
}

private const val SETUP = """[{"id":"abc-1","name":"Дім","zones":[{"id":"z1","name":"Спальня"}],
                "devices":[{"deviceId":555,"modelId":2401,"productId":41,"name":"ALTIS","zoneId":"z1",
                "capabilities":[{"capabilityId":7,"value":"4"},{"capabilityId":40,"value":"21.0"},{"capabilityId":117,"value":"19.5"},
                {"capabilityId":160,"value":"7"},{"capabilityId":161,"value":"28"},{"capabilityId":184,"value":"1"},{"capabilityId":157,"value":"0"},
                {"capabilityId":158,"value":"120"},{"capabilityId":153,"value":"1"},{"capabilityId":104044,"value":"0"},{"capabilityId":222,"value":"[0,0]"},{"capabilityId":152,"value":"0"},
                {"capabilityId":196,"value":"[[0,17],[420,21]]"},{"capabilityId":197,"value":"[[0,17]]"},{"capabilityId":198,"value":"[[0,17]]"},
                {"capabilityId":199,"value":"[[0,17]]"},{"capabilityId":200,"value":"[[0,17]]"},{"capabilityId":201,"value":"[[0,17]]"},{"capabilityId":202,"value":"[[0,17]]"},
                {"capabilityId":99999,"value":"x"}]}]}]"""
