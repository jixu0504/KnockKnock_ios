@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.rhodes.privatechat.viewmodel

import android.app.Application
import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.db.DatabaseWrapper
import com.rhodes.privatechat.shared.domain.chat.FakeAiEngine
import com.rhodes.privatechat.shared.model.AiMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.model.ReplyTurn
import com.rhodes.privatechat.shared.network.AIService
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.viewmodel.shared.AppStateHolder
import com.rhodes.privatechat.viewmodel.shared.OperatorStateUpdater
import com.rhodes.privatechat.viewmodel.shared.SharedUtils
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.SettingsListener
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * T28 批 C 步骤 1：ChatViewModel 测试替身基础设施（无网络、无真机）。
 *
 * 组成：
 * - Robolectric 宿主：真实 `Application` + Android SQLDelight 驱动（内存/临时文件库）；
 * - [InMemoryObservableSettings]：确定性设置存储（等价 shared androidUnitTest 的同名实现）；
 * - [FakeModelServer]：Ktor MockEngine 模拟模型厂商（回复/失败/延迟可编程），AIService 走真实解析链；
 * - 真实 [ChatRepository]/[SettingsRepository]/[SharedUtils]/[OperatorStateUpdater]，只替换模型侧。
 *
 * 纪律：这些测试是「逐入口替换」的基线回归网——改动前必须先全绿，改动后必须逐入口复跑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
abstract class RobolectricChatTest {

    protected lateinit var env: ChatTestEnv

    @Before
    fun setUpChatTestEnv() {
        // 让 viewModelScope / Main 调度在测试中可推进，同时保留真实 IO/DB 车道。
        Dispatchers.setMain(Dispatchers.Default)
        // WorkManager 在 VM 成功路径有 Android 专属调度（ManualReplyScheduler/ContextMaintenanceScheduler），
        // Robolectric 下用官方测试初始化器提供可用的 WorkManager 单例。
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication())
        env = ChatTestEnv()
    }

    @After
    fun tearDownChatTestEnv() {
        Dispatchers.resetMain()
        env.close()
    }
}

/** JVM 内存 ObservableSettings（multiplatform-settings-test 未引入，手写等价物）。 */
class InMemoryObservableSettings : ObservableSettings {
    private val store = mutableMapOf<String, Any>()

    override val keys: Set<String> get() = store.keys
    override val size: Int get() = store.size
    override fun clear() = store.clear()
    override fun remove(key: String) {
        store.remove(key)
    }

    override fun hasKey(key: String): Boolean = store.containsKey(key)
    override fun putInt(key: String, value: Int) {
        store[key] = value
    }

    override fun getInt(key: String, defaultValue: Int): Int = store[key] as? Int ?: defaultValue
    override fun getIntOrNull(key: String): Int? = store[key] as? Int
    override fun putLong(key: String, value: Long) {
        store[key] = value
    }

    override fun getLong(key: String, defaultValue: Long): Long = store[key] as? Long ?: defaultValue
    override fun getLongOrNull(key: String): Long? = store[key] as? Long
    override fun putString(key: String, value: String) {
        store[key] = value
    }

    override fun getString(key: String, defaultValue: String): String = store[key] as? String ?: defaultValue
    override fun getStringOrNull(key: String): String? = store[key] as? String
    override fun putFloat(key: String, value: Float) {
        store[key] = value
    }

    override fun getFloat(key: String, defaultValue: Float): Float = store[key] as? Float ?: defaultValue
    override fun getFloatOrNull(key: String): Float? = store[key] as? Float
    override fun putDouble(key: String, value: Double) {
        store[key] = value
    }

    override fun getDouble(key: String, defaultValue: Double): Double = store[key] as? Double ?: defaultValue
    override fun getDoubleOrNull(key: String): Double? = store[key] as? Double
    override fun putBoolean(key: String, value: Boolean) {
        store[key] = value
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = store[key] as? Boolean ?: defaultValue
    override fun getBooleanOrNull(key: String): Boolean? = store[key] as? Boolean
    private val noop = object : SettingsListener { override fun deactivate() {} }

    override fun addIntListener(key: String, defaultValue: Int, callback: (Int) -> Unit): SettingsListener = noop
    override fun addLongListener(key: String, defaultValue: Long, callback: (Long) -> Unit): SettingsListener = noop
    override fun addStringListener(key: String, defaultValue: String, callback: (String) -> Unit): SettingsListener = noop
    override fun addFloatListener(key: String, defaultValue: Float, callback: (Float) -> Unit): SettingsListener = noop
    override fun addDoubleListener(key: String, defaultValue: Double, callback: (Double) -> Unit): SettingsListener = noop
    override fun addBooleanListener(key: String, defaultValue: Boolean, callback: (Boolean) -> Unit): SettingsListener = noop
    override fun addIntOrNullListener(key: String, callback: (Int?) -> Unit): SettingsListener = noop
    override fun addLongOrNullListener(key: String, callback: (Long?) -> Unit): SettingsListener = noop
    override fun addStringOrNullListener(key: String, callback: (String?) -> Unit): SettingsListener = noop
    override fun addFloatOrNullListener(key: String, callback: (Float?) -> Unit): SettingsListener = noop
    override fun addDoubleOrNullListener(key: String, callback: (Double?) -> Unit): SettingsListener = noop
    override fun addBooleanOrNullListener(key: String, callback: (Boolean?) -> Unit): SettingsListener = noop
}

/**
 * 可编程的模型厂商替身：MockEngine 拦截 AIService 的 HTTP 请求，返回确定性的 OpenAI 兼容响应。
 * [reply] 是模型 content 原文（通常是 OfflineModeResponse JSON）；[failMessage] 非空时返回 500。
 */
class FakeModelServer {

    @Volatile
    var reply: String = FakeAiEngine.FAKE_REPLY_JSON

    /** 非空时 HTTP 500（模拟厂商错误，等价 oracle 的 send_failed 路径）。 */
    @Volatile
    var failMessage: String? = null

    /** 每次请求前的人为延迟（模拟慢模型 / 取消窗口）。 */
    @Volatile
    var delayMs: Long = 0L

    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    fun reset() {
        reply = FakeAiEngine.FAKE_REPLY_JSON
        failMessage = null
        delayMs = 0L
        requests.clear()
    }

    private val engine = MockEngine { request ->
        requests += "${request.method.value} ${request.url.encodedPath}"
        if (delayMs > 0) delay(delayMs)
        val failure = failMessage
        if (failure != null) {
            respond(
                content = """{"error":{"message":"$failure","type":"test_error"}}""",
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        } else {
            respond(
                content = buildJsonObject {
                    put("choices", buildJsonArray {
                        add(buildJsonObject {
                            put("message", buildJsonObject {
                                put("role", "assistant")
                                put("content", reply)
                            })
                            put("finish_reason", "stop")
                        })
                    })
                    put("usage", buildJsonObject {
                        put("prompt_tokens", 120)
                        put("completion_tokens", 60)
                        put("total_tokens", 180)
                    })
                }.toString(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
    }

    val client: HttpClient = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
        }
    }
}

/**
 * ChatViewModel 宿主环境：真实 DB/仓库 + 假模型。测试只通过公开 VM API 与 DB 观测断言。
 */
class ChatTestEnv {

    val application: Application = RuntimeEnvironment.getApplication()
    val settingsStore = InMemoryObservableSettings()
    val settings = SettingsRepository(settingsStore)
    val databaseWrapper = DatabaseWrapper(application)
    val repository = ChatRepository(databaseWrapper, settings)
    val model = FakeModelServer()
    val sharedUtils = SharedUtils(repository, settings, AIService(client = model.client))
    val appState = AppStateHolder(CoroutineScope(Dispatchers.Default), repository, settings)
    val operatorStateUpdater = OperatorStateUpdater(repository, settings, sharedUtils) { appState.operators.value }

    val toasts: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
    val unhiddenSessions: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    init {
        // 确定性聊天配置：custom 厂商 + 非空 key/url，请求全部被 MockEngine 拦截。
        settings.provider = "custom"
        settings.apiKey = "test-key"
        settings.modelName = "test-model"
        settings.customUrl = "http://localhost/mock/chat/completions"
        // 关掉每日摘要/记忆生成等后台模型调用，避免与测试脚本争用模型替身。
        settings.memoryV2Enabled = false
        settings.dailySummaryDate = sharedUtils.beijingSdf("yyyyMMdd").format(java.util.Date())
    }

    fun createViewModel(): ChatViewModel = ChatViewModel(
        application = application,
        repository = repository,
        settings = settings,
        sharedUtils = sharedUtils,
        operatorStateUpdater = operatorStateUpdater,
        appState = appState,
        memoryVectorService = null,
        visionGateway = null,
        onShowToast = { toasts += it },
        onUnhideSession = { unhiddenSessions += it },
        onRefreshOperatorStatus = {},
    )

    /** 种一个群聊会话（成员资料 + session 行，群 prompt/解析路径可直接工作）。 */
    suspend fun seedGroup(
        groupId: String = "g1",
        memberIds: List<String> = listOf("op1"),
        mutedIds: List<String> = emptyList(),
    ): ChatSession {
        memberIds.forEach { id -> repository.insertOperator(Operator(id = id, name = "角色$id")) }
        val session = ChatSession(
            id = groupId, operatorId = "group_$groupId", operatorName = "测试群",
            members = memberIds.joinToString(","), mutedMembers = mutedIds.joinToString(","),
        )
        repository.sessions.insertSession(session)
        return session
    }

    /** 群聊 VM（与生产 MainViewModel 相同的接线，测试只替换模型/记忆/通知端口）。 */
    fun createGroupViewModel(): GroupChatViewModel = GroupChatViewModel(
        context = application,
        repository = repository,
        settings = settings,
        sharedUtils = sharedUtils,
        appState = appState,
        markSessionRead = {},
        unhideSession = {},
        getUserProfile = { com.rhodes.privatechat.viewmodel.shared.UserProfile() },
        getPromptTemplate = { type, mode ->
            settings.resolvePromptTemplate(type, mode, com.rhodes.privatechat.viewmodel.shared.PromptTemplates.get(type, mode), com.rhodes.privatechat.viewmodel.shared.PromptTemplates.VERSION)
        },
        isPromptTemplateCustom = { type, mode -> settings.isPromptTemplateCustom(type, mode) },
        getPromptModule = { module, type, mode ->
            val saved = settings.getPromptModule(module, type, mode)
            if (settings.isPromptModuleCustom(module, type, mode)) {
                saved
            } else {
                when (module) {
                    "protocol" -> com.rhodes.privatechat.viewmodel.PromptModuleDefaults.outputProtocol(type, mode)
                    "runtime" -> com.rhodes.privatechat.viewmodel.PromptModuleDefaults.runtime(type, mode)
                    "behavior" -> com.rhodes.privatechat.viewmodel.PromptModuleDefaults.behavior(type, mode)
                    else -> ""
                }
            }
        },
        sessionMessageCounter = ConcurrentHashMap(),
        memoryVectorService = null,
        visionGateway = null,
        showNotification = { _, _, _ -> },
    )

    /** 种一个私聊会话（operator + session 行齐全，结算/提示词路径可直接工作）。 */
    suspend fun seedPrivateChat(sessionId: String = "s1", operatorId: String = "op1"): ChatSession {
        repository.insertOperator(Operator(id = operatorId, name = "角色$operatorId"))
        val session = ChatSession(id = sessionId, operatorId = operatorId, operatorName = "角色$operatorId")
        repository.sessions.insertSession(session)
        return session
    }

    suspend fun seedReplyTurn(turnId: String, sessionId: String = "s1", sourceMessageId: Long) {
        val now = System.currentTimeMillis()
        repository.replyTurns.createIfAbsent(
            ReplyTurn(
                id = turnId, sessionId = sessionId, surface = "private", triggerKind = "manual",
                sourceMessageId = sourceMessageId, autoPlanToken = "", mode = "online",
                status = "pending", attemptCount = 0, nextAttemptAt = 0L, leaseToken = "",
                leaseUntil = 0L, responseMessageId = null, lastError = "", createdAt = now,
                updatedAt = now, completedAt = 0L,
            ),
        )
    }

    fun close() {
        runCatching { model.client.close() }
    }

    // === 轮询等待（真实 DB/IO 车道，不用虚拟时间） ===

    fun waitUntil(timeoutMs: Long = 30_000L, block: suspend () -> Boolean): Boolean = runBlocking {
        withTimeoutOrNull(timeoutMs) {
            while (!block()) delay(20)
            true
        } != null
    }

}
