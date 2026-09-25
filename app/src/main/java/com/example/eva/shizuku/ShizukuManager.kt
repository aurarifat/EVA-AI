package com.example.eva.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ShizukuConnectionStatus {
    NOT_INSTALLED,
    SERVER_NOT_RUNNING,
    PERMISSION_REQUIRED,
    AUTHORIZED_CONNECTED,
    UNKNOWN
}

enum class WirelessSessionStatus {
    DISCONNECTED,
    CONNECTING,
    ACTIVE_CONNECTED,
    ERROR
}

data class ShizukuInfo(
    val status: ShizukuConnectionStatus = ShizukuConnectionStatus.UNKNOWN,
    val isInstalled: Boolean = false,
    val isBinderAlive: Boolean = false,
    val isAuthorized: Boolean = false,
    val serverVersion: Int = 0,
    val serverUid: Int = -1, // 0 = root, 2000 = shell (wireless debugging / adb)
    val isPreV11: Boolean = false,
    val lastPingMessage: String = "",
    val connectionTimestamp: Long = 0L,
    val isWirelessDebuggingAdbMode: Boolean = false
)

data class WirelessDebuggingSessionState(
    val sessionStatus: WirelessSessionStatus = WirelessSessionStatus.DISCONNECTED,
    val activePort: Int? = null,
    val latencyMs: Long = -1L,
    val isNativeAdbWifiEnabled: Boolean = false,
    val localIpAddress: String = "127.0.0.1",
    val sessionStartTime: Long = 0L,
    val commandsExecutedCount: Int = 0,
    val statusMessage: String = "Wireless debugging session inactive",
    val lastError: String? = null
)

data class ShizukuLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: String = "INFO", // "INFO", "SUCCESS", "WARN", "ERROR"
    val message: String
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

class ShizukuManager(private val context: Context) {

    private val _shizukuState = MutableStateFlow(ShizukuInfo())
    val shizukuState: StateFlow<ShizukuInfo> = _shizukuState.asStateFlow()

    private val _wirelessSession = MutableStateFlow(WirelessDebuggingSessionState())
    val wirelessSession: StateFlow<WirelessDebuggingSessionState> = _wirelessSession.asStateFlow()

    private val _logs = MutableStateFlow<List<ShizukuLogEntry>>(emptyList())
    val logs: StateFlow<List<ShizukuLogEntry>> = _logs.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        addLog("SUCCESS", "Shizuku IPC binder token received from server.")
        checkStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        addLog("WARN", "Shizuku binder died. Server process terminated.")
        checkStatus()
        _wirelessSession.value = _wirelessSession.value.copy(
            sessionStatus = WirelessSessionStatus.DISCONNECTED,
            statusMessage = "Bridge disconnected: Shizuku binder died",
            lastError = "Shizuku binder is no longer alive."
        )
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        addLog(
            if (granted) "SUCCESS" else "WARN",
            "Shizuku permission request ($requestCode) result: ${if (granted) "GRANTED" else "DENIED"}"
        )
        checkStatus()
    }

    init {
        addLog("INFO", "Initializing Shizuku IPC manager and listeners.")
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (e: Exception) {
            addLog("ERROR", "Failed to register Shizuku listeners: ${e.localizedMessage}")
        }
        checkStatus()
    }

    private fun addLog(level: String, message: String) {
        val entry = ShizukuLogEntry(level = level, message = message)
        val current = _logs.value.toMutableList()
        if (current.size > 150) current.removeAt(0)
        current.add(entry)
        _logs.value = current
    }

    fun isShizukuInstalled(): Boolean {
        return try {
            val pm = context.packageManager
            pm.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun checkStatus() {
        val pingAlive = try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }

        val installed = pingAlive || isShizukuInstalled()
        if (!installed) {
            _shizukuState.value = ShizukuInfo(
                status = ShizukuConnectionStatus.NOT_INSTALLED,
                isInstalled = false,
                lastPingMessage = "Shizuku app is not installed on this device."
            )
            return
        }

        if (!pingAlive) {
            _shizukuState.value = ShizukuInfo(
                status = ShizukuConnectionStatus.SERVER_NOT_RUNNING,
                isInstalled = true,
                isBinderAlive = false,
                lastPingMessage = "Shizuku server is not running. Start via Wireless Debugging or root."
            )
            return
        }

        val isAuthorized = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }

        val version = runCatching { Shizuku.getVersion() }.getOrDefault(0)
        val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        val isAdbMode = (uid == 2000)

        val status = if (isAuthorized) {
            ShizukuConnectionStatus.AUTHORIZED_CONNECTED
        } else {
            ShizukuConnectionStatus.PERMISSION_REQUIRED
        }

        val pingMsg = when (status) {
            ShizukuConnectionStatus.AUTHORIZED_CONNECTED -> {
                val mode = if (uid == 0) "Root (UID 0)" else "Wireless Debugging Shell (UID 2000)"
                "Shizuku Connected: v$version running in $mode mode."
            }
            ShizukuConnectionStatus.PERMISSION_REQUIRED -> "Shizuku is running. Authorization required."
            else -> "Shizuku status: $status"
        }

        val previous = _shizukuState.value
        val now = System.currentTimeMillis()
        val connTime = if (status == ShizukuConnectionStatus.AUTHORIZED_CONNECTED && previous.connectionTimestamp == 0L) now else previous.connectionTimestamp

        _shizukuState.value = ShizukuInfo(
            status = status,
            isInstalled = true,
            isBinderAlive = true,
            isAuthorized = isAuthorized,
            serverVersion = version,
            serverUid = uid,
            isPreV11 = runCatching { Shizuku.isPreV11() }.getOrDefault(false),
            lastPingMessage = pingMsg,
            connectionTimestamp = connTime,
            isWirelessDebuggingAdbMode = isAdbMode
        )

        // Automatically update native wireless debugging state if authorized
        if (isAuthorized) {
            checkNativeWirelessDebuggingState()
        }
    }

    fun requestAuthorization(requestCode: Int = 1001): Boolean {
        if (!_shizukuState.value.isBinderAlive) {
            addLog("WARN", "Cannot request permission: Shizuku binder is not alive.")
            return false
        }
        return try {
            addLog("INFO", "Prompting user for Shizuku permission grant...")
            Shizuku.requestPermission(requestCode)
            true
        } catch (e: Exception) {
            addLog("ERROR", "Error requesting Shizuku permission: ${e.localizedMessage}")
            false
        }
    }

    /**
     * Checks if native Android 11+ Wireless Debugging is enabled in Settings.Global.
     */
    fun checkNativeWirelessDebuggingState(): Boolean {
        return try {
            val enabled = Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
            _wirelessSession.value = _wirelessSession.value.copy(isNativeAdbWifiEnabled = enabled)
            enabled
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Attempts to toggle native Wireless Debugging on Android 11+ using Shizuku's shell privilege.
     */
    suspend fun toggleNativeWirelessDebugging(enable: Boolean): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!_shizukuState.value.isAuthorized) {
            return@withContext Pair(false, "Shizuku authorization required to configure wireless debugging.")
        }
        addLog("INFO", "Toggling native Wireless Debugging to ${if (enable) "ENABLED" else "DISABLED"} via Shizuku shell.")
        try {
            val valStr = if (enable) "1" else "0"
            val result = executeRawCommand(arrayOf("settings", "put", "global", "adb_wifi_enabled", valStr))
            if (result.first == 0) {
                checkNativeWirelessDebuggingState()
                addLog("SUCCESS", "Wireless debugging setting updated to $valStr.")
                Pair(true, "Wireless debugging successfully ${if (enable) "enabled" else "disabled"}.")
            } else {
                addLog("ERROR", "Failed to update adb_wifi_enabled: ${result.second}")
                Pair(false, result.second)
            }
        } catch (e: Exception) {
            addLog("ERROR", "Exception setting adb_wifi_enabled: ${e.localizedMessage}")
            Pair(false, "Error: ${e.localizedMessage}")
        }
    }

    /**
     * Auto-detects active Wireless Debugging ADB port via system property, /proc/net/tcp, or local socket check.
     */
    suspend fun autoDetectAdbPort(): Int? = withContext(Dispatchers.IO) {
        val sysPropPort = detectAdbPort()
        if (sysPropPort != null) {
            addLog("INFO", "Detected ADB TCP port $sysPropPort from system properties.")
            return@withContext sysPropPort
        }

        if (_shizukuState.value.isAuthorized) {
            try {
                val (code, out) = executeRawCommand(arrayOf("cat", "/proc/net/tcp"))
                if (code == 0 && out.isNotBlank()) {
                    for (line in out.lines().drop(1)) {
                        val parts = line.trim().split(Regex("""\s+"""))
                        if (parts.size >= 4 && parts[3].equals("0A", ignoreCase = true)) {
                            val portHex = parts[1].substringAfter(":", "")
                            val port = portHex.toIntOrNull(16)
                            if (port != null && (port == 5555 || port in 30000..50000)) {
                                addLog("INFO", "Discovered listening wireless port $port from /proc/net/tcp.")
                                return@withContext port
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Test standard 5555 port as fallback
        val (sock5555, _) = testWirelessDebuggingPort(5555)
        if (sock5555) {
            addLog("INFO", "Standard wireless debugging port 5555 is listening.")
            return@withContext 5555
        }

        null
    }

    /**
     * Pings the active session bridge and measures round-trip latency.
     */
    suspend fun pingActiveSession(): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
        if (_wirelessSession.value.sessionStatus != WirelessSessionStatus.ACTIVE_CONNECTED) {
            return@withContext Pair(false, -1L)
        }
        val startTime = System.currentTimeMillis()
        val result = executeRawCommand(arrayOf("id"))
        val latency = System.currentTimeMillis() - startTime
        if (result.first == 0) {
            _wirelessSession.value = _wirelessSession.value.copy(
                latencyMs = latency,
                commandsExecutedCount = _wirelessSession.value.commandsExecutedCount + 1,
                statusMessage = "Bridge responsive (Latency: ${latency}ms)"
            )
            addLog("SUCCESS", "Bridge ping round-trip successful: ${latency}ms.")
            Pair(true, latency)
        } else {
            addLog("WARN", "Bridge ping error: ${result.second}")
            Pair(false, -1L)
        }
    }

    /**
     * Sets ADB TCP port (e.g. 5555) via privileged shell.
     */
    suspend fun configureAdbTcpPort(port: Int = 5555): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!_shizukuState.value.isAuthorized) {
            return@withContext Pair(false, "Shizuku authorization required.")
        }
        addLog("INFO", "Configuring ADB TCP port to $port via Shizuku shell...")
        val res = executeRawCommand(arrayOf("setprop", "service.adb.tcp.port", port.toString()))
        if (res.first == 0) {
            addLog("SUCCESS", "ADB TCP port set to $port.")
            Pair(true, "ADB TCP port set to $port.")
        } else {
            addLog("ERROR", "Failed to set ADB TCP port: ${res.second}")
            Pair(false, res.second)
        }
    }

    /**
     * Establishes a verified Wireless Debugging Session through the Shizuku bridge.
     */
    suspend fun establishWirelessDebuggingSession(customPort: Int? = null): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        checkStatus()
        val info = _shizukuState.value

        if (!info.isInstalled) {
            val msg = "Shizuku is not installed on this device."
            addLog("ERROR", msg)
            _wirelessSession.value = _wirelessSession.value.copy(
                sessionStatus = WirelessSessionStatus.ERROR,
                statusMessage = msg,
                lastError = msg
            )
            return@withContext Pair(false, msg)
        }

        if (!info.isBinderAlive) {
            val msg = "Shizuku server is not running. Please start Shizuku first."
            addLog("ERROR", msg)
            _wirelessSession.value = _wirelessSession.value.copy(
                sessionStatus = WirelessSessionStatus.ERROR,
                statusMessage = msg,
                lastError = msg
            )
            return@withContext Pair(false, msg)
        }

        if (!info.isAuthorized) {
            val msg = "Shizuku permission not granted. Please authorize EVA."
            addLog("WARN", msg)
            _wirelessSession.value = _wirelessSession.value.copy(
                sessionStatus = WirelessSessionStatus.ERROR,
                statusMessage = msg,
                lastError = msg
            )
            return@withContext Pair(false, msg)
        }

        _wirelessSession.value = _wirelessSession.value.copy(
            sessionStatus = WirelessSessionStatus.CONNECTING,
            statusMessage = "Establishing wireless debugging bridge session..."
        )
        addLog("INFO", "Initiating wireless debugging session verification via Shizuku...")

        val startTime = System.currentTimeMillis()

        // 1. Verify shell execution through Shizuku
        val testResult = executeRawCommand(arrayOf("id"))
        val latency = System.currentTimeMillis() - startTime

        if (testResult.first != 0) {
            val err = "Failed to communicate with ADB shell: ${testResult.second}"
            addLog("ERROR", err)
            _wirelessSession.value = _wirelessSession.value.copy(
                sessionStatus = WirelessSessionStatus.ERROR,
                statusMessage = err,
                lastError = err
            )
            return@withContext Pair(false, err)
        }

        // 2. Discover or verify wireless debugging port
        val portToUse = customPort ?: autoDetectAdbPort() ?: detectAdbPort() ?: 5555
        var socketConnected = false

        if (portToUse > 0) {
            val (sockOk, _) = testWirelessDebuggingPort(portToUse)
            socketConnected = sockOk
        }

        // 3. Resolve device IP address on Wi-Fi
        val ip = getDeviceWifiIp()

        _wirelessSession.value = WirelessDebuggingSessionState(
            sessionStatus = WirelessSessionStatus.ACTIVE_CONNECTED,
            activePort = if (socketConnected) portToUse else null,
            latencyMs = latency,
            isNativeAdbWifiEnabled = checkNativeWirelessDebuggingState(),
            localIpAddress = ip,
            sessionStartTime = System.currentTimeMillis(),
            commandsExecutedCount = _wirelessSession.value.commandsExecutedCount + 1,
            statusMessage = "Wireless Debugging Bridge Active (UID ${info.serverUid} | Latency: ${latency}ms)",
            lastError = null
        )

        val successMsg = "Wireless debugging session established successfully! Latency: ${latency}ms."
        addLog("SUCCESS", successMsg)
        Pair(true, successMsg)
    }

    /**
     * Disconnects the active wireless debugging bridge session.
     */
    fun disconnectWirelessDebuggingSession() {
        addLog("INFO", "Wireless debugging bridge session disconnected by user.")
        _wirelessSession.value = _wirelessSession.value.copy(
            sessionStatus = WirelessSessionStatus.DISCONNECTED,
            statusMessage = "Wireless debugging session disconnected",
            latencyMs = -1L,
            lastError = null
        )
    }

    /**
     * Detects active ADB TCP port from system properties.
     */
    suspend fun detectAdbPort(): Int? = withContext(Dispatchers.IO) {
        try {
            val (code, out) = executeRawCommand(arrayOf("getprop", "service.adb.tcp.port"))
            if (code == 0 && out.isNotBlank()) {
                val p = out.trim().toIntOrNull()
                if (p != null && p > 0) return@withContext p
            }
        } catch (_: Exception) {}
        null
    }

    /**
     * Executes a raw command through Shizuku's privileged process interface with exit code and output.
     */
    suspend fun executeRawCommand(command: Array<String>): Pair<Int, String> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }

            val process = method.invoke(null, command, null, null) as Process
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))
            val sb = StringBuilder()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                sb.appendLine(line)
            }
            while (errReader.readLine().also { line = it } != null) {
                sb.appendLine(line)
            }

            val exitCode = process.waitFor()
            val latency = System.currentTimeMillis() - startTime
            val out = sb.toString().trim()

            // Update session command count
            if (_wirelessSession.value.sessionStatus == WirelessSessionStatus.ACTIVE_CONNECTED) {
                _wirelessSession.value = _wirelessSession.value.copy(
                    commandsExecutedCount = _wirelessSession.value.commandsExecutedCount + 1,
                    latencyMs = latency
                )
            }

            Pair(exitCode, out)
        } catch (e: Exception) {
            Pair(-1, "Process execution error: ${e.localizedMessage}")
        }
    }

    /**
     * Tests local port connectivity for Wireless Debugging ADB port.
     */
    suspend fun testWirelessDebuggingPort(port: Int): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (port !in 1024..65535) {
            return@withContext Pair(false, "Invalid port. Must be between 1024 and 65535.")
        }
        val startTime = System.currentTimeMillis()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
                val lat = System.currentTimeMillis() - startTime
                addLog("SUCCESS", "Port $port on localhost is open and listening (${lat}ms).")
                Pair(true, "Wireless Debugging port $port is active and listening (${lat}ms)!")
            }
        } catch (e: Exception) {
            addLog("WARN", "Port $port unreachable on localhost: ${e.localizedMessage}")
            Pair(false, "Port $port closed or unreachable: ${e.localizedMessage}")
        }
    }

    private fun getDeviceWifiIp(): String {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wm.connectionInfo.ipAddress
            if (ipInt != 0) {
                String.format(
                    Locale.getDefault(),
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            } else "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    fun openWirelessDebuggingSettings(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun openShizukuApp(): Intent? {
        return context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
    }

    fun clearLogs() {
        _logs.value = emptyList()
        addLog("INFO", "Logs cleared.")
    }

    fun cleanup() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Exception) {}
    }
}
