package ua.madeisy.atlantic.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.madeisy.atlantic.core.Capability
import ua.madeisy.atlantic.core.ConsumptionDay
import ua.madeisy.atlantic.core.CozyApi
import ua.madeisy.atlantic.core.CozyException
import ua.madeisy.atlantic.core.Device
import ua.madeisy.atlantic.core.Heater
import ua.madeisy.atlantic.core.Setup
import ua.madeisy.atlantic.core.Slot
import ua.madeisy.atlantic.data.CredentialStore

data class UiState(
    val checkingSaved: Boolean = true,
    val loggedIn: Boolean = false,
    val loggingIn: Boolean = false,
    val savedEmail: String = "",
    val setups: List<Setup> = emptyList(),
    val loadingDevices: Boolean = false,
    val selectedDeviceId: Long? = null,
    val refreshing: Boolean = false,
    /** id значень, які зараз записуються */
    val writing: Set<Int> = emptySet(),
    val lastUpdate: Long = 0,
    val consumptions: List<ConsumptionDay>? = null,
    val message: String? = null,
    val error: String? = null,
) {
    val allDevices: List<Pair<Setup, Device>> get() = setups.flatMap { s -> s.devices.map { s to it } }
    val selected: Pair<Setup, Device>? get() = allDevices.firstOrNull { it.second.deviceId == selectedDeviceId }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = CredentialStore(app)
    private val api = CozyApi(log = { android.util.Log.d("Cozy", it) })
    private val writeLock = Mutex()
    private var pollJob: Job? = null

    private val _state = MutableStateFlow(UiState(savedEmail = store.email() ?: ""))
    val state: StateFlow<UiState> = _state

    init {
        val saved = store.load()
        if (saved != null) login(saved.first, saved.second, remember = false)
        else _state.update { it.copy(checkingSaved = false) }
    }

    // ───── Вхід ─────

    fun login(email: String, password: String, remember: Boolean = true) {
        if (email.isBlank() || password.isBlank()) {
            _state.update { it.copy(error = "Введіть email і пароль від Cozytouch") }
            return
        }
        _state.update { it.copy(loggingIn = true, error = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.login(email.trim(), password) }
                if (remember) store.save(email.trim(), password)
                _state.update { it.copy(loggedIn = true, loggingIn = false, checkingSaved = false, savedEmail = email.trim()) }
                loadDevices()
            } catch (e: Exception) {
                if (e is CozyException && (e.httpCode == 400 || e.httpCode == 401)) store.clearPassword()
                _state.update { it.copy(loggingIn = false, checkingSaved = false, error = e.message ?: e.toString()) }
            }
        }
    }

    fun logout() {
        pollJob?.cancel()
        api.logout()
        store.clearPassword()
        _state.update { UiState(checkingSaved = false, savedEmail = it.savedEmail) }
    }

    // ───── Пристрої ─────

    fun loadDevices() {
        _state.update { it.copy(loadingDevices = true, error = null) }
        viewModelScope.launch {
            try {
                val setups = withContext(Dispatchers.IO) { api.getSetups() }
                _state.update { st ->
                    // якщо пристрій один — одразу відкриваємо його
                    val heaters = setups.flatMap { it.devices }.filter { it.looksLikeHeater }
                    val autoSelect = st.selectedDeviceId ?: heaters.singleOrNull()?.deviceId
                    st.copy(setups = setups, loadingDevices = false, lastUpdate = System.currentTimeMillis(), selectedDeviceId = autoSelect)
                }
                if (_state.value.selectedDeviceId != null) startPolling()
            } catch (e: Exception) {
                _state.update { it.copy(loadingDevices = false, error = e.message) }
            }
        }
    }

    fun openDevice(id: Long) {
        _state.update { it.copy(selectedDeviceId = id, consumptions = null) }
        startPolling()
    }

    fun closeDevice() {
        pollJob?.cancel()
        _state.update { it.copy(selectedDeviceId = null) }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                refreshSelected(silent = true)
                delay(60_000) // частіше хмара Atlantic обмежує запити
            }
        }
    }

    fun refreshSelected(silent: Boolean = false) {
        val (setup, device) = _state.value.selected ?: return
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(refreshing = true) }
            try {
                var caps = withContext(Dispatchers.IO) { api.getCapabilities(device.deviceId) }
                if (caps.isEmpty()) {
                    // деякі прилади віддають значення лише у setupviewv2
                    val setups = withContext(Dispatchers.IO) { api.getSetups() }
                    _state.update { it.copy(setups = setups) }
                    caps = setups.flatMap { it.devices }.firstOrNull { it.deviceId == device.deviceId }?.capabilities ?: emptyList()
                }
                replaceCapabilities(device.deviceId, caps, merge = true)
                _state.update { it.copy(refreshing = false, lastUpdate = System.currentTimeMillis(), error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(refreshing = false, error = if (silent) it.error ?: e.message else e.message) }
            }
        }
    }

    fun loadConsumptions() {
        val (setup, _) = _state.value.selected ?: return
        viewModelScope.launch {
            val list = runCatching { withContext(Dispatchers.IO) { api.getConsumptionsDaily(setup.id) } }.getOrElse { emptyList() }
            _state.update { it.copy(consumptions = list) }
        }
    }

    // ───── Команди ─────

    private fun command(capIds: Set<Int>, optimistic: Map<Int, String>, ok: String?, block: (Heater, Setup) -> Unit) {
        val (setup, device) = _state.value.selected ?: return
        _state.update { it.copy(writing = it.writing + capIds, error = null) }
        viewModelScope.launch {
            try {
                writeLock.withLock {
                    withContext(Dispatchers.IO) { block(Heater(device), setup) }
                }
                if (optimistic.isNotEmpty()) {
                    replaceCapabilities(device.deviceId, optimistic.map { Capability(it.key, it.value) }, merge = true)
                }
                _state.update { it.copy(writing = it.writing - capIds, message = ok) }
                delay(4000)
                refreshSelected(silent = true)
            } catch (e: Exception) {
                _state.update { it.copy(writing = it.writing - capIds, error = e.message ?: e.toString()) }
            }
        }
    }

    fun setTarget(t: Double) {
        val h = currentHeater() ?: return
        val v = h.clampTarget(t)
        val opt = mutableMapOf(40 to v.toString())
        if (h.programOn && h.hasOverride) opt[157] = "1"
        command(setOf(40), opt, null) { heater, _ -> heater.setTarget(api, v) }
    }

    fun setPower(on: Boolean) = command(setOf(7), mapOf(7 to if (on) "4" else "0"), if (on) "Обігрівач увімкнено" else "Обігрівач вимкнено") { h, _ -> h.setPower(api, on) }

    fun setProgram(on: Boolean) = command(setOf(184), mapOf(184 to if (on) "1" else "0"), null) { h, _ -> h.setProgram(api, on) }

    fun cancelOverride() = command(setOf(157), mapOf(157 to "0"), "Повернуто до програми") { h, _ -> h.cancelOverride(api) }

    fun setBoost(on: Boolean) {
        val id = currentHeater()?.boostId ?: return
        command(setOf(id), mapOf(id to if (on) "1" else "0"), if (on) "Boost увімкнено" else "Boost вимкнено") { h, _ -> h.setBoost(api, on) }
    }

    fun setSwitch(capId: Int, on: Boolean) =
        command(setOf(capId), mapOf(capId to if (on) "1" else "0"), null) { h, _ -> h.setSwitch(api, capId, on) }

    fun writeRaw(capId: Int, value: String) =
        command(setOf(capId), mapOf(capId to value), "Записано") { h, _ -> api.writeCapability(h.device.deviceId, capId, value) }

    fun identify() = command(setOf(100078), emptyMap(), "Прилад має блимнути індикатором") { h, _ ->
        api.writeCapability(h.device.deviceId, 100078, "1")
    }

    fun writeDays(days: List<Int>, slots: List<Slot>) {
        val h = currentHeater() ?: return
        val start = h.programDayStart ?: return
        val encoded = runCatching { ua.madeisy.atlantic.core.Program.encode(slots, h.maxSlotsPerDay) }
            .getOrElse { e -> _state.update { it.copy(error = e.message) }; return }
        val ids = days.map { start + it }.toSet()
        command(ids, ids.associateWith { encoded }, "Розклад збережено") { heater, _ ->
            for (d in days) heater.writeDay(api, d, slots)
        }
    }

    fun setAway(days: Int?) {
        val now = System.currentTimeMillis() / 1000
        val start = days?.let { now + 60 }
        val end = days?.let { now + 60 + it * 86400L }
        val h = currentHeater() ?: return
        val ids = setOfNotNull(h.awaySwitchId, h.awayTimestampsId)
        val opt = buildMap<Int, String> {
            h.awaySwitchId?.let { put(it, if (days == null) "0" else "2") }
            h.awayTimestampsId?.let { put(it, if (days == null) "[0,0]" else "[$start,$end]") }
        }
        command(ids.ifEmpty { setOf(-1) }, opt, if (days == null) "Відсутність вимкнено" else "Відсутність на $days дн.") { heater, setup ->
            heater.setAway(api, setup, start, end, now)
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
    fun consumeError() = _state.update { it.copy(error = null) }

    private fun currentHeater(): Heater? = _state.value.selected?.second?.let { Heater(it) }

    private fun replaceCapabilities(deviceId: Long, caps: List<Capability>, merge: Boolean) {
        _state.update { st ->
            st.copy(setups = st.setups.map { s ->
                s.copy(devices = s.devices.map { d ->
                    if (d.deviceId != deviceId) d else {
                        val map = if (merge) d.capabilities.associateBy { it.id }.toMutableMap() else mutableMapOf()
                        caps.forEach { map[it.id] = it }
                        d.copy(capabilities = map.values.sortedBy { it.id })
                    }
                })
            })
        }
    }
}
