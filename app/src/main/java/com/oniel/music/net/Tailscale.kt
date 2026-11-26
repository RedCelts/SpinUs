package com.oniel.music.net

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * Embedded Tailscale: the app joins the tailnet as its own device
 * ("oniel-music"), so the server is reachable over Tailscale without the
 * Tailscale app or a VPN. The Go side (tsbridge.aar) runs tsnet and a
 * local SOCKS5 proxy; [NetClients] sends requests for tailnet hosts
 * through it.
 *
 * On/off is remembered; when on, it starts with the app (MainActivity)
 * and with the playback service, whichever comes first.
 */
object Tailscale {

    enum class Phase { OFF, STARTING, NEEDS_LOGIN, RUNNING, ERROR }

    data class State(
        val phase: Phase = Phase.OFF,
        val ips: List<String> = emptyList(),
        val deviceName: String? = null,
        val tailnet: String? = null,
        val error: String? = null
    )

    private const val TAG = "OnielTailscale"
    private const val PREFS = "tailscale"
    private const val KEY_ENABLED = "enabled"
    /** Exists while Tailscale is starting; left behind if starting killed the app. */
    private const val STARTING_MARKER = "tailscale_starting"
    /** How long after a successful start the marker is kept (late crashes count too). */
    private const val MARKER_HOLD_MS = 20_000L
    const val HOSTNAME = "oniel-music"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Local SOCKS5 port while running, else 0. Read by [NetClients]. */
    @Volatile var socksPort: Int = 0
        private set

    val isRunning: Boolean get() = _state.value.phase == Phase.RUNNING && socksPort > 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    @Volatile private var started = false
    private var appContext: Context? = null

    private val bridgeContext = object : tsbridge.AppContext {
        override fun getInterfaces(): String = interfacesAsString()
        override fun log(line: String) {
            Log.i(TAG, line)
        }
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    /** Start if the user turned Tailscale on. Safe to call repeatedly. */
    fun startIfEnabled(context: Context) {
        appContext = context.applicationContext
        val marker = File(context.applicationContext.filesDir, STARTING_MARKER)
        if (!started && marker.exists()) {
            // The last start never finished: it took the app down. Turn
            // Tailscale off so the app opens normally; the crash report
            // (if any) is shown on launch.
            marker.delete()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, false).apply()
            _state.value = State(
                phase = Phase.OFF,
                error = "Tailscale closed the app while starting, so it was turned off."
            )
            return
        }
        if (isEnabled(context)) start(context)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) start(context) else stop()
    }

    private fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val dir = File(app.filesDir, "tailscale").absolutePath
        val marker = File(app.filesDir, STARTING_MARKER)
        _state.value = State(phase = Phase.STARTING)
        scope.launch {
            try {
                marker.writeText(System.currentTimeMillis().toString())
                prepareEnvironment(dir)
                try {
                    tsbridge.Tsbridge.setCrashFile(com.oniel.music.CrashLog.goCrashFile(app).absolutePath)
                } catch (e: Throwable) {
                    Log.w(TAG, "crash file not set", e)
                }
                tsbridge.Tsbridge.start(dir, HOSTNAME, bridgeContext)
                socksPort = tsbridge.Tsbridge.socksPort().toInt()
            } catch (e: Throwable) {
                Log.w(TAG, "start failed", e)
                marker.delete()
                started = false
                socksPort = 0
                _state.value = State(phase = Phase.ERROR, error = e.message ?: e.javaClass.simpleName)
                return@launch
            }
            startPolling()
            delay(MARKER_HOLD_MS)
            marker.delete()
        }
    }

    private fun stop() {
        pollJob?.cancel()
        pollJob = null
        socksPort = 0
        started = false
        _state.value = State(phase = Phase.OFF)
        scope.launch {
            try { tsbridge.Tsbridge.stop() } catch (e: Throwable) { Log.w(TAG, "stop failed", e) }
        }
    }

    /**
     * Android apps have no $HOME or XDG folders, and Tailscale's Go code
     * panics without a place for its log state. Set them (and opt out of
     * diagnostic log upload) BEFORE the Go library loads: Go reads the
     * environment once, when it starts.
     */
    private fun prepareEnvironment(dir: String) {
        val logs = File(dir, "logs").apply { mkdirs() }
        val cache = File(dir, "cache").apply { mkdirs() }
        val vars = mapOf(
            "HOME" to dir,
            "XDG_CACHE_HOME" to cache.absolutePath,
            "XDG_CONFIG_HOME" to dir,
            "TMPDIR" to cache.absolutePath,
            "TS_LOGS_DIR" to logs.absolutePath,
            "TS_NO_LOGS_NO_SUPPORT" to "true"
        )
        for ((key, value) in vars) {
            try {
                android.system.Os.setenv(key, value, true)
            } catch (e: Exception) {
                Log.w(TAG, "setenv $key failed", e)
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                refresh()
                // Quick while signing in / connecting, relaxed once up.
                delay(if (_state.value.phase == Phase.RUNNING) 10_000L else 1_000L)
            }
        }
    }

    private fun refresh() {
        val json = try {
            JSONObject(tsbridge.Tsbridge.status())
        } catch (e: Throwable) {
            _state.value = _state.value.copy(phase = Phase.ERROR, error = e.message)
            return
        }
        val backend = json.optString("state")
        val ipsArray = json.optJSONArray("ips")
        val ips = (0 until (ipsArray?.length() ?: 0)).map { ipsArray!!.getString(it) }
        val phase = when (backend) {
            "Running" -> Phase.RUNNING
            "NeedsLogin", "NeedsMachineAuth" -> Phase.NEEDS_LOGIN
            "Stopped" -> if (started) Phase.STARTING else Phase.OFF
            else -> Phase.STARTING
        }
        _state.value = State(
            phase = phase,
            ips = ips,
            deviceName = json.optString("dnsName").ifBlank { null },
            tailnet = json.optString("tailnet").ifBlank { null },
            error = json.optString("error").ifBlank { null }
        )
    }

    /** Sign-in link to open in the browser, or null if already signed in. */
    suspend fun loginUrl(): String? = withContext(Dispatchers.IO) {
        val url = tsbridge.Tsbridge.login()
        startPolling()
        url.ifBlank { null }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        tsbridge.Tsbridge.logout()
        refresh()
    }

    /**
     * The phone's interfaces for Tailscale (Go can't list them itself on
     * Android 11+): name|index|mtu|flags|cidr,cidr
     */
    private fun interfacesAsString(): String = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().joinToString("\n") { ni ->
            val flags = buildList {
                if (ni.isUp) add("up")
                if (ni.isLoopback) add("loopback")
                if (ni.supportsMulticast()) add("multicast")
                if (ni.isPointToPoint) add("p2p")
                if (!ni.isLoopback && !ni.isPointToPoint) add("broadcast")
            }.joinToString(",")
            val addrs = ni.interfaceAddresses.mapNotNull { ia ->
                val host = ia.address?.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                val prefix = ia.networkPrefixLength.toInt()
                if (ia.address is Inet6Address && prefix > 128) null else "$host/$prefix"
            }.joinToString(",")
            "${ni.name}|${ni.index}|${ni.mtu}|$flags|$addrs"
        }
    } catch (e: Exception) {
        ""
    }
}
