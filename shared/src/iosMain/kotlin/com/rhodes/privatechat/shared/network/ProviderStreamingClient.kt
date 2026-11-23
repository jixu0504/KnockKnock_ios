package com.rhodes.privatechat.shared.network

import com.rhodes.privatechat.shared.domain.chat.ChatStageBudget
import com.rhodes.privatechat.shared.domain.chat.ChatStageTimeoutException
import com.rhodes.privatechat.shared.domain.chat.StreamingClient
import com.rhodes.privatechat.shared.model.AiMessage
import com.rhodes.privatechat.shared.model.ChatCompletionRequest
import com.rhodes.privatechat.shared.settings.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.TimeSource

/**
 * T37 —— iOS 真实模型供应商客户端（Ktor Darwin 流式）。
 *
 * 设计（与规格 `ios-port/specs/provider-integration-spec.md` 一致）：
 * - 11 家供应商全部走 **OpenAI 兼容** `chat/completions` 流式协议。Google / Anthropic 使用各自官方
 *   的 OpenAI 兼容端点（Gemini `/v1beta/openai/chat/completions`；Anthropic `/v1/chat/completions`），
 *   避免在 iOS 侧再维护 Gemini / Messages 两套协议解析；自定义端点要求用户填 OpenAI 兼容 URL。
 * - 统一 SSE 解析：`data: {...}` 行、`data: [DONE]`、注释行（keep-alive）、流内 error 事件、
 *   **半包累积**（按字节缓冲到 `\n` 再解码，UTF-8 多字节字符跨 TCP 分片不会被截断）；
 *   非 `text/event-stream` 响应退化为一次性 JSON 解析。
 * - 超时：首 token 超时（默认 30s）+ 整轮预算（跟随引擎 `ChatStageBudget.MODEL_BUDGET_MS`，
 *   DeepSeek 深度思考开启时 + `THINKING_BUDGET_BONUS_MS`），超时抛
 *   [ChatStageTimeoutException]（引擎按「回复超时了，再试一次吧」用户文案分类）。
 * - 取消：协程取消经 Ktor Darwin 直接断流（T9 实测 cancel-and-join 1ms），本类**不**吞
 *   CancellationException。
 * - 错误映射：401/403 未授权、429 限流、5xx 服务端故障、400/404/413 参数/模型/上下文问题、
 *   网络断开、空响应。消息前缀沿用 Android oracle 的 `API error <code>:`，并行复用
 *   [ModelErrorMessages] 的中文可操作提示与密钥脱敏。
 *
 * 引擎契约（`StreamingClient.complete`）是「整包返回」，本类内部消费完整 SSE 流后返回累计全文，
 * 与 lead R2 裁决（iOS 沿用整包落库、delta 仅 UI 打字机层）一致。
 */

// ---------------------------------------------------------------------------
// 诊断日志（System log 可见；不打印密钥/消息内容）
// ---------------------------------------------------------------------------

internal fun providerLog(message: String) {
    // 注意：K/N 2.3 下 NSLog 的 %@ vararg 不自动桥接 Kotlin String（会 SIGSEGV），
    // 统一用「拼接 format」的写法规避。
    platform.Foundation.NSLog("[RhodesLink][ProviderNative] " + message)
}

// ---------------------------------------------------------------------------
// 供应商目录（11 家；复用 commonMain providers 与 resolveProviderEndpoint）
// ---------------------------------------------------------------------------

internal object IosProviderCatalog {
    /** 顺序与 Android 设置页一致（11 家聊天供应商）。 */
    val ids: List<String> = listOf(
        "deepseek", "minimax", "byte", "google", "anthropic", "ali",
        "zhipu", "siliconflow", "xiaomi", "openai_compat", "custom",
    )

    fun config(providerId: String) = providers[providerId]

    /** 展示名（未知 id 原样返回，避免崩溃）。 */
    fun displayName(providerId: String): String = providers[providerId]?.name ?: providerId

    /**
     * 实际 POST 端点。Google / Anthropic 用官方 OpenAI 兼容端点；其余沿用
     * `ProviderConfig.baseUrl`（MiniMax abab 老模型的端点切换由 [resolveProviderEndpoint] 处理）。
     */
    fun endpoint(providerId: String, model: String, customUrl: String): String = when (providerId) {
        "custom" -> normalizeCustomUrl(customUrl)
        "google" -> "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        "anthropic" -> "https://api.anthropic.com/v1/chat/completions"
        else -> resolveProviderEndpoint(providerId, model, providers[providerId]?.baseUrl.orEmpty())
    }

    /**
     * 自定义端点宽容归一：用户常只填 `https://x/v1`。已含 `chat/completions` 原样使用，
     * 否则补 `/chat/completions`。
     */
    fun normalizeCustomUrl(raw: String): String {
        val url = raw.trim().trimEnd('/')
        if (url.isEmpty()) return ""
        if (url.contains("chat/completions")) return url
        return "$url/chat/completions"
    }

    /** 该供应商的鉴权头：小米 MiMo 用 `api-key`，其余 OpenAI 兼容用 Bearer。 */
    fun usesApiKeyHeader(providerId: String): Boolean = providerId == "xiaomi"
}

data class ProviderRuntimeConfig(
    val providerId: String,
    val providerName: String,
    val model: String,
    val baseUrl: String,
    /** 实际读取到的密钥（Keychain 优先，旧 NSUserDefaults 明文兜底）——**不要**写日志/UI。 */
    val apiKey: String,
    val temperature: Double,
    val thinkingEnabled: Boolean,
) {
    /** 空串 = 已配置；否则是给用户看的中文缺失原因。 */
    val missingReason: String
        get() = when {
            providerId.isBlank() -> "未选择供应商"
            IosProviderCatalog.config(providerId) == null -> "未知供应商：$providerId"
            model.isBlank() -> "未填写模型名"
            baseUrl.isBlank() -> "端点地址为空（自定义端点请填写 OpenAI 兼容地址）"
            apiKey.isBlank() -> "未填写 API Key"
            else -> ""
        }

    val isConfigured: Boolean get() = missingReason.isEmpty()
}

/** 引擎预算派生的真实客户端超时策略。 */
data class ProviderTimeouts(
    val firstTokenMs: Long = DEFAULT_FIRST_TOKEN_MS,
    val totalMs: Long = ChatStageBudget.MODEL_BUDGET_MS,
) {
    companion object {
        const val DEFAULT_FIRST_TOKEN_MS = 30_000L

        /** 深度思考开启时整轮预算跟随引擎 +60s（oracle :124 / ChatEngineConfig.effectiveModelBudgetMs）。 */
        fun forConfig(config: ProviderRuntimeConfig): ProviderTimeouts {
            val thinkingBonus =
                if (config.thinkingEnabled && config.providerId == "deepseek") ChatStageBudget.THINKING_BUDGET_BONUS_MS else 0L
            return ProviderTimeouts(totalMs = ChatStageBudget.MODEL_BUDGET_MS + thinkingBonus)
        }
    }
}

// ---------------------------------------------------------------------------
// 错误映射
// ---------------------------------------------------------------------------

enum class ProviderErrorKind {
    Unauthorized,
    RateLimited,
    ServerError,
    BadRequest,
    Network,
    Timeout,
    FirstTokenTimeout,
    EmptyResponse,
    Config,
    Other,
}

class ProviderException(
    val kind: ProviderErrorKind,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal object ProviderErrorMapper {
    fun forHttpStatus(status: Int, rawBody: String, apiKey: String, providerId: String, model: String): ProviderException {
        val kind = when (status) {
            401, 403 -> ProviderErrorKind.Unauthorized
            429 -> ProviderErrorKind.RateLimited
            in 500..599 -> ProviderErrorKind.ServerError
            400, 404, 413, 422 -> ProviderErrorKind.BadRequest
            else -> ProviderErrorKind.Other
        }
        val detail = ModelErrorMessages.redact(rawBody, apiKey).trim().replace(Regex("\\s+"), " ").take(1000)
        val hint = ModelErrorMessages.forHttpStatus(status, detail)
        val endpointHint = when (providerId) {
            "custom" -> "（自定义端点，模型=$model）"
            "minimax" -> "（模型=$model）"
            else -> ""
        }
        return ProviderException(kind, "API error $status: $hint$endpointHint")
    }

    fun network(cause: Throwable): ProviderException {
        val detail = cause.message?.trim().orEmpty()
        val text = if (detail.isEmpty()) "网络连接失败：无法连接到模型服务" else "网络连接失败：$detail"
        return ProviderException(ProviderErrorKind.Network, text, cause)
    }

    fun empty(model: String): ProviderException =
        ProviderException(ProviderErrorKind.EmptyResponse, "模型 $model 返回空内容，请检查 Key/模型名，或稍后重试")

    fun config(reason: String): ProviderException =
        ProviderException(ProviderErrorKind.Config, reason)

    fun timeout(surface: String, stage: String, budgetMs: Long, elapsedMs: Long): ChatStageTimeoutException =
        ChatStageTimeoutException(surface = surface, stage = stage, budgetMs = budgetMs, elapsedMs = elapsedMs)
}

// ---------------------------------------------------------------------------
// SSE 解析（纯逻辑，iosTest 可直接测）
// ---------------------------------------------------------------------------

internal sealed interface SseEvent {
    data class Content(val text: String) : SseEvent
    data object Done : SseEvent
    data class Failure(val message: String) : SseEvent
    data object Ignore : SseEvent
}

/**
 * 按字节累积、按 `\n` 切行。**UTF-8 分块安全**：多字节字符可能被拆到两个 TCP 分片里，
 * 这里只在收满一行后才 `decodeToString()`，因此不会出现半个汉字。
 */
internal class SseLineDecoder {
    private var buffer = ByteArray(2048)
    private var size = 0

    /** 喂入一段字节，返回本次新增的完整行（已去掉行尾 `\r`）。 */
    fun feed(bytes: ByteArray, count: Int): List<String> {
        if (count <= 0) return emptyList()
        ensureCapacity(size + count)
        bytes.copyInto(buffer, size, 0, count)
        size += count
        val lines = mutableListOf<String>()
        var start = 0
        for (i in 0 until size) {
            if (buffer[i] == '\n'.code.toByte()) {
                var end = i
                if (end > start && buffer[end - 1] == '\r'.code.toByte()) end--
                lines += buffer.decodeToString(start, end)
                start = i + 1
            }
        }
        if (start > 0) {
            size -= start
            if (size > 0) buffer.copyInto(buffer, 0, start, start + size)
        }
        return lines
    }

    /** EOF 时把残留字节当最后一行返回（无换行结尾的响应）。 */
    fun flush(): String? {
        if (size == 0) return null
        val line = buffer.decodeToString(0, size)
        size = 0
        return line
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var next = buffer.size
        while (next < required) next *= 2
        buffer = buffer.copyOf(next)
    }
}

internal object SseEventParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseLine(line: String): SseEvent {
        val trimmed = line.trimEnd('\r', ' ')
        if (trimmed.isEmpty()) return SseEvent.Ignore
        if (trimmed.startsWith(":")) return SseEvent.Ignore // keep-alive 注释
        if (!trimmed.startsWith("data:")) return SseEvent.Ignore // event: / id: / retry:
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty()) return SseEvent.Ignore
        if (payload == "[DONE]") return SseEvent.Done

        val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return SseEvent.Ignore // 非 JSON 的 data 行忽略（部分厂商发心跳）

        // 流内错误事件：{"error":{"message":"..."}} 或 {"error":"..."}
        val errorEl = obj["error"]
        if (errorEl != null) {
            val message = when (errorEl) {
                is JsonObject -> errorEl["message"]?.jsonPrimitive?.contentOrNullSafe()
                    ?: errorEl["type"]?.jsonPrimitive?.contentOrNullSafe()
                is JsonPrimitive -> errorEl.contentOrNullSafe()
                else -> null
            }
            if (!message.isNullOrBlank()) return SseEvent.Failure(message)
        }

        val content = extractContent(obj)
        return if (content.isNullOrEmpty()) SseEvent.Ignore else SseEvent.Content(content)
    }

    /** choices[0].delta.content（流式）→ choices[0].message.content（非流式 JSON）→ choices[0].text（legacy）。 */
    fun extractContent(obj: JsonObject): String? {
        val choices = obj["choices"] as? JsonArray ?: return null
        val first = choices.firstOrNull() as? JsonObject ?: return null
        val delta = first["delta"] as? JsonObject
        val deltaContent = delta?.get("content")?.jsonPrimitive?.contentOrNullSafe()
        if (!deltaContent.isNullOrEmpty()) return deltaContent
        val message = first["message"] as? JsonObject
        val messageContent = message?.get("content")?.jsonPrimitive?.contentOrNullSafe()
        if (!messageContent.isNullOrEmpty()) return messageContent
        val text = first["text"]?.jsonPrimitive?.contentOrNullSafe()
        if (!text.isNullOrEmpty()) return text
        return null
    }

    fun extractFirstContent(rawJson: String): String? =
        runCatching { extractContent(json.parseToJsonElement(rawJson).jsonObject) }.getOrNull()

    fun extractErrorMessage(rawJson: String): String? = runCatching {
        val obj = json.parseToJsonElement(rawJson).jsonObject
        val err = obj["error"] ?: return@runCatching null
        when (err) {
            is JsonObject -> err["message"]?.jsonPrimitive?.contentOrNullSafe()
            is JsonPrimitive -> err.contentOrNullSafe()
            else -> null
        }
    }.getOrNull()

    /** `JsonPrimitive.content` 在显式 null 时会抛错，这里安全取值。 */
    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (this is JsonNull) null else content
}

/**
 * 流式读取器：按需返回下一段非空 content；`finished` 表示 `[DONE]`/EOF；
 * `errorMessage` 非空表示服务端在流内报错。
 */
internal class SseStreamReader(private val channel: ByteReadChannel) {
    private val decoder = SseLineDecoder()
    private val events = ArrayDeque<SseEvent>()
    private val readBuffer = ByteArray(8192)
    private var eof = false

    var finished = false
        private set
    var errorMessage: String? = null
        private set
    var sawDoneMarker = false
        private set

    suspend fun nextContent(): String? {
        while (true) {
            while (events.isNotEmpty()) {
                when (val event = events.removeFirst()) {
                    is SseEvent.Content -> return event.text
                    SseEvent.Done -> {
                        sawDoneMarker = true
                        finished = true
                        return null
                    }
                    is SseEvent.Failure -> {
                        errorMessage = event.message
                        finished = true
                        return null
                    }
                    SseEvent.Ignore -> Unit
                }
            }
            if (finished || eof) return null
            val read = channel.readAvailable(readBuffer)
            if (read == -1) {
                drainEofTail()
                eof = true
                finished = true
                return null
            }
            if (read > 0) {
                events.addAll(decoder.feed(readBuffer, read).map { SseEventParser.parseLine(it) })
                continue
            }
            if (!channel.awaitContent()) {
                drainEofTail()
                eof = true
                finished = true
                return null
            }
        }
    }

    private fun drainEofTail() {
        val tail = decoder.flush() ?: return
        if (tail.isNotBlank()) events.addLast(SseEventParser.parseLine(tail))
    }
}

// ---------------------------------------------------------------------------
// 配置解析（SettingsRepository + Keychain）
// ---------------------------------------------------------------------------

/**
 * 读取「当前供应商」运行时配置。
 *
 * 密钥优先级：Keychain(`provider_api_key_<id>`) > 旧的 SettingsRepository.apiKey 明文
 * （只作迁移兜底；保存时会把明文清掉）。Keychain 在 standalone 测试运行器不可用时
 * （errSecNotAvailable，T9 实测 -25291）`get` 返回 null，不会抛异常，自动退化到 settings 明文。
 */
internal class ProviderConfigResolver(
    private val settings: SettingsRepository,
    private val keyStore: ProviderKeyStore = ProviderKeyStore(),
) {
    fun apiKeyFor(providerId: String): String {
        val account = accountFor(providerId)
        val stored = runCatching { keyStore.get(account)?.trim().orEmpty() }.getOrDefault("")
        if (stored.isNotEmpty()) return stored
        return settings.apiKey.trim()
    }

    fun saveApiKey(providerId: String, apiKey: String): Boolean {
        val trimmed = apiKey.trim()
        val result = if (trimmed.isEmpty()) {
            keyStore.delete(accountFor(providerId))
        } else {
            keyStore.set(accountFor(providerId), trimmed)
        }
        // 密钥只进 Keychain/安全容器：清掉 NSUserDefaults 里的明文（若存在）
        if (settings.apiKey.isNotEmpty()) settings.apiKey = ""
        return result
    }

    /** Keychain/回退容器诊断（只含状态码与后端，不含密钥），用于自检与真机确认。 */
    fun keyStoreDiagnostics(): String = keyStore.diagnostics()

    fun resolve(): ProviderRuntimeConfig {
        val providerId = settings.provider.ifBlank { "deepseek" }
        val model = settings.modelName
        val baseUrl = IosProviderCatalog.endpoint(providerId, model, settings.customUrl)
        return ProviderRuntimeConfig(
            providerId = providerId,
            providerName = IosProviderCatalog.displayName(providerId),
            model = model,
            baseUrl = baseUrl,
            apiKey = apiKeyFor(providerId),
            temperature = settings.aiTemperature,
            thinkingEnabled = settings.deepseekThinkingEnabled,
        )
    }

    /** 用「未保存的编辑态」构造配置（连接测试用，不写设置）。 */
    fun draft(
        providerId: String,
        model: String,
        baseUrl: String,
        apiKey: String,
        temperature: Double,
    ): ProviderRuntimeConfig = ProviderRuntimeConfig(
        providerId = providerId,
        providerName = IosProviderCatalog.displayName(providerId),
        model = model,
        baseUrl = if (providerId == "custom") IosProviderCatalog.normalizeCustomUrl(baseUrl)
        else IosProviderCatalog.endpoint(providerId, model, ""),
        apiKey = apiKey.trim(),
        temperature = temperature.coerceIn(0.0, 2.0),
        thinkingEnabled = settings.deepseekThinkingEnabled,
    )

    private fun accountFor(providerId: String): String = "provider_api_key_$providerId"
}

// ---------------------------------------------------------------------------
// HTTP 流式实现
// ---------------------------------------------------------------------------

/**
 * 单次流式请求的纯实现：给定配置 + 消息，消费 SSE/JSON 并返回整包文本。
 * 与「配置从哪来」解耦，供真实客户端与连接测试共用。
 */
/**
 * 单次流式请求的实现：执行 POST 并逐段读取 SSE，返回整包文本。
 *
 * **超时/取消不在本类内用 `withTimeout` 直接包 body 读取**：K/N + Ktor Darwin 3.0.3 实测，
 * 直接在读挂起点上取消时 body channel 不会及时恢复（dribble 场景会一直挂住）；因此超时统一由
 * [ProviderRequestRunner] 用「独立请求 Job + 看门狗取消整个请求」实现。
 */
internal class ProviderHttpStreamer(
    private val client: HttpClient,
    private val surface: String,
) {
    var lastError: String = ""
        private set

    internal fun recordError(error: Throwable) {
        lastError = error.message.orEmpty()
    }

    suspend fun stream(
        config: ProviderRuntimeConfig,
        messages: List<AiMessage>,
        stage: String,
        onFirstContent: () -> Unit = {},
    ): String {
        lastError = ""
        if (!config.isConfigured) {
            throw fail(ProviderException(ProviderErrorKind.Config, config.missingReason))
        }
        val startedAt = TimeSource.Monotonic.markNow()
        providerLog("stream start provider=${config.providerId} model=${config.model} stage=$stage")

        val body = ChatCompletionRequest(
            model = config.model,
            messages = messages,
            stream = true,
            temperature = config.temperature,
            thinking = ModelCapabilities.thinkingParameter(
                config.providerId,
                config.model,
                config.thinkingEnabled,
            ),
            enable_thinking = ModelCapabilities.enableThinkingParameter(config.providerId, config.model),
            reasoning_effort = ModelCapabilities.reasoningEffort(config.providerId, config.model),
        )

        // 必须用 `preparePost(...).execute { }`（流式语句）而不是 eager `client.post(...)`：
        // T37 诊断实测（ProviderDarwinBehaviorTest）：eager post 在 Ktor Darwin 下要等响应体
        // 结束/连接关闭才返回 HttpResponse（dribble 场景 119.6s 才返回），流式 SSE 会退化为整包，
        // 首 token 超时也会误判；`execute` 形式与 T9 已验证的 prepareGet 行为一致（首字节即时）。
        return try {
            client.preparePost(config.baseUrl) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, "text/event-stream")
                if (IosProviderCatalog.usesApiKeyHeader(config.providerId)) {
                    header("api-key", config.apiKey)
                } else {
                    header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                }
                setBody(body)
            }.execute { response ->
                handleResponse(config, response, stage, { startedAt.elapsedNow().inWholeMilliseconds }, onFirstContent)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (error is ProviderException || error is ChatStageTimeoutException) throw error
            throw fail(ProviderErrorMapper.network(error))
        }
    }

    private suspend fun handleResponse(
        config: ProviderRuntimeConfig,
        response: HttpResponse,
        stage: String,
        elapsedMs: () -> Long,
        onFirstContent: () -> Unit,
    ): String {
        providerLog("stream headers received elapsed=${elapsedMs()}ms")

        if (!response.status.isSuccess()) {
            val detail = runCatching { response.bodyAsText() }.getOrDefault("")
            throw fail(
                ProviderErrorMapper.forHttpStatus(
                    status = response.status.value,
                    rawBody = detail,
                    apiKey = config.apiKey,
                    providerId = config.providerId,
                    model = config.model,
                ),
            )
        }

        // 非 SSE（部分自定义端点忽略 stream=true 直接返回 JSON）
        val contentType = response.headers[HttpHeaders.ContentType].orEmpty()
        if (!contentType.contains("text/event-stream", ignoreCase = true)) {
            val text = try {
                response.bodyAsText()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                throw fail(ProviderErrorMapper.network(error))
            }
            val content = SseEventParser.extractFirstContent(text)
            if (content.isNullOrBlank()) {
                val serverError = SseEventParser.extractErrorMessage(text)
                throw fail(
                    if (serverError != null) ProviderException(ProviderErrorKind.Other, "模型服务返回错误：$serverError")
                    else ProviderErrorMapper.empty(config.model),
                )
            }
            onFirstContent()
            lastError = ""
            return content
        }

        val reader = SseStreamReader(response.bodyAsChannel())
        val content = StringBuilder()
        var firstSeen = false
        while (true) {
            val next = reader.nextContent() ?: break
            if (!firstSeen) {
                firstSeen = true
                providerLog("stream first content elapsed=${elapsedMs()}ms")
                onFirstContent()
            }
            content.append(next)
        }
        reader.errorMessage?.let { failure ->
            throw fail(ProviderException(ProviderErrorKind.Other, "模型服务返回错误：$failure"))
        }
        val text = content.toString()
        providerLog(
            "stream done elapsed=${elapsedMs()}ms chars=${text.length} " +
                "done=${reader.sawDoneMarker}",
        )
        if (text.isBlank()) throw fail(ProviderErrorMapper.empty(config.model))
        lastError = ""
        return text
    }

    private fun fail(error: Throwable): Throwable {
        lastError = error.message.orEmpty()
        return error
    }
}

/**
 * 请求执行器：把「首 token 超时」与「整轮超时」实现为**看门狗**。
 *
 * 为什么不用 `withTimeout { stream(...) }`：K/N + Ktor Darwin 下取消 body 读取挂起点不会让读取恢复，
 * 会造成整轮永久挂起（T37 第一/二轮模拟器实测：dribble 场景 51s 不返回）。这里：
 * - 流式读取跑在独立的请求 Job 里（不进当前协程的父子链，避免父协程等待未完成的读）；
 * - 调用方只 `withTimeoutOrNull { firstToken.await() }` / `{ work.await() }`（await 是可取消挂起点，必达）；
 * - 超时/取消时 `requestJob.cancel()` 取消整个 HTTP 请求（取消会关闭底层 NSURLSession 任务）。
 * - 取消信号（引擎 cancel / 上层取消）经 catch CancellationException 传播，并取消请求 Job。
 */
internal class ProviderRequestRunner(private val streamer: ProviderHttpStreamer) {
    private val surface: String get() = "provider"

    suspend fun execute(
        config: ProviderRuntimeConfig,
        messages: List<AiMessage>,
        stage: String,
        timeouts: ProviderTimeouts,
        surfaceName: String = surface,
    ): String {
        val requestJob = Job()
        val scope = CoroutineScope(requestJob + Dispatchers.Default)
        val firstContent = CompletableDeferred<Unit>()
        val startedAt = TimeSource.Monotonic.markNow()
        val work = scope.async {
            try {
                streamer.stream(config, messages, stage) {
                    if (!firstContent.isCompleted) firstContent.complete(Unit)
                }
            } catch (error: Throwable) {
                if (!firstContent.isCompleted) firstContent.completeExceptionally(error)
                throw error
            }
        }
        try {
            val firstOk = withTimeoutOrNull(timeouts.firstTokenMs) { firstContent.await() }
            if (firstOk == null) {
                requestJob.cancel()
                val timeout = ProviderErrorMapper.timeout(
                    surface = surfaceName,
                    stage = stage,
                    budgetMs = timeouts.firstTokenMs,
                    elapsedMs = startedAt.elapsedNow().inWholeMilliseconds,
                )
                streamer.recordError(timeout)
                throw timeout
            }
            val remaining = (timeouts.totalMs - startedAt.elapsedNow().inWholeMilliseconds).coerceAtLeast(500L)
            val result = withTimeoutOrNull(remaining) { work.await() }
            if (result == null) {
                requestJob.cancel()
                val timeout = ProviderErrorMapper.timeout(
                    surface = surfaceName,
                    stage = stage,
                    budgetMs = timeouts.totalMs,
                    elapsedMs = startedAt.elapsedNow().inWholeMilliseconds,
                )
                streamer.recordError(timeout)
                throw timeout
            }
            return result
        } catch (cancellation: CancellationException) {
            requestJob.cancel()
            throw cancellation
        }
    }
}

/**
 * 真实流式客户端（引擎注入用）。
 *
 * @param resolver 每次 `complete` 都重新解析配置 → **设置保存后下一次发送立即生效**。
 * @param client 可注入（测试/连接测试）；默认 `createHttpClient()`（iOS 上即 Darwin 引擎，传输层 240s 上限）。
 * @param fixedConfig 非空时忽略 resolver（连接测试用）。
 * @param timeoutsOverride 非空时覆盖按预算推导的超时。
 */
internal class RealProviderStreamingClient(
    private val resolver: ProviderConfigResolver,
    private val client: HttpClient = createHttpClient(),
    private val fixedConfig: ProviderRuntimeConfig? = null,
    private val timeoutsOverride: ProviderTimeouts? = null,
    private val surface: String = "private",
) : StreamingClient {
    private val streamer = ProviderHttpStreamer(client, surface)
    private val runner = ProviderRequestRunner(streamer)

    /** 最近一次失败的可操作错误文案（桥接层用于覆盖引擎的通用兜底文案）。 */
    val lastError: String get() = streamer.lastError

    override suspend fun complete(messages: List<AiMessage>, stage: String): String {
        val config = fixedConfig ?: resolver.resolve()
        val timeouts = timeoutsOverride ?: ProviderTimeouts.forConfig(config)
        return runner.execute(config, messages, stage, timeouts, surface)
    }
}

internal class ProviderTestOutcome(
    val ok: Boolean,
    val providerId: String,
    val providerName: String,
    val model: String,
    val baseUrl: String,
    val latencyMs: Long,
    val message: String,
    val replyPreview: String,
)

/** 最小连接测试：发一条 1 token 级请求，返回可用性/延迟/回复预览。 */
internal class ProviderConnectionTester(
    private val client: HttpClient = createHttpClient(),
) {
    suspend fun test(
        config: ProviderRuntimeConfig,
        timeouts: ProviderTimeouts = ProviderTimeouts(firstTokenMs = 20_000L, totalMs = 45_000L),
    ): ProviderTestOutcome {
        if (!config.isConfigured) {
            return ProviderTestOutcome(
                ok = false,
                providerId = config.providerId,
                providerName = config.providerName,
                model = config.model,
                baseUrl = config.baseUrl,
                latencyMs = 0,
                message = config.missingReason,
                replyPreview = "",
            )
        }
        val probeMessages = listOf(
            AiMessage(role = "system", content = "你是连接测试助手。只回复两个字：可用"),
            AiMessage(role = "user", content = "ping"),
        )
        val streamer = ProviderHttpStreamer(client, "provider_test")
        val runner = ProviderRequestRunner(streamer)
        val startedAt = TimeSource.Monotonic.markNow()
        return try {
            val text = runner.execute(
                config = config.copy(temperature = 0.0),
                messages = probeMessages,
                stage = "provider_test",
                timeouts = timeouts,
                surfaceName = "provider_test",
            )
            ProviderTestOutcome(
                ok = true,
                providerId = config.providerId,
                providerName = config.providerName,
                model = config.model,
                baseUrl = config.baseUrl,
                latencyMs = startedAt.elapsedNow().inWholeMilliseconds,
                message = "连接成功",
                replyPreview = text.trim().replace(Regex("\\s+"), " ").take(80),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (timeout: ChatStageTimeoutException) {
            ProviderTestOutcome(
                ok = false,
                providerId = config.providerId,
                providerName = config.providerName,
                model = config.model,
                baseUrl = config.baseUrl,
                latencyMs = startedAt.elapsedNow().inWholeMilliseconds,
                message = "连接超时（预算 ${timeout.budgetMs}ms，已等待 ${timeout.elapsedMs}ms）",
                replyPreview = "",
            )
        } catch (error: Throwable) {
            ProviderTestOutcome(
                ok = false,
                providerId = config.providerId,
                providerName = config.providerName,
                model = config.model,
                baseUrl = config.baseUrl,
                latencyMs = startedAt.elapsedNow().inWholeMilliseconds,
                message = error.message ?: "连接失败",
                replyPreview = "",
            )
        }
    }
}
