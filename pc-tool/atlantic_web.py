# -*- coding: utf-8 -*-
"""
Atlantic ALTIS (CHG-BD1/Wi-Fi) — інструмент для ПК з веб-інтерфейсом.

Запуск:  python atlantic_web.py      (або AtlanticPC.exe)
Відкриється http://localhost:8765 у браузері.

Що вміє:
  * показати Wi-Fi адаптери Windows, знайти мережу ELEC_HEATER_Wifi_xxxx і підключити до неї
    вибраний адаптер (другий USB-адаптер — щоб інтернет лишався на основному);
  * поговорити з модулем обігрівача (ws://192.168.1.99:9998): прочитати PIN, регістри, передати Wi-Fi;
  * хмара Atlantic (Cozytouch): вхід, список, пошук/реєстрація шлюзу, синхронізація, прив'язка;
  * «Все автоматично» — повний сценарій з журналом.

Лише стандартна бібліотека Python 3.8+. Дані входу зберігаються тільки в пам'яті.
"""
import base64
import http.client
import json
import os
import re
import socket
import subprocess
import sys
import tempfile
import threading
import time
import traceback
import urllib.parse
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT_UI = int(os.environ.get("ATLANTIC_UI_PORT", "8765"))
HEATER_HOST = os.environ.get("ATLANTIC_HEATER_HOST", "192.168.1.99")
HEATER_PORT = int(os.environ.get("ATLANTIC_HEATER_PORT", "9998"))
CLOUD = os.environ.get("ATLANTIC_CLOUD", "https://apis.groupe-atlantic.com")
CLIENT_BASIC = "Q3RfMUpWeVRtSUxYOEllZkE3YVVOQmpGblpVYToyRWNORHpfZHkzNDJVSnFvMlo3cFNKTnZVdjBh"
AP_PREFIX = "ELEC_HEATER_Wifi_"
AP_PASSWORD = "WIFIHVAC"

# ─────────────────────────────── журнал ───────────────────────────────
LOG = []
LOG_LOCK = threading.Lock()


def log(msg):
    line = time.strftime("%H:%M:%S ") + str(msg)
    print(line, flush=True)
    with LOG_LOCK:
        LOG.append(line)
        if len(LOG) > 3000:
            del LOG[:1000]


class Fail(Exception):
    pass


# ─────────────────────────── Wi-Fi Windows (netsh) ───────────────────────────
IS_WIN = sys.platform.startswith("win")


def netsh(*args, timeout=30):
    if not IS_WIN:
        raise Fail("Керування Wi-Fi працює лише на Windows. Підключіться до мережі обігрівача вручну.")
    flags = 0x08000000  # CREATE_NO_WINDOW
    p = subprocess.run(["netsh", "wlan", *args], capture_output=True, timeout=timeout, creationflags=flags)
    raw = p.stdout + p.stderr
    for enc in ("utf-8", "oem", "cp866", "cp1251"):
        try:
            return raw.decode(enc, "strict")
        except Exception:
            continue
    return raw.decode("utf-8", "replace")


def _blocks(text):
    blocks, cur = [], []
    for line in text.splitlines():
        if not line.strip():
            if cur:
                blocks.append(cur)
                cur = []
            continue
        cur.append(line)
    if cur:
        blocks.append(cur)
    return blocks


def _kv(line):
    if " : " not in line and not re.search(r"\s:\s*$", line):
        return None
    k, _, v = line.partition(":")
    return k.strip(), v.strip()


def wifi_interfaces():
    out = netsh("show", "interfaces")
    res = []
    for b in _blocks(out):
        kvs = [kv for kv in (_kv(l) for l in b) if kv]
        if len(kvs) < 3:
            continue
        d = {"name": kvs[0][1], "fields": kvs}
        for k, v in kvs:
            kl = k.lower()
            if kl == "ssid":
                d["ssid"] = v
            if kl in ("state", "стан", "состояние"):
                d["state"] = v
            if "description" in kl or "опис" in kl or "описание" in kl:
                d["description"] = v
            if kl in ("channel", "канал"):
                d["channel"] = v
            if "signal" in kl or "сигнал" in kl:
                d["signal"] = v
        res.append(d)
    return res, out


def wifi_scan(interface=None):
    args = ["show", "networks", "mode=bssid"]
    if interface:
        args.append(f"interface={interface}")
    out = netsh(*args)
    nets, cur = [], None
    for line in out.splitlines():
        m = re.match(r"^\s*SSID\s+\d+\s*:\s*(.*)$", line)
        if m:
            cur = {"ssid": m.group(1).strip(), "signal": None, "channel": None, "auth": None, "band": None}
            nets.append(cur)
            continue
        if cur is None:
            continue
        kv = _kv(line)
        if not kv:
            continue
        k, v = kv[0].lower(), kv[1]
        if ("signal" in k or "сигнал" in k) and cur["signal"] is None:
            cur["signal"] = v
        elif k in ("channel", "канал") and cur["channel"] is None:
            cur["channel"] = v
        elif ("authentication" in k or "автентиф" in k or "проверка подлинности" in k) and cur["auth"] is None:
            cur["auth"] = v
        elif ("band" in k or "діапазон" in k or "диапазон" in k) and cur["band"] is None:
            cur["band"] = v
    return nets, out


def _xml_escape(s):
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;").replace("'", "&apos;"))


def wifi_connect(interface, ssid, key=None):
    name = _xml_escape(ssid)
    if key:
        sec = (f"<authEncryption><authentication>WPA2PSK</authentication><encryption>AES</encryption>"
               f"<useOneX>false</useOneX></authEncryption><sharedKey><keyType>passPhrase</keyType>"
               f"<protected>false</protected><keyMaterial>{_xml_escape(key)}</keyMaterial></sharedKey>")
    else:
        sec = ("<authEncryption><authentication>open</authentication><encryption>none</encryption>"
               "<useOneX>false</useOneX></authEncryption>")
    xml = (f'<?xml version="1.0"?>\n<WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1">'
           f"<name>{name}</name><SSIDConfig><SSID><name>{name}</name></SSID></SSIDConfig>"
           f"<connectionType>ESS</connectionType><connectionMode>manual</connectionMode>"
           f"<MSM><security>{sec}</security></MSM></WLANProfile>")
    fd, path = tempfile.mkstemp(suffix=".xml")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(xml)
        args = ["add", "profile", f"filename={path}", "user=current"]
        if interface:
            args.append(f"interface={interface}")
        log("netsh add profile: " + netsh(*args).strip().replace("\n", " "))
    finally:
        try:
            os.remove(path)
        except OSError:
            pass
    args = ["connect", f"name={ssid}", f"ssid={ssid}"]
    if interface:
        args.append(f"interface={interface}")
    out = netsh(*args).strip()
    log("netsh connect: " + out.replace("\n", " "))
    return out


def wait_heater_reachable(seconds=25):
    end = time.time() + seconds
    last = None
    while time.time() < end:
        try:
            socket.create_connection((HEATER_HOST, HEATER_PORT), timeout=3).close()
            return True
        except OSError as e:
            last = e
            time.sleep(1.5)
    log(f"{HEATER_HOST}:{HEATER_PORT} недоступний: {last}")
    return False


# ─────────────────────── модуль обігрівача (WebSocket) ───────────────────────
def b64(b):
    return base64.b64encode(b).decode()


def enc_str(s, size):
    raw = s.encode("utf-8")
    if len(raw) > size:
        raise Fail(f"Задовге значення ({len(raw)} байт, максимум {size})")
    return b64(raw + b"\0" * (size - len(raw)))


def enc_int(v):
    return b64(bytes([(v >> 8) & 0xFF, v & 0xFF]))


def cmd(cid, function, start, data=None, quantity=None, data_size=None):
    value = {"startingAddress": start}
    if data is not None:
        value["data"] = data
    if quantity is not None:
        value["quantity"] = quantity
    if data_size is not None:
        value["dataSize"] = data_size
    return {"command": {"id": cid, "nodeId": 1, "function": function, "value": value}}


def envelope(cmds):
    return json.dumps({"modbuslink": [{"execution": {"commands": cmds}}]}, separators=(",", ":"))


def msg_read(start, qty):
    return envelope([cmd("1", 3, start, quantity=qty)])


def msg_wifi(ssid, password):
    cmds = [cmd("0", 6, 134, enc_int(3))]
    if password:
        cmds.append(cmd("1", 16, 98, enc_str(password, 64), 32, 16))
    cmds.append(cmd("2", 16, 82, enc_str(ssid, 32), 16, 16))
    return envelope(cmds)


def parse_reply(text):
    try:
        link = json.loads(text)["box"]["modules"][0]["modbuslink"][0]
    except Exception:
        return {"kind": "OTHER"}
    if "commandResult" in link:
        r = link["commandResult"].get("result")
        return {"kind": "OK" if r in (4, 5) else "ERROR", "result": r}
    if "ack" in link:
        return {"kind": "ACK"}
    if "states" in link:
        try:
            v = link["states"]["values"][0]["value"]
            raw = base64.b64decode(v)
        except Exception:
            raw = b""
        return {"kind": "DATA", "bytes": raw}
    return {"kind": "OTHER"}


class WS:
    def __init__(self, host, port, timeout=8):
        self.s = socket.create_connection((host, port), timeout=timeout)
        key = b64(os.urandom(16))
        req = (f"GET / HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
               f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n")
        self.s.sendall(req.encode())
        head = b""
        while not head.endswith(b"\r\n\r\n"):
            c = self.s.recv(1)
            if not c:
                raise Fail("WebSocket: з'єднання закрито під час рукостискання")
            head += c
            if len(head) > 8192:
                raise Fail("WebSocket: завелика відповідь")
        status = head.split(b"\r\n", 1)[0].decode(errors="replace")
        if " 101" not in status:
            raise Fail(f"WebSocket: сервер відповів «{status}»")

    def _frame(self, opcode, payload):
        n = len(payload)
        hdr = bytes([0x80 | opcode])
        if n < 126:
            hdr += bytes([0x80 | n])
        elif n < 65536:
            hdr += bytes([0x80 | 126]) + n.to_bytes(2, "big")
        else:
            hdr += bytes([0x80 | 127]) + n.to_bytes(8, "big")
        mask = os.urandom(4)
        self.s.sendall(hdr + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

    def send(self, text):
        self._frame(1, text.encode("utf-8"))

    def _exact(self, n):
        buf = b""
        while len(buf) < n:
            c = self.s.recv(n - len(buf))
            if not c:
                return None
            buf += c
        return buf

    def recv(self, timeout):
        self.s.settimeout(timeout)
        msg = b""
        while True:
            h = self._exact(2)
            if h is None:
                return None
            op, ln = h[0] & 0x0F, h[1] & 0x7F
            if ln == 126:
                ln = int.from_bytes(self._exact(2), "big")
            elif ln == 127:
                ln = int.from_bytes(self._exact(8), "big")
            mask = self._exact(4) if h[1] & 0x80 else None
            data = self._exact(ln) if ln else b""
            if data is None:
                return None
            if mask:
                data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
            if op == 8:
                return None
            if op == 9:
                self._frame(0xA, data)
                continue
            if op in (0, 1, 2):
                msg += data
                if h[0] & 0x80:
                    return msg.decode("utf-8", "replace")

    def close(self):
        try:
            self._frame(8, b"")
        except Exception:
            pass
        try:
            self.s.close()
        except Exception:
            pass


def heater_session(payload, want, timeout=10):
    ws = WS(HEATER_HOST, HEATER_PORT)
    try:
        log("WS → " + (re.sub(r'"data":"[^"]*"', '"data":"…"', payload) if '"startingAddress":98' in payload else payload))
        ws.send(payload)
        end = time.time() + timeout
        while time.time() < end:
            try:
                m = ws.recv(max(0.5, end - time.time()))
            except socket.timeout:
                break
            if m is None:
                log("WS: модуль закрив з'єднання")
                break
            log("WS ← " + m[:400])
            r = parse_reply(m)
            if r["kind"] == "ERROR" or want(r):
                return r
        return None
    finally:
        ws.close()


def show_bytes(b):
    txt = "".join(chr(c) if 32 <= c < 127 else f"\\x{c:02x}" for c in b)
    return {"text": txt, "hex": b.hex(" ")}


def heater_read(start, qty):
    r = heater_session(msg_read(start, qty), lambda x: x["kind"] == "DATA", 8)
    if not r:
        raise Fail("Модуль не відповів")
    if r["kind"] == "ERROR":
        raise Fail(f"Модуль повернув помилку (result {r.get('result')})")
    return r["bytes"]


def heater_pin():
    raw = heater_read(180, 6)
    digits = "".join(ch for ch in raw.decode("latin-1") if ch.isdigit())
    shown = show_bytes(raw)
    log(f"PIN сирий: {shown['text']}  [{shown['hex']}]")
    pin = digits if len(digits) == 12 else None
    if pin is None and len(digits) == 11:
        pin = "0" + digits
        log(f"Перший символ зіпсований — припускаю 0: {pin} (звірте з екраном обігрівача: WIFI SN)")
    return {"raw": shown, "pin": pin}


def heater_send_wifi(ssid, password):
    """Як Cozytouch: надіслати 3 команди і НЕ закривати з'єднання, поки модуль 4 с мовчить
    (або сам закриє). Повертає {commandId: result}."""
    payload = msg_wifi(ssid, password)
    expected = [c["command"]["id"] for c in json.loads(payload)["modbuslink"][0]["execution"]["commands"]]
    ws = WS(HEATER_HOST, HEATER_PORT)
    results = {}
    try:
        log("WS → " + re.sub(r'"data":"[^"]*"', '"data":"…"', payload))
        ws.send(payload)
        hard_end = time.time() + 30
        while time.time() < hard_end:
            try:
                m = ws.recv(4.0)
            except socket.timeout:
                log("Модуль 4 с мовчить — завершую, як Cozytouch")
                break
            if m is None:
                log("Модуль закрив з'єднання (перемикається на роутер)")
                break
            log("WS ← " + m[:300])
            try:
                cr = json.loads(m)["box"]["modules"][0]["modbuslink"][0].get("commandResult")
            except Exception:
                cr = None
            if cr:
                results.setdefault(str(cr.get("commandId")), []).append(cr.get("result"))
            if all(5 in results.get(i, []) for i in expected):
                log("Усі команди виконані (result 5) — чекаю, щоб модуль застосував")
                try:
                    ws.recv(4.0)
                except Exception:
                    pass
                break
    finally:
        ws.close()
    summary = ", ".join(f"cmd {i}: {results.get(i, '—')}" for i in expected)
    log("Підсумок команд: " + summary + "  (4 = прийнято, 5 = виконано)")
    bad = [i for i in expected if any(r not in (4, 5) for r in results.get(i, []))]
    if bad:
        raise Fail(f"Модуль відхилив команди {bad}: {summary}")
    if any(5 not in results.get(i, []) for i in expected):
        log("УВАГА: не всі команди підтверджені як виконані")
    return summary


# ─────────────────────────────── хмара ───────────────────────────────
class Cloud:
    def __init__(self, base):
        u = urllib.parse.urlparse(base)
        self.https = u.scheme == "https"
        self.host = u.netloc
        self.token = None
        self.exp = 0
        self.email = None
        self.pw = None

    def http(self, method, path, body=None, headers=None):
        conn = (http.client.HTTPSConnection if self.https else http.client.HTTPConnection)(self.host, timeout=30)
        h = {"Accept": "application/json"}
        h.update(headers or {})
        data = body.encode("utf-8") if isinstance(body, str) else body
        try:
            conn.request(method, path, body=data, headers=h)
            r = conn.getresponse()
            text = "" if method == "HEAD" else r.read().decode("utf-8", "replace")
            loc = r.getheader("Location")
            log(f"{method} {path.split('?')[0]} -> {r.status}" + (f" Location={loc}" if loc else "")
                + (f" {text[:200]}" if text and method != "GET" else ""))
            return r.status, text, loc
        except OSError as e:
            raise Fail(f"Немає зв'язку з хмарою Atlantic: {e}. Можливо, інтернет зараз іде через адаптер обігрівача?")
        finally:
            conn.close()

    def login(self, email, pw):
        body = urllib.parse.urlencode({"grant_type": "password", "scope": "openid",
                                       "username": "GA-PRIVATEPERSON/" + email, "password": pw})
        code, text, _ = self.http("POST", "/users/token", body, {
            "Authorization": "Basic " + CLIENT_BASIC, "Content-Type": "application/x-www-form-urlencoded"})
        if code in (400, 401):
            raise Fail("Невірний email або пароль Cozytouch")
        if code // 100 != 2:
            raise Fail(f"Вхід: HTTP {code} {text[:200]}")
        j = json.loads(text)
        self.token = j["access_token"]
        self.exp = time.time() + int(j.get("expires_in", 3600)) - 60
        self.email, self.pw = email, pw
        log("Вхід у Cozytouch успішний")

    def authed(self, method, path, body=None):
        if not self.token:
            raise Fail("Спершу увійдіть у Cozytouch")
        if time.time() > self.exp:
            self.login(self.email, self.pw)
        h = {"Authorization": "Bearer " + self.token, "Content-Type": "application/json"}
        code, text, loc = self.http(method, path, body, h)
        if code == 401:
            self.login(self.email, self.pw)
            h["Authorization"] = "Bearer " + self.token
            code, text, loc = self.http(method, path, body, h)
        return code, text, loc

    @staticmethod
    def js(text):
        try:
            return json.loads(text) if text.strip() else None
        except Exception:
            return text

    def setups_view(self):
        code, text, _ = self.authed("GET", "/magellan/cozytouch/setupviewv2")
        if code // 100 != 2:
            raise Fail(f"setupviewv2: HTTP {code}")
        return self.js(text)

    def setup_ids(self):
        code, text, _ = self.authed("GET", "/magellan/setups")
        j = self.js(text)
        ids = []
        if isinstance(j, list):
            for s in j:
                if isinstance(s, dict) and s.get("id") is not None:
                    ids.append(int(s["id"]))
        if not ids:
            v = self.setups_view()
            for s in v if isinstance(v, list) else []:
                if isinstance(s, dict) and s.get("id") is not None:
                    ids.append(int(s["id"]))
        return ids

    def find_gateway(self, serial):
        code, _, loc = self.authed("HEAD", "/magellan/gateways/?serialNumber=" + urllib.parse.quote(serial))
        if loc:
            tail = loc.rstrip("/").rsplit("/", 1)[-1]
            if tail.isdigit():
                return {"code": code, "gatewayId": int(tail)}
        msg = {404: "хмара не знає цього приладу (ще не зареєстрований)",
               403: "прилад прив'язаний до ІНШОГО акаунта — потрібна відв'язка через попереднього власника/підтримку",
               451: "на приладі сервісний контракт — лише через підтримку Atlantic"}.get(code, "")
        return {"code": code, "gatewayId": None, "note": msg}

    def create_gateway(self, serial):
        code, text, loc = self.authed("POST", "/magellan/gateways", json.dumps({"serialNumber": serial}))
        if code // 100 != 2:
            raise Fail(f"Реєстрація шлюзу: HTTP {code} {text[:200]}")
        j = self.js(text)
        if isinstance(j, int):
            return j
        if isinstance(j, str) and j.strip('"').isdigit():
            return int(j.strip('"'))
        if loc and loc.rstrip("/").rsplit("/", 1)[-1].isdigit():
            return int(loc.rstrip("/").rsplit("/", 1)[-1])
        f = self.find_gateway(serial)
        if f["gatewayId"]:
            return f["gatewayId"]
        raise Fail("Хмара не повернула id шлюзу: " + text[:200])

    SYNC_PATHS = ["/magellan/gateways/issynchronized?serialNumber={s}",
                  "/magellan/gateways/issynchronized?gatewaySerialNumber={s}",
                  "/magellan/gateways/issynchronized?pinid={s}",
                  "/magellan/v2/gateways/issynchronized?serialNumber={s}",
                  "/magellan/gateways/issynchronized/{s}",
                  "/magellan/gateways/{s}/issynchronized"]
    sync_path = None

    def is_synced(self, serial):
        q = urllib.parse.quote(serial)
        paths = [self.sync_path] if self.sync_path else self.SYNC_PATHS
        code, text = None, ""
        for tpl in paths:
            code, text, _ = self.authed("GET", tpl.format(s=q))
            if code != 405:
                if self.sync_path is None:
                    log(f"issynchronized: робочий шлях {tpl}")
                self.sync_path = tpl
                break
        log(f"issynchronized -> {code} {text[:120]}")
        if code is None or code // 100 != 2:
            return None
        j = self.js(text)
        if isinstance(j, bool):
            return j
        if isinstance(j, dict):
            return j.get("isSynchronized")
        if isinstance(j, str):
            return j.strip().lower() == "true"
        return None

    def wait_exec(self, eid, polls=90):
        for _ in range(polls):
            time.sleep(2)
            _, text, _ = self.authed("GET", f"/magellan/executions/{eid}")
            st = (self.js(text) or {}).get("state") if isinstance(self.js(text), dict) else None
            if st == 3:
                return
            if st not in (1, 2, None):
                raise Fail("Хмара відхилила виконання: " + text[:200])
        raise Fail("Хмара не завершила виконання вчасно")

    def attach(self, setup_id, gw_id):
        body = json.dumps({"gatewayId": gw_id})
        code, text, _ = self.authed("POST", f"/magellan/executions/setups/{setup_id}/attach-gateway", body)
        if code // 100 == 2:
            j = self.js(text)
            eid = j.get("executionId") or j.get("id") if isinstance(j, dict) else j
            if eid not in (None, ""):
                self.wait_exec(str(eid).strip('"'))
            return "ok"
        if code in (429, 451):
            raise Fail(f"Прив'язка: HTTP {code} {text[:200]}")
        log("Пробую старий спосіб прив'язки")
        h = {"Authorization": "Bearer " + self.token, "Content-Type": "application/json", "bypassgam": "false"}
        code2, text2, _ = self.http("POST", f"/magellan/cozytouch/setups/{setup_id}/attachgateway", body, h)
        if code2 // 100 != 2 and "already" not in text2.lower():
            raise Fail(f"Прив'язка не вдалася: HTTP {code2} {text2[:200]}")
        return "ok"

    def discover(self, gw_id):
        for m, p, b in (("POST", f"/magellan/executions/gateways/{gw_id}/discover", '""'),
                        ("POST", f"/magellan/devices/discover?gatewayId={gw_id}", '""'),
                        ("POST", "/magellan/zones/autoconfigure", json.dumps({"gatewayId": gw_id}))):
            try:
                self.authed(m, p, b)
            except Fail as e:
                log(str(e))


CLOUD_API = Cloud(CLOUD)


def fmt_serial(pin):
    d = re.sub(r"\D", "", pin or "")
    if len(d) != 12:
        raise Fail("PIN-id має бути 12 цифр (на обігрівачі: WIFI SN)")
    return f"{d[0:4]}-{d[4:8]}-{d[8:12]}"


# ─────────────────────────── автоматичний сценарій ───────────────────────────
AUTO = {"running": False}


def auto_flow(p):
    try:
        AUTO["running"] = True
        iface = p.get("interface") or None
        ssid, pw = p["ssid"].strip(), p.get("password", "")
        if not ssid:
            raise Fail("Вкажіть назву домашньої мережі")
        if not CLOUD_API.token:
            raise Fail("Спершу увійдіть у Cozytouch (розділ «Хмара»)")
        setups = CLOUD_API.setup_ids()
        if not setups:
            raise Fail("В акаунті немає жодного будинку (setup). Створіть його в Cozytouch.")
        log(f"Будинки акаунта: {setups}")

        if wait_heater_reachable(3):
            log("Модуль обігрівача вже доступний — підключення до його мережі пропускаю")
        elif p.get("connectAp") and IS_WIN:
            nets, _ = wifi_scan(iface)
            ap = next((n["ssid"] for n in nets if n["ssid"].startswith(AP_PREFIX)), None)
            if not ap:
                raise Fail("Мережу ELEC_HEATER_Wifi_… не видно. Увімкніть на обігрівачі Меню → Зв'язок → Підключення.")
            log(f"Підключаю {iface or 'адаптер'} до {ap}")
            wifi_connect(iface, ap)
            if not wait_heater_reachable(20):
                log("Без пароля не вийшло — пробую WIFIHVAC")
                wifi_connect(iface, ap, AP_PASSWORD)
                if not wait_heater_reachable(20):
                    raise Fail("Модуль 192.168.1.99 недоступний. Перевірте, що адаптер підключився до ELEC_HEATER.")
        elif not wait_heater_reachable(5):
            raise Fail("Модуль 192.168.1.99 недоступний. Підключіть ПК до мережі ELEC_HEATER_Wifi_….")

        pin = re.sub(r"\D", "", p.get("pin") or "")
        info = heater_pin()
        if len(pin) != 12:
            pin = info["pin"]
        if not pin:
            raise Fail("Не вдалося визначити PIN. Введіть WIFI SN з екрана обігрівача.")
        serial = fmt_serial(pin)
        log(f"Серійний номер шлюзу: {serial}")

        f = CLOUD_API.find_gateway(serial)
        if f["code"] in (403, 451):
            raise Fail(f["note"])

        log(f"Передаю Wi-Fi «{ssid}» модулю…")
        log("Результат: " + heater_send_wifi(ssid, pw))

        ap_name = None
        if IS_WIN:
            try:
                ifs, _ = wifi_interfaces()
                ap_name = next((i.get("ssid") for i in ifs if (i.get("ssid") or "").startswith(AP_PREFIX)), None)
            except Exception:
                pass

        gw = f["gatewayId"]
        if gw is not None:
            log(f"Шлюз уже відомий хмарі: id {gw}")

        t0 = time.time()
        synced = False
        ap_gone_logged = False
        while time.time() - t0 < 300:
            time.sleep(8)
            if IS_WIN and not ap_gone_logged and time.time() - t0 > 30:
                try:
                    nets, _ = wifi_scan(iface)
                    still = [n for n in nets if n["ssid"].startswith(AP_PREFIX)]
                    if still:
                        log(f"Мережа обігрівача ще в ефірі ({still[0]['ssid']}, сигнал {still[0]['signal']}) — "
                            f"модуль, схоже, не перейшов на роутер.")
                    else:
                        log("Мережа ELEC_HEATER зникла — модуль перейшов на роутер.")
                        ap_gone_logged = True
                except Exception as e:
                    log(f"scan: {e}")
            if gw is None:
                fg = CLOUD_API.find_gateway(serial)
                gw = fg["gatewayId"]
                if gw is None and fg["code"] == 404:
                    try:
                        gw = CLOUD_API.create_gateway(serial)
                        log(f"Шлюз зареєстровано: id {gw}")
                    except Fail as e:
                        if "UnknownGatewayType" in str(e) or "Unknown gateway type" in str(e):
                            log("Хмара ще не знає тип шлюзу = модуль жодного разу не виходив в інтернет. Чекаю…")
                        else:
                            raise
            s = CLOUD_API.is_synced(serial)
            if s and gw is None:
                fg = CLOUD_API.find_gateway(serial)
                gw = fg["gatewayId"] or (CLOUD_API.create_gateway(serial) if fg["code"] == 404 else None)
            if s and gw is not None:
                synced = True
                break
        if not synced:
            raise Fail("Хмара не побачила обігрівач за 5 хв. Він не підключився до роутера: перевірте пароль, "
                       "2.4 ГГц, канал 1–11, 20 МГц, WPA2-AES. Подивіться RSSI на обігрівачі.")
        log("Обігрівач онлайн у хмарі!")
        CLOUD_API.attach(setups[0], gw)
        log(f"Прив'язано до будинку {setups[0]}")
        CLOUD_API.discover(gw)
        for _ in range(30):
            v = CLOUD_API.setups_view()
            devs = [d for s in (v or []) if isinstance(s, dict) for d in s.get("devices", []) or []]
            if devs:
                log("Пристрої в акаунті: " + ", ".join(f"{d.get('name')} (id {d.get('deviceId')})" for d in devs))
                break
            time.sleep(5)
        log("ГОТОВО ✔")
    except Fail as e:
        log("ПОМИЛКА: " + str(e))
    except Exception as e:
        log("ПОМИЛКА: " + repr(e))
        log(traceback.format_exc())
    finally:
        AUTO["running"] = False


# ─────────────────────────────── веб-сервер ───────────────────────────────
def api(path, p):
    if path == "/api/wifi/interfaces":
        ifs, raw = wifi_interfaces()
        return {"interfaces": ifs, "raw": raw}
    if path == "/api/wifi/scan":
        nets, raw = wifi_scan(p.get("interface") or None)
        return {"networks": nets, "raw": raw}
    if path == "/api/wifi/connect":
        out = wifi_connect(p.get("interface") or None, p["ssid"], p.get("key") or None)
        ok = wait_heater_reachable(20) if p["ssid"].startswith(AP_PREFIX) else None
        return {"out": out, "heaterReachable": ok}
    if path == "/api/heater/check":
        return {"reachable": wait_heater_reachable(4)}
    if path == "/api/heater/pin":
        return heater_pin()
    if path == "/api/heater/read":
        b = heater_read(int(p["start"]), int(p["qty"]))
        return show_bytes(b)
    if path == "/api/heater/dump":
        rows = []
        start, end, step = int(p.get("start", 0)), int(p.get("end", 260)), int(p.get("step", 8))
        for a in range(start, end, step):
            try:
                b = heater_read(a, step)
                sb = show_bytes(b)
                words = [int.from_bytes(b[i:i + 2], "big") for i in range(0, len(b) - 1, 2)]
                line = f"{a:4d}: {sb['hex']:<{step*6}}  {words}  «{sb['text']}»"
            except Exception as e:
                line = f"{a:4d}: помилка {e}"
            rows.append(line)
            log("REG " + line)
        return {"text": "\n".join(rows)}
    if path == "/api/heater/wifi":
        return {"result": heater_send_wifi(p["ssid"], p.get("password", ""))}
    if path == "/api/cloud/login":
        CLOUD_API.login(p["email"].strip(), p["password"])
        return {"ok": True}
    if path == "/api/cloud/setups":
        return {"setupIds": CLOUD_API.setup_ids(), "view": CLOUD_API.setups_view()}
    if path == "/api/cloud/find":
        return CLOUD_API.find_gateway(fmt_serial(p["pin"]))
    if path == "/api/cloud/create":
        return {"gatewayId": CLOUD_API.create_gateway(fmt_serial(p["pin"]))}
    if path == "/api/cloud/sync":
        return {"synchronized": CLOUD_API.is_synced(fmt_serial(p["pin"]))}
    if path == "/api/cloud/attach":
        sid = int(p["setupId"]) if p.get("setupId") else CLOUD_API.setup_ids()[0]
        CLOUD_API.attach(sid, int(p["gatewayId"]))
        CLOUD_API.discover(int(p["gatewayId"]))
        return {"ok": True, "setupId": sid}
    if path == "/api/cloud/raw":
        code, text, loc = CLOUD_API.authed(p["method"].upper(), p["path"], p.get("body") or None)
        return {"code": code, "location": loc, "body": text[:20000]}
    if path == "/api/auto":
        if AUTO["running"]:
            raise Fail("Сценарій уже виконується")
        threading.Thread(target=auto_flow, args=(p,), daemon=True).start()
        return {"started": True}
    raise Fail("Невідома дія " + path)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        data = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _local_only(self):
        host = (self.headers.get("Host") or "").split(":")[0]
        if host not in ("localhost", "127.0.0.1"):
            self._send(403, '{"error":"forbidden"}')
            return False
        return True

    def do_GET(self):
        if not self._local_only():
            return
        if self.path.startswith("/api/log"):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            since = int(q.get("since", ["0"])[0])
            with LOG_LOCK:
                lines = LOG[since:]
                total = len(LOG)
            return self._send(200, json.dumps({"lines": lines, "next": total, "auto": AUTO["running"],
                                               "loggedIn": bool(CLOUD_API.token)}, ensure_ascii=False))
        self._send(200, PAGE, "text/html; charset=utf-8")

    def do_POST(self):
        if not self._local_only():
            return
        # захист від запитів з інших сайтів
        origin = self.headers.get("Origin")
        if origin and urllib.parse.urlparse(origin).hostname not in ("localhost", "127.0.0.1"):
            return self._send(403, '{"error":"forbidden origin"}')
        n = int(self.headers.get("Content-Length") or 0)
        try:
            p = json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            p = {}
        try:
            res = api(self.path, p)
            self._send(200, json.dumps(res, ensure_ascii=False))
        except Fail as e:
            log("Помилка: " + str(e))
            self._send(400, json.dumps({"error": str(e)}, ensure_ascii=False))
        except Exception as e:
            log("Помилка: " + repr(e))
            self._send(500, json.dumps({"error": repr(e)}, ensure_ascii=False))


PAGE = r"""<!doctype html>
<html lang="uk"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Atlantic ALTIS — ПК</title>
<style>
:root{--bg:#f4f5f7;--card:#fff;--fg:#1d2330;--mut:#667085;--line:#d9dde5;--acc:#e0442b;--ok:#1a7f4b;--bad:#c62828}
@media (prefers-color-scheme:dark){:root{--bg:#14171c;--card:#1e232b;--fg:#e8ebf0;--mut:#98a2b3;--line:#323946;--acc:#ff6a4d;--ok:#4cc38a;--bad:#ff6b6b}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.45 system-ui,Segoe UI,sans-serif}
header{padding:18px 20px;border-bottom:1px solid var(--line);background:var(--card)}h1{margin:0;font-size:19px}
header p{margin:4px 0 0;color:var(--mut)}
main{max-width:1100px;margin:0 auto;padding:16px;display:grid;grid-template-columns:1fr 1fr;gap:14px}
@media (max-width:860px){main{grid-template-columns:1fr}}
section{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px 16px}
section.wide{grid-column:1/-1}h2{margin:0 0 10px;font-size:15px}
label{display:block;font-size:12px;color:var(--mut);margin:8px 0 3px}
input,select,textarea{width:100%;padding:7px 9px;border:1px solid var(--line);border-radius:6px;background:var(--bg);color:var(--fg);font:inherit}
.row{display:flex;gap:8px;flex-wrap:wrap;align-items:end}.row>*{flex:1;min-width:120px}
button{padding:7px 12px;border:1px solid var(--line);border-radius:6px;background:var(--bg);color:var(--fg);cursor:pointer;font:inherit;margin:8px 6px 0 0}
button.pri{background:var(--acc);border-color:var(--acc);color:#fff;font-weight:600}button:disabled{opacity:.5;cursor:default}
.hint{color:var(--mut);font-size:12px;margin:6px 0 0}.ok{color:var(--ok)}.bad{color:var(--bad)}
table{width:100%;border-collapse:collapse;margin-top:8px;font-size:13px}td,th{text-align:left;padding:4px 6px;border-bottom:1px solid var(--line)}
tr.ap td{font-weight:600;color:var(--acc)}
pre{background:var(--bg);border:1px solid var(--line);border-radius:6px;padding:8px;max-height:260px;overflow:auto;font:12px/1.4 Consolas,monospace;white-space:pre-wrap;word-break:break-all;margin:8px 0 0}
#log{max-height:420px;min-height:200px}
</style></head><body>
<header><h1>Atlantic ALTIS ECO BOOST — підключення з ПК</h1>
<p>Локальна сторінка: усі запити робить програма на цьому ПК. Пароль Cozytouch не зберігається.</p></header>
<main>

<section>
<h2>1. Wi‑Fi адаптер ПК</h2>
<div class="row"><div><label>Адаптер для мережі обігрівача</label><select id="iface"></select></div></div>
<button onclick="loadIfaces()">Оновити адаптери</button><button onclick="scan()">Сканувати мережі</button>
<div id="ifinfo" class="hint"></div>
<table id="nets"></table>
<p class="hint">Порада: основний адаптер лишайте на домашній мережі, а другим (USB) підключайтесь до ELEC_HEATER_Wifi_….
Якщо список порожній на Windows 11 — увімкніть «Розташування» в налаштуваннях конфіденційності.</p>
</section>

<section>
<h2>2. Модуль обігрівача (192.168.1.99)</h2>
<button onclick="call('/api/heater/check',{},r=>out('heat',r.reachable?'✔ Модуль доступний':'✘ Недоступний'))">Перевірити зв'язок</button>
<button onclick="call('/api/heater/pin',{},r=>{out('heat','PIN: '+(r.pin||'?')+'\nсирий: '+r.raw.text+'\nhex: '+r.raw.hex); if(r.pin&&!v('pin'))$('pin').value=r.pin})">Прочитати PIN</button>
<div class="row"><div><label>Регістр</label><input id="rs" value="180"></div><div><label>Кількість</label><input id="rq" value="6"></div>
<div style="flex:0"><button onclick="call('/api/heater/read',{start:v('rs'),qty:v('rq')},r=>out('heat',r.text+'\n'+r.hex))">Читати</button></div></div>
<button onclick="out('heat','Читаю регістри 0…260, ~1 хв…');call('/api/heater/dump',{start:0,end:260,step:8},r=>out('heat',r.text))">Прочитати всі регістри (діагностика)</button>
<pre id="heat">—</pre>
</section>

<section>
<h2>3. Хмара Cozytouch</h2>
<div class="row"><div><label>Email</label><input id="em" autocomplete="username"></div>
<div><label>Пароль</label><input id="pw" type="password" autocomplete="current-password"></div></div>
<button class="pri" onclick="call('/api/cloud/login',{email:v('em'),password:v('pw')},()=>{out('cl','✔ Вхід виконано');$('pw').value=''})">Увійти</button>
<button onclick="call('/api/cloud/setups',{},r=>out('cl',JSON.stringify(r,null,1)))">Мої будинки/пристрої</button>
<div class="row"><div><label>PIN-id (WIFI SN, 12 цифр)</label><input id="pin" placeholder="015108524955"></div></div>
<button onclick="call('/api/cloud/find',{pin:v('pin')},r=>out('cl',JSON.stringify(r)))">Знайти шлюз</button>
<button onclick="call('/api/cloud/sync',{pin:v('pin')},r=>out('cl','Синхронізований: '+r.synchronized))">Онлайн?</button>
<button onclick="if(confirm('Зареєструвати шлюз з цим PIN на ваш акаунт?'))call('/api/cloud/create',{pin:v('pin')},r=>{out('cl','id шлюзу: '+r.gatewayId);$('gw').value=r.gatewayId})">Зареєструвати шлюз</button>
<div class="row"><div><label>id шлюзу</label><input id="gw"></div><div><label>id будинку (порожньо = перший)</label><input id="sid"></div>
<div style="flex:0"><button onclick="call('/api/cloud/attach',{gatewayId:v('gw'),setupId:v('sid')},r=>out('cl','✔ Прив\'язано до '+r.setupId))">Прив'язати</button></div></div>
<pre id="cl">—</pre>
</section>

<section>
<h2>4. Все автоматично</h2>
<div class="row"><div><label>Домашня мережа 2.4 ГГц (SSID)</label><input id="hs" placeholder="3DN9C"></div>
<div><label>Пароль</label><input id="hp" type="password"></div></div>
<label><input type="checkbox" id="cap" checked style="width:auto"> Самому підключити вибраний адаптер до ELEC_HEATER_Wifi_…</label>
<button class="pri" id="autoBtn" onclick="auto()">Підключити обігрівач</button>
<p class="hint">Порядок: вхід у хмару (розділ 3) → на обігрівачі Меню → Зв'язок → Підключення → ця кнопка.
Програма прочитає PIN, передасть Wi‑Fi, зареєструє і прив'яже прилад, а також перевірить, чи зникла мережа обігрівача (тобто чи перейшов він на роутер).</p>
<div id="autoState" class="hint"></div>
</section>

<section class="wide">
<h2>Додатково: довільний запит до хмари</h2>
<div class="row"><div style="flex:0;min-width:90px"><label>Метод</label><select id="rm"><option>GET</option><option>HEAD</option><option>POST</option><option>PUT</option></select></div>
<div style="flex:4"><label>Шлях</label><input id="rp" value="/magellan/cozytouch/setupviewv2"></div></div>
<label>Тіло (JSON)</label><textarea id="rb" rows="2"></textarea>
<button onclick="call('/api/cloud/raw',{method:v('rm'),path:v('rp'),body:v('rb')},r=>out('raw','HTTP '+r.code+(r.location?'\nLocation: '+r.location:'')+'\n'+r.body))">Надіслати</button>
<pre id="raw">—</pre>
</section>

<section class="wide"><h2>Журнал</h2><button onclick="$('log').textContent='';">Очистити екран</button>
<button onclick="navigator.clipboard.writeText($('log').textContent)">Копіювати</button><pre id="log"></pre></section>
</main>
<script>
const $=id=>document.getElementById(id), v=id=>$(id).value.trim();
function out(id,t){$(id).textContent=t}
async function call(url,body,ok){
  try{const r=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
    const j=await r.json(); if(!r.ok) throw new Error(j.error||r.status); ok&&ok(j); return j;}
  catch(e){alert('Помилка: '+e.message)}
}
async function loadIfaces(){
  const r=await call('/api/wifi/interfaces',{}); if(!r)return;
  const s=$('iface'); const prev=s.value; s.innerHTML='';
  r.interfaces.forEach(i=>{const o=document.createElement('option');o.value=i.name;
    o.textContent=i.name+(i.description?' — '+i.description:'')+(i.ssid?'  ['+i.ssid+']':'  [не підключено]');s.appendChild(o)});
  if(prev)s.value=prev;
  // за замовчуванням — адаптер, що не підключений (другий)
  if(!prev){const free=r.interfaces.find(i=>!i.ssid); if(free)s.value=free.name}
  $('ifinfo').textContent=r.interfaces.map(i=>i.name+': '+(i.state||'')+(i.ssid?' → '+i.ssid+' канал '+(i.channel||'?')+' сигнал '+(i.signal||'?'):'')).join(' | ');
}
async function scan(){
  const r=await call('/api/wifi/scan',{interface:v('iface')}); if(!r)return;
  const t=$('nets'); t.innerHTML='<tr><th>Мережа</th><th>Сигнал</th><th>Канал</th><th>Захист</th><th></th></tr>';
  r.networks.sort((a,b)=>(b.ssid.startsWith('ELEC_HEATER')?1:0)-(a.ssid.startsWith('ELEC_HEATER')?1:0)).forEach(n=>{
    const tr=document.createElement('tr'); if(n.ssid.startsWith('ELEC_HEATER'))tr.className='ap';
    tr.innerHTML=`<td></td><td>${n.signal||''}</td><td>${n.channel||''}</td><td>${n.auth||''}</td><td></td>`;
    tr.cells[0].textContent=n.ssid||'(приховна)';
    if(n.ssid.startsWith('ELEC_HEATER')){const b=document.createElement('button');b.textContent='Підключити';
      b.onclick=()=>call('/api/wifi/connect',{interface:v('iface'),ssid:n.ssid},x=>{alert(x.heaterReachable?'✔ Модуль обігрівача доступний':'Підключено, але модуль ще не відповідає. Якщо мережа з замком — WIFIHVAC.');loadIfaces()});
      tr.cells[4].appendChild(b)}
    t.appendChild(tr)});
  if(!r.networks.length) t.innerHTML='<tr><td>Нічого не знайдено</td></tr>';
}
function auto(){call('/api/auto',{interface:v('iface'),ssid:$('hs').value,password:$('hp').value,pin:v('pin'),connectAp:$('cap').checked})}
let since=0;
async function poll(){
  try{const r=await (await fetch('/api/log?since='+since)).json(); since=r.next;
    if(r.lines.length){const l=$('log'); l.textContent+=r.lines.join('\n')+'\n'; l.scrollTop=l.scrollHeight}
    $('autoBtn').disabled=r.auto; $('autoState').textContent=r.auto?'⏳ Виконується… дивіться журнал':'';
  }catch(e){}
  setTimeout(poll,1200);
}
poll(); loadIfaces().catch(()=>{});
</script></body></html>"""


def main():
    srv = ThreadingHTTPServer(("127.0.0.1", PORT_UI), Handler)
    url = f"http://localhost:{PORT_UI}/"
    log(f"Atlantic PC tool: {url}  (закрийте це вікно, щоб зупинити)")
    if not os.environ.get("ATLANTIC_NO_BROWSER"):
        threading.Timer(0.8, lambda: webbrowser.open(url)).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
