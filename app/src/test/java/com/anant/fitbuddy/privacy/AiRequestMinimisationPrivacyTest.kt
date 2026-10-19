package com.anant.fitbuddy.privacy

import android.app.Application
import org.robolectric.RuntimeEnvironment
import com.anant.fitbuddy.data.database.BodyMeasurement
import com.anant.fitbuddy.data.database.UserProfile
import com.anant.fitbuddy.data.remote.NetworkModule
import com.anant.fitbuddy.data.remote.UpdateChecker
import com.anant.fitbuddy.data.settings.AiProvider
import com.anant.fitbuddy.data.settings.AppSettings
import com.anant.fitbuddy.ui.viewmodel.MainViewModel
import com.anant.fitbuddy.util.DateUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Privacy issue 1, "Health profile sent with every AI request" (report Section 3.2), data flow 1.
 *
 * Estimating the calories of a meal does not require the user's body composition or identity, so
 * a meal-estimation request must not contain them (data minimisation, Art. 5(1)(c) GDPR). The
 * test drives the real ViewModel and repository and inspects the request that would be sent to
 * the AI provider. It fails until the request is minimised.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AiRequestMinimisationPrivacyTest {

    private lateinit var proxy: BlockingProxy
    private lateinit var graph: PrivacyTestGraph
    private val aiApi = RecordingAiApi()
    private val uiScope = CoroutineScope(Dispatchers.Default)

    @Before
    fun setUp() {
        proxy = BlockingProxy()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        graph = PrivacyTestGraph(RuntimeEnvironment.getApplication(), aiApi)
        println("testingboot")
    }

    @After
    fun tearDown() {
        uiScope.cancel()
        Dispatchers.resetMain()
        graph.close()
        proxy.close()
    }

    @Test
    fun mealEstimateRequest_doesNotDiscloseBodyCompositionOrName() = runBlocking {
        val now = System.currentTimeMillis()
        graph.settingsRepository.save(
            AppSettings(
                provider = AiProvider.OPENROUTER,
                openRouterApiKey = "test-key",
                openRouterModel = "test/vision-model:free",
                firstName = "Jane",
                lastName = "Doe"
            )
        )
        graph.repository.saveProfile(
            UserProfile(
                age = 34,
                weightKg = 82.0,
                heightCm = 180.0,
                dailyTargetCalories = 2400,
                targetProteinG = 150,
                targetCarbsG = 250,
                targetFatsG = 70,
                lastUpdatedTimestamp = now,
                sex = "FEMALE"
            )
        )
        graph.repository.addMeasurement(
            BodyMeasurement(
                timestamp = now,
                dateString = DateUtils.today(),
                weightKg = 82.0,
                bodyFatPct = 27.5,
                bmr = 1650,
                muscleMassKg = 55.1
            )
        )

        val viewModel = MainViewModel(
            repository = graph.repository,
            settingsRepository = graph.settingsRepository,
            updateChecker = UpdateChecker(
                githubApi = NetworkModule.provideGithubApi(),
                fdroidApi = NetworkModule.provideFdroidApi(),
                okHttpClient = NetworkModule.okHttpClient()
            )
        )
        // The dashboard screen keeps these flows subscribed while the user logs a meal.
        uiScope.launch { viewModel.dashboardState.collect {} }
        uiScope.launch { viewModel.latestMeasurement.collect {} }
        withTimeout(10_000) {
            while (viewModel.latestMeasurement.value == null ||
                viewModel.dashboardState.value.profile == null ||
                !viewModel.settings.value.isConfigured
            ) delay(20)
        }

        viewModel.analyzeText("two boiled eggs and a slice of toast")
        withTimeout(10_000) { while (aiApi.requests.isEmpty()) delay(20) }

        val sent = aiApi.sentJson()
        val leaked = mapOf(
            "body-fat percentage (27.5)" to "27.5",
            "basal metabolic rate (1650)" to "1650",
            "muscle mass (55.1)" to "55.1",
            "first name (Jane)" to "Jane"
        ).filterValues { sent.contains(it) }.keys

        assertTrue(
            "PRIVACY ISSUE 1 NOT FIXED (report Section 3.2, data flow 1): the meal-estimation " +
                "request sent to the AI provider discloses ${leaked.joinToString()}. Only data " +
                "needed to estimate the meal may be sent (Art. 5(1)(c) GDPR).",
            leaked.isEmpty()
        )
    }
}
