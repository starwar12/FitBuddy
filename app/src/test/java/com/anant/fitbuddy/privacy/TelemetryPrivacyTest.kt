package com.anant.fitbuddy.privacy

import android.app.Application
import com.anant.fitbuddy.crash.CrashReporter
import com.anant.fitbuddy.crash.HeartbeatInfo
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.reflect.full.primaryConstructor

/**
 * Privacy issue 3, "Named telemetry despite opt-out" (report Section 3.4), data flow 5 and
 * component "Telemetry".
 *
 * All outgoing connections go to a local [BlockingProxy] that records and refuses them, so no
 * test heartbeat ever reaches Sentry or the developer's proxy. The tests fail until heartbeats
 * respect the user's choice and stop carrying the user's name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TelemetryPrivacyTest {

    private lateinit var proxy: BlockingProxy

    @Before
    fun setUp() {
        proxy = BlockingProxy()
    }

    @After
    fun tearDown() {
        proxy.close()
    }

    /** After opting out, the app must not contact Sentry or the developer's telemetry proxy. */
    @Test
    fun heartbeat_isNotSent_afterOptingOutOfCrashReports() {
        CrashReporter.init(
            app = RuntimeEnvironment.getApplication(),
            enabled = false,
            supportId = UUID.randomUUID().toString()
        )

        val reportedSent = CrashReporter.sendHeartbeat(
            HeartbeatInfo(aiProvider = "OPENROUTER", recordCount = 42)
        )
        Thread.sleep(1_000) // let Sentry's background transport attempt any upload

        assertTrue(
            "PRIVACY ISSUE 3 NOT FIXED (report Section 3.4, data flow 5): with crash reporting " +
                "turned off, the daily heartbeat still tried to contact: ${proxy.attempts}. " +
                "The settings screen promises the user can turn this off.",
            proxy.attempts.isEmpty() && !reportedSent
        )
    }

    /** Usage statistics do not need the user's identity, so the heartbeat must not carry a name. */
    @Test
    fun heartbeat_carriesNoPersonalName() {
        val nameFields = HeartbeatInfo::class.primaryConstructor!!.parameters
            .mapNotNull { it.name }
            .filter { Regex("(?i)name|first|last|user").containsMatchIn(it) }

        assertTrue(
            "PRIVACY ISSUE 3 NOT FIXED (report Section 3.4, threat I1): the heartbeat sent to " +
                "the developer has the field(s) $nameFields, which the app fills with the user's " +
                "first and last name, although its settings call the heartbeat anonymous.",
            nameFields.isEmpty()
        )
    }
}
