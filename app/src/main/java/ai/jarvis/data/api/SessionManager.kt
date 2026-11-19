package ai.jarvis.data.api

import ai.jarvis.core.ApiResult
import ai.jarvis.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Connection state as the rest of the app sees it. Never lies about being connected. */
data class ConnectionState(
    val phase: Phase = Phase.UNKNOWN,
    val sessionStatus: String = "",
    val phone: String? = null,
    val pushName: String? = null,
    val engine: String? = null,
    val role: String? = null,
    val lastError: String? = null,
    val checkedAtMs: Long = 0,
) {
    enum class Phase { UNKNOWN, DEMO, UNREACHABLE, NO_KEY, NO_SESSION, CONNECTED, DEGRADED }

    val isLiveReady: Boolean get() = phase == Phase.CONNECTED && sessionStatus == "ready"

    val badge: String
        get() = when (phase) {
            Phase.DEMO -> "DEMO"
            Phase.UNREACHABLE -> "UNREACHABLE"
            Phase.NO_KEY -> "NO KEY"
            Phase.NO_SESSION -> "NO SESSION"
            Phase.DEGRADED -> "DEGRADED"
            Phase.CONNECTED -> when (sessionStatus) {
                "ready" -> "READY"
                "qr_ready", "authenticating" -> "QR"
                "initializing" -> "STARTING"
                "disconnected" -> "DISCONNECTED"
                "action_required" -> "ACTION REQ"
                "failed" -> "FAILED"
                else -> sessionStatus.uppercase().ifBlank { "CONNECTED" }
            }
            Phase.UNKNOWN -> "…"
        }
}

/**
 * Owns the OpenWA connection: health check, key validation, session polling, and the
 * "start session + QR" flow. Everything is exposed as flows so screens stay truthful.
 */
class SessionManager(
    private val api: OpenWaApi,
    private val settings: SettingsStore,
) {
    private val _connection = MutableStateFlow(ConnectionState())
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionDto>>(emptyList())
    val sessions: StateFlow<List<SessionDto>> = _sessions.asStateFlow()

    private val _qr = MutableStateFlow<String?>(null)
    val qrDataUrl: StateFlow<String?> = _qr.asStateFlow()

    fun markDemo() {
        _connection.value = ConnectionState(phase = ConnectionState.Phase.DEMO, checkedAtMs = System.currentTimeMillis())
    }

    /** Full verification: readiness (no key) -> key validate -> session fetch. */
    suspend fun testConnection(): ConnectionState {
        val s = settings.settings.value
        if (s.mode == ai.jarvis.core.AppMode.DEMO) {
            markDemo()
            return _connection.value
        }
        if (s.apiKey.isBlank()) {
            _connection.value = ConnectionState(
                phase = ConnectionState.Phase.NO_KEY,
                lastError = "API key khali hai — Settings me OpenWA operator key daalo.",
                checkedAtMs = System.currentTimeMillis(),
            )
            return _connection.value
        }

        val health = api.healthReady()
        if (health is ApiResult.Failure) {
            _connection.value = ConnectionState(
                phase = ConnectionState.Phase.UNREACHABLE,
                lastError = health.error.message,
                checkedAtMs = System.currentTimeMillis(),
            )
            return _connection.value
        }

        val validate = api.validateKey(s.apiKey)
        val role = (validate as? ApiResult.Success)?.data?.role
        val engine = (validate as? ApiResult.Success)?.data?.engineType

        if (s.sessionId.isBlank()) {
            _connection.value = ConnectionState(
                phase = ConnectionState.Phase.NO_SESSION,
                role = role, engine = engine,
                lastError = "Session id set nahi hai — Sessions screen se session banao/select karo.",
                checkedAtMs = System.currentTimeMillis(),
            )
            return _connection.value
        }

        return when (val res = api.getSession(s.sessionId)) {
            is ApiResult.Success -> {
                val dto = res.data
                val phase = if (dto.status == "ready") ConnectionState.Phase.CONNECTED
                else ConnectionState.Phase.DEGRADED
                _connection.value = ConnectionState(
                    phase = phase,
                    sessionStatus = dto.status,
                    phone = dto.phone,
                    pushName = dto.pushName,
                    engine = engine,
                    role = role,
                    lastError = dto.lastError,
                    checkedAtMs = System.currentTimeMillis(),
                )
                _connection.value
            }
            is ApiResult.Failure -> {
                _connection.value = ConnectionState(
                    phase = if (res.error.isAuthProblem) ConnectionState.Phase.NO_KEY else ConnectionState.Phase.UNREACHABLE,
                    role = role, engine = engine,
                    lastError = res.error.message,
                    checkedAtMs = System.currentTimeMillis(),
                )
                _connection.value
            }
        }
    }

    suspend fun refreshSessions(): ApiResult<List<SessionDto>> {
        val res = api.listSessions()
        if (res is ApiResult.Success) _sessions.value = res.data
        return res
    }

    suspend fun refreshSession(): ApiResult<SessionDto> {
        val id = settings.settings.value.sessionId
        if (id.isBlank()) return ApiResult.Failure(ai.jarvis.core.ApiError(0, "Session id missing", transport = true))
        return when (val res = api.getSession(id)) {
            is ApiResult.Success -> {
                val d = res.data
                _connection.value = _connection.value.copy(
                    phase = if (d.status == "ready") ConnectionState.Phase.CONNECTED else ConnectionState.Phase.DEGRADED,
                    sessionStatus = d.status, phone = d.phone, pushName = d.pushName,
                    lastError = d.lastError, checkedAtMs = System.currentTimeMillis(),
                )
                res
            }
            is ApiResult.Failure -> res
        }
    }

    /** Starts the session then waits (bounded) for it to reach a QR / ready phase. */
    suspend fun startSession(onPhase: (String) -> Unit = {}): ApiResult<SessionDto> {
        val id = settings.settings.value.sessionId
        val start = api.startSession(id)
        if (start is ApiResult.Failure) return start
        onPhase("initializing")
        var last: SessionDto = (start as ApiResult.Success).data
        repeat(40) { // up to ~80 s
            kotlinx.coroutines.delay(2_000)
            when (val s = api.getSession(id)) {
                is ApiResult.Success -> {
                    last = s.data
                    onPhase(last.status)
                    _connection.value = _connection.value.copy(
                        phase = if (last.status == "ready") ConnectionState.Phase.CONNECTED else ConnectionState.Phase.DEGRADED,
                        sessionStatus = last.status, phone = last.phone, pushName = last.pushName,
                        lastError = last.lastError, checkedAtMs = System.currentTimeMillis(),
                    )
                    if (last.status in setOf("ready", "qr_ready", "authenticating", "action_required", "failed", "disconnected")) {
                        return ApiResult.Success(last)
                    }
                }
                is ApiResult.Failure -> return s
            }
        }
        return ApiResult.Success(last)
    }

    suspend fun fetchQr(): ApiResult<String> {
        val id = settings.settings.value.sessionId
        return when (val res = api.getQr(id)) {
            is ApiResult.Success -> {
                _qr.value = res.data.qrCode
                ApiResult.Success(res.data.qrCode)
            }
            is ApiResult.Failure -> res
        }
    }

    suspend fun requestPairingCode(phone: String): ApiResult<String> {
        val id = settings.settings.value.sessionId
        return when (val res = api.requestPairingCode(id, phone)) {
            is ApiResult.Success -> {
                val code = res.data.pairingCode ?: res.data.code ?: ""
                ApiResult.Success(code)
            }
            is ApiResult.Failure -> res
        }
    }

    /** Live in-app test of the API key + session without leaving Settings. */
    suspend fun verifyKeyOnly(apiKey: String, baseUrl: String): String {
        val health = api.healthReady()
        if (health is ApiResult.Failure) return "❌ Server reachable nahi: ${health.error.oneLine}"
        val v = api.validateKey(apiKey)
        return when (v) {
            is ApiResult.Success -> "✅ Key valid · role=${v.data.role} · engine=${v.data.engineType} · scoped=${v.data.scoped}"
            is ApiResult.Failure -> "❌ Key invalid: ${v.error.oneLine}"
        }
    }

    fun currentSessionId(): String = settings.settings.value.sessionId
}
