package com.anant.fitbuddy.privacy

import android.content.Context
import androidx.room.Room
import com.anant.fitbuddy.data.backup.BackupManager
import com.anant.fitbuddy.data.backup.crypto.BackupCrypto
import com.anant.fitbuddy.data.database.AppDatabase
import com.anant.fitbuddy.data.remote.AiApi
import com.anant.fitbuddy.data.remote.NetworkModule
import com.anant.fitbuddy.data.remote.OpenFoodFactsDataSource
import com.anant.fitbuddy.data.remote.RemoteAiDataSource
import com.anant.fitbuddy.data.remote.dto.ChatRequest
import com.anant.fitbuddy.data.remote.dto.ChatRequestPlain
import com.anant.fitbuddy.data.remote.dto.ChatResponse
import com.anant.fitbuddy.data.remote.dto.Choice
import com.anant.fitbuddy.data.remote.dto.GeminiModelsResponse
import com.anant.fitbuddy.data.remote.dto.ModelsResponse
import com.anant.fitbuddy.data.remote.dto.ResponseMessage
import com.anant.fitbuddy.data.remote.exercisedb.ExerciseCatalogRepository
import com.anant.fitbuddy.data.repository.FitnessRepository
import com.anant.fitbuddy.data.settings.SettingsRepository
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Routes every outgoing HTTP(S) connection of the JVM through a local proxy that records the
 * request line and refuses it. Privacy tests use it to observe what the app tries to send while
 * guaranteeing that no test data ever reaches a real server.
 */
class BlockingProxy : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val previous: ProxySelector? = ProxySelector.getDefault()
    val attempts: MutableList<String> = CopyOnWriteArrayList()

    init {
        thread(isDaemon = true, name = "privacy-blocking-proxy") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                socket.use {
                    val requestLine = it.getInputStream().bufferedReader().readLine().orEmpty()
                    attempts += requestLine
                    it.getOutputStream().write(
                        "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .toByteArray()
                    )
                }
            }
        }
        val address = InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = listOf(Proxy(Proxy.Type.HTTP, address))
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        })
    }

    override fun close() {
        ProxySelector.setDefault(previous)
        server.close()
    }
}

/** AI endpoint stand-in that records each outgoing chat request and returns a fixed estimate. */
class RecordingAiApi : AiApi {
    val requests: MutableList<Any> = CopyOnWriteArrayList()

    private fun estimate() = ChatResponse(
        choices = listOf(
            Choice(
                message = ResponseMessage(
                    role = "assistant",
                    content = """{"status":"SUCCESS","clarification_message":null,""" +
                        """"food_analysis":{"dish_name":"Eggs on toast","macros":{"calories":300,""" +
                        """"protein_g":18,"carbs_g":20,"fats_g":15},"ingredients":null},""" +
                        """"exercise_analysis":null}"""
                )
            )
        )
    )

    override suspend fun chatCompletion(url: String, authorization: String?, request: ChatRequest): ChatResponse {
        requests += request
        return estimate()
    }

    override suspend fun chatCompletionPlain(url: String, authorization: String?, request: ChatRequestPlain): ChatResponse {
        requests += request
        return estimate()
    }

    override suspend fun probeChatCompletion(
        url: String,
        authorization: String?,
        request: ChatRequestPlain
    ): Response<ResponseBody> = Response.success(ByteArray(0).toResponseBody(null))

    override suspend fun listModels(url: String, authorization: String?) = ModelsResponse()

    override suspend fun listGeminiModels(url: String) = GeminiModelsResponse()

    /** JSON of everything sent to the AI provider, exactly as Retrofit would serialise it. */
    fun sentJson(): String = requests.joinToString("\n") { request ->
        when (request) {
            is ChatRequest -> NetworkModule.moshi.adapter(ChatRequest::class.java).toJson(request)
            is ChatRequestPlain -> NetworkModule.moshi.adapter(ChatRequestPlain::class.java).toJson(request)
            else -> request.toString()
        }
    }
}

/** The production object graph of [com.anant.fitbuddy.FitBuddyApp], on an in-memory database. */
class PrivacyTestGraph(context: Context, aiApi: AiApi = RecordingAiApi()) : Closeable {
    val database: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val settingsRepository = SettingsRepository(context)
    val backupManager = BackupManager(
        context = context,
        userProfileDao = database.userProfileDao(),
        foodLogDao = database.foodLogDao(),
        mealFoodDao = database.mealFoodDao(),
        exerciseLogDao = database.exerciseLogDao(),
        savedFoodDao = database.savedFoodDao(),
        mealPresetDao = database.mealPresetDao(),
        exercisePresetDao = database.exercisePresetDao(),
        exerciseUsageDao = database.exerciseUsageDao(),
        bodyMeasurementDao = database.bodyMeasurementDao(),
        workoutSessionDao = database.workoutSessionDao(),
        workoutExerciseDao = database.workoutExerciseDao(),
        settingsRepository = settingsRepository,
        crypto = BackupCrypto(NetworkModule.moshi),
        moshi = NetworkModule.moshi
    )
    val repository = FitnessRepository(
        userProfileDao = database.userProfileDao(),
        foodLogDao = database.foodLogDao(),
        mealFoodDao = database.mealFoodDao(),
        exerciseLogDao = database.exerciseLogDao(),
        savedFoodDao = database.savedFoodDao(),
        mealPresetDao = database.mealPresetDao(),
        exercisePresetDao = database.exercisePresetDao(),
        exerciseUsageDao = database.exerciseUsageDao(),
        bodyMeasurementDao = database.bodyMeasurementDao(),
        workoutSessionDao = database.workoutSessionDao(),
        workoutExerciseDao = database.workoutExerciseDao(),
        exerciseCatalogRepository = ExerciseCatalogRepository(context),
        remoteAiDataSource = RemoteAiDataSource(aiApi, NetworkModule.moshi),
        openFoodFactsDataSource = OpenFoodFactsDataSource(NetworkModule.provideOpenFoodFactsApi()),
        settingsRepository = settingsRepository,
        backupManager = backupManager
    )

    override fun close() = database.close()
}
