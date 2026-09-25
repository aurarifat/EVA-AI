package com.example

import com.example.eva.context.ContextManager
import com.example.eva.context.MultiStepEngine
import com.example.eva.tools.CalculatorTool
import com.example.eva.tools.ContactTools
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testContextResolutionScenario() {
        val contextManager = ContextManager()

        // Scenario: Open AdGuard
        contextManager.updateActiveApp("AdGuard", "com.adguard.android")
        val state1 = contextManager.sessionState.value
        assertEquals("AdGuard", state1.activeApp?.appName)

        // Scenario: "Turn it on"
        val resolvedOn = contextManager.resolveContextualReference("Turn it on")
        assertNotNull(resolvedOn)
        assertEquals("app_toggle", resolvedOn?.actionType)
        assertEquals("AdGuard", resolvedOn?.target)
        assertEquals("on", resolvedOn?.parameter)

        // Scenario: "Turn it off"
        val resolvedOff = contextManager.resolveContextualReference("Turn it off")
        assertNotNull(resolvedOff)
        assertEquals("app_toggle", resolvedOff?.actionType)
        assertEquals("AdGuard", resolvedOff?.target)
        assertEquals("off", resolvedOff?.parameter)

        // Scenario: "Turn it on again"
        val resolvedOnAgain = contextManager.resolveContextualReference("Turn it on again")
        assertNotNull(resolvedOnAgain)
        assertEquals("app_toggle", resolvedOnAgain?.actionType)
        assertEquals("AdGuard", resolvedOnAgain?.target)
        assertEquals("on", resolvedOnAgain?.parameter)

        // Scenario: Now open YouTube
        contextManager.updateActiveApp("YouTube", "com.google.android.youtube")
        assertEquals("YouTube", contextManager.sessionState.value.activeApp?.appName)
        assertEquals("AdGuard", contextManager.sessionState.value.previousApp?.appName)
    }

    @Test
    fun testFlashlightContextResolution() {
        val contextManager = ContextManager()
        contextManager.updateToolExecution("flashlight", "enable", "Flashlight turned on.", true)

        val resolved = contextManager.resolveContextualReference("turn it off")
        assertNotNull(resolved)
        assertEquals("toggle_tool", resolved?.actionType)
        assertEquals("flashlight", resolved?.toolName)
        assertEquals("disable", resolved?.target)
    }

    @Test
    fun testMultiStepCommandDecomposition() {
        val engine = MultiStepEngine()
        val compoundCommand = "Open YouTube, search for Class 9 Physics, then set media volume to 50 percent"
        val steps = engine.parseSequentialCommand(compoundCommand)

        assertTrue(steps.size >= 3)
        assertTrue(steps[0].contains("YouTube", ignoreCase = true))
        assertTrue(steps[1].contains("Physics", ignoreCase = true))
        assertTrue(steps[2].contains("volume", ignoreCase = true))

        val plan = engine.createPlan(compoundCommand, steps)
        assertEquals(steps.size, plan.steps.size)
        assertEquals(1, plan.steps[0].stepNumber)
    }

    @Test
    fun testCalculatorSafeEngine() {
        val mult = CalculatorTool.calculate("25 times 40")
        assertTrue(mult.isSuccess)
        assertTrue(mult.message.contains("1000"))

        val percent = CalculatorTool.calculate("15 percent of 800")
        assertTrue(percent.isSuccess)
        assertTrue(percent.message.contains("120"))

        val km = CalculatorTool.calculate("convert 5 kilometers to meters")
        assertTrue(km.isSuccess)
        assertTrue(km.message.contains("5000"))
    }

    @Test
    fun testShizukuStateTransitions() {
        val infoNotInstalled = com.example.eva.shizuku.ShizukuInfo(
            status = com.example.eva.shizuku.ShizukuConnectionStatus.NOT_INSTALLED,
            isInstalled = false
        )
        assertFalse(infoNotInstalled.isInstalled)
        assertFalse(infoNotInstalled.isAuthorized)

        val infoConnectedWireless = com.example.eva.shizuku.ShizukuInfo(
            status = com.example.eva.shizuku.ShizukuConnectionStatus.AUTHORIZED_CONNECTED,
            isInstalled = true,
            isBinderAlive = true,
            isAuthorized = true,
            serverVersion = 13,
            serverUid = 2000,
            isWirelessDebuggingAdbMode = true,
            lastPingMessage = "Shizuku Connected: v13 running in Wireless Debugging Shell (UID 2000) mode."
        )
        assertTrue(infoConnectedWireless.isAuthorized)
        assertTrue(infoConnectedWireless.isWirelessDebuggingAdbMode)
        assertEquals(2000, infoConnectedWireless.serverUid)
        assertEquals(13, infoConnectedWireless.serverVersion)

        val infoConnectedRoot = com.example.eva.shizuku.ShizukuInfo(
            status = com.example.eva.shizuku.ShizukuConnectionStatus.AUTHORIZED_CONNECTED,
            isInstalled = true,
            isBinderAlive = true,
            isAuthorized = true,
            serverVersion = 13,
            serverUid = 0,
            isWirelessDebuggingAdbMode = false
        )
        assertFalse(infoConnectedRoot.isWirelessDebuggingAdbMode)
        assertEquals(0, infoConnectedRoot.serverUid)
    }

    @Test
    fun testWirelessDebuggingSessionStateTransitions() {
        var session = com.example.eva.shizuku.WirelessDebuggingSessionState()
        assertEquals(com.example.eva.shizuku.WirelessSessionStatus.DISCONNECTED, session.sessionStatus)
        assertEquals(0, session.commandsExecutedCount)

        session = session.copy(
            sessionStatus = com.example.eva.shizuku.WirelessSessionStatus.CONNECTING,
            statusMessage = "Establishing wireless debugging bridge session..."
        )
        assertEquals(com.example.eva.shizuku.WirelessSessionStatus.CONNECTING, session.sessionStatus)

        session = session.copy(
            sessionStatus = com.example.eva.shizuku.WirelessSessionStatus.ACTIVE_CONNECTED,
            activePort = 5555,
            latencyMs = 12L,
            commandsExecutedCount = 1,
            statusMessage = "Wireless Debugging Bridge Active (UID 2000 | Latency: 12ms)"
        )
        assertEquals(com.example.eva.shizuku.WirelessSessionStatus.ACTIVE_CONNECTED, session.sessionStatus)
        assertEquals(5555, session.activePort)
        assertEquals(12L, session.latencyMs)
        assertEquals(1, session.commandsExecutedCount)
    }

    @Test
    fun testShizukuLogEntryFormatting() {
        val entry = com.example.eva.shizuku.ShizukuLogEntry(
            timestamp = 1700000000000L,
            level = "SUCCESS",
            message = "Shizuku IPC binder token received from server."
        )
        assertEquals("SUCCESS", entry.level)
        assertTrue(entry.formattedTime.isNotBlank())
        assertTrue(entry.message.contains("Shizuku IPC"))
    }

    @Test
    fun testBubbleOverlayStateTransitions() {
        var state = com.example.eva.overlay.BubbleOverlayUiState()
        assertEquals(com.example.eva.overlay.BubbleMode.IDLE, state.mode)
        assertEquals("EVA Ready", state.statusText)
        assertFalse(state.isProcessing)

        // Transition to LISTENING
        state = state.copy(
            mode = com.example.eva.overlay.BubbleMode.LISTENING,
            statusText = "Listening...",
            rmsLevel = 0.75f
        )
        assertEquals(com.example.eva.overlay.BubbleMode.LISTENING, state.mode)
        assertEquals("Listening...", state.statusText)
        assertEquals(0.75f, state.rmsLevel, 0.001f)

        // User speaks
        state = state.copy(
            recognizedText = "Open AdGuard"
        )
        assertEquals("Open AdGuard", state.recognizedText)

        // Processing & SPEAKING
        state = state.copy(
            mode = com.example.eva.overlay.BubbleMode.SPEAKING,
            statusText = "Speaking...",
            spokenText = "Opened AdGuard. I am waiting for your next commands."
        )
        assertEquals(com.example.eva.overlay.BubbleMode.SPEAKING, state.mode)
        assertEquals("Speaking...", state.statusText)
        assertTrue(state.spokenText.contains("AdGuard"))

        // Returns to IDLE - overlay stays active!
        state = state.copy(
            mode = com.example.eva.overlay.BubbleMode.IDLE,
            statusText = "EVA Ready",
            recognizedText = "",
            spokenText = ""
        )
        assertEquals(com.example.eva.overlay.BubbleMode.IDLE, state.mode)
        assertEquals("EVA Ready", state.statusText)
    }
}
