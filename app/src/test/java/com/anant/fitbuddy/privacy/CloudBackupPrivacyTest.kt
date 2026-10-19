package com.anant.fitbuddy.privacy

import android.app.Application
import com.anant.fitbuddy.data.repository.FitnessRepository
import com.anant.fitbuddy.data.settings.AiProvider
import com.anant.fitbuddy.data.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Privacy issue 2, "Cloud backups protected by a shareable identifier" (report Section 3.3),
 * data flow 6 and component "Backup & restore".
 *
 * These are security tests that protect a privacy property: the confidentiality of the user's
 * health history (Art. 9 GDPR) in the cloud backup held by the developer. They fail until the
 * recommendations are implemented.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CloudBackupPrivacyTest {

    private lateinit var proxy: BlockingProxy
    private lateinit var graph: PrivacyTestGraph

    @Before
    fun setUp() {
        proxy = BlockingProxy()
        graph = PrivacyTestGraph(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        graph.close()
        proxy.close()
    }

    /**
     * Without a user-chosen password, the backup key is derived from the support ID. That ID is
     * the backup's address on the server, is attached to crash reports and is shared in bug
     * reports, so anyone who knows it can decrypt the backup.
     *
     * Cloud uploads are compiled out of debug builds (the only unit-test variant), so the test
     * calls the passphrase selection that `uploadMongoBackup()` uses. Refusing to upload without a
     * user password, or using a secret key, both count as fixed.
     */
    @Test
    fun cloudBackupKey_isNotDerivedFromSupportId() = runBlocking {
        val supportId = graph.settingsRepository.ensureSupportId()
        val settings = graph.settingsRepository.settings.first().copy(
            cloudBackupEnabled = true,
            cloudBackupPasswordSet = false
        )

        val resolver = FitnessRepository::class.java.declaredMethods
            .firstOrNull { it.name == "resolveUploadPassword" }
            ?.apply { isAccessible = true }
        checkNotNull(resolver) {
            "FitnessRepository.resolveUploadPassword() no longer exists; update this test to the " +
                "new passphrase selection for cloud backups."
        }
        val ignore = object : Continuation<Any?> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Any?>) = Unit
        }

        val passphrase = try {
            resolver.invoke(graph.repository, settings, supportId, ignore) as? CharArray
        } catch (refused: InvocationTargetException) {
            null // refusing to upload without a user-chosen password protects the backup
        }

        assertFalse(
            "PRIVACY ISSUE 2 NOT FIXED (report Section 3.3, data flow 6): without a user " +
                "password, the cloud backup is encrypted with a key derived from the support ID " +
                "($supportId). The support ID is the backup's address on the server and is " +
                "shared in bug reports, so anyone who knows it can decrypt the user's health history.",
            passphrase != null && String(passphrase) == supportId
        )
    }

    /**
     * The cloud backup (and a local export) contains the user's AI API keys, so whoever can open
     * the backup also gains access to the user's account at the AI provider, including the
     * provider-side history of their meal photos and health context.
     */
    @Test
    fun backupPayload_doesNotContainAiApiKeys() = runBlocking {
        val apiKey = "sk-or-v1-privacy-test-key-0123456789"
        graph.settingsRepository.save(
            AppSettings(
                provider = AiProvider.OPENROUTER,
                openRouterApiKeys = listOf(apiKey),
                openRouterModel = "test/vision-model:free"
            )
        )

        val payload = graph.backupManager.encode(graph.backupManager.buildBackupData())

        assertFalse(
            "PRIVACY ISSUE 2 NOT FIXED (report Section 3.3, threat DD4): the backup payload that " +
                "is uploaded to the cloud contains the user's AI API key. Whoever decrypts the " +
                "backup can then read the user's AI history at the provider.",
            payload.contains(apiKey)
        )
    }
}
