package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.network.IosProviderCatalog
import com.rhodes.privatechat.shared.network.ProviderConfigResolver
import com.rhodes.privatechat.shared.network.ProviderConnectionTester
import com.rhodes.privatechat.shared.network.ProviderKeyStore
import com.rhodes.privatechat.shared.network.ProviderRuntimeConfig
import com.rhodes.privatechat.shared.network.providerLog
import com.rhodes.privatechat.shared.network.ProviderTestOutcome
import com.rhodes.privatechat.shared.network.ProviderTimeouts
import com.rhodes.privatechat.shared.network.providers
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// DTO（只用简单类型，Swift 侧一一映射）
// ---------------------------------------------------------------------------

/**
 * 一家供应商的展示/状态行。
 *
 * - [modelsCsv]：可选模型名以 `\n` 分隔（Swift 侧 split）；自定义端点为空串（用户自填）。
 * - [configured]：该供应商当前是否已有可用 API Key（Keychain 优先）。
 * - [selected]：是否为当前 `provider` 设置。
 * - [requiresCustomUrl]：custom（其他 OpenAI 兼容）需要用户填 baseUrl。
 */
class ProviderDto(
    val id: String,
    val name: String,
    val modelsCsv: String,
    val configured: Boolean,
    val selected: Boolean,
    val requiresCustomUrl: Boolean,
    val endpointHint: String,
)

/** 当前编辑态回填（打开设置页/切换供应商时用）。 */
class ProviderSelectionDto(
    val providerId: String,
    val providerName: String,
    val model: String,
    val customUrl: String,
    val temperature: Double,
    val keyConfigured: Boolean,
    /** 掩码后的 Key（如 `••••abcd`），**绝不返回明文**。 */
    val keyPreview: String,
)

/** 聊天/设置页共用的供应商状态（真实模式 vs 演示模式）。 */
class ProviderStatusDto(
    /** "real" = 已配置真实供应商；"demo" = 未配置，聊天回退演示模式。 */
    val mode: String,
    val providerId: String,
    val providerName: String,
    val model: String,
    val endpoint: String,
    val keyConfigured: Boolean,
    /** demo 原因（real 时为空串）。 */
    val reason: String,
    /** 一行中文摘要（UI 直接显示）。 */
    val summary: String,
    val configured: Boolean,
)

/** 连接测试结果。 */
class ProviderTestResultDto(
    val ok: Boolean,
    val providerId: String,
    val providerName: String,
    val model: String,
    val baseUrl: String,
    val latencyMs: Long,
    val message: String,
    val replyPreview: String,
)

internal fun buildProviderStatus(resolver: ProviderConfigResolver): ProviderStatusDto {
    val config = resolver.resolve()
    val configured = config.isConfigured
    return ProviderStatusDto(
        mode = if (configured) "real" else "demo",
        providerId = config.providerId,
        providerName = config.providerName,
        model = config.model,
        endpoint = config.baseUrl,
        keyConfigured = config.apiKey.isNotEmpty(),
        reason = config.missingReason,
        summary = if (configured) {
            "真实模型：${config.providerName} · ${config.model}"
        } else {
            "演示模式（${config.missingReason}）"
        },
        configured = configured,
    )
}

internal fun buildSelection(resolver: ProviderConfigResolver, settings: SettingsRepository): ProviderSelectionDto {
    val config = resolver.resolve()
    val key = config.apiKey
    return ProviderSelectionDto(
        providerId = config.providerId,
        providerName = config.providerName,
        model = config.model,
        customUrl = settings.customUrl,
        temperature = config.temperature,
        keyConfigured = key.isNotEmpty(),
        keyPreview = maskKey(key),
    )
}

private fun maskKey(key: String): String = when {
    key.isEmpty() -> ""
    key.length <= 4 -> "••••"
    else -> "••••" + key.takeLast(4)
}

// ---------------------------------------------------------------------------
// 桥
// ---------------------------------------------------------------------------

/**
 * 模型供应商配置桥（T37）。
 *
 * - 11 家供应商列表 + 当前选择 + Key 状态；
 * - 保存配置：`provider / model_name / custom_url / ai_temperature` 写 SettingsRepository，
 *   **API Key 只写 Keychain**（`provider_api_key_<id>`），并清掉 settings 里的明文 key；
 * - 连接测试：发一条最小 OpenAI 兼容请求（首 token 20s / 整体 45s 预算），回调结果；
 * - 保存后无需重开 App：聊天客户端每次发送前重新解析配置（下一次发送即生效）。
 *
 * 线程：`list/selection/status/save/clearApiKey` 同步（NSUserDefaults/Keychain 轻量）；
 * `test` 在后台协程执行，回调在 Main 队列。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "ProviderBridge")
class ProviderBridge internal constructor(
    private val settingsRepo: SettingsRepository,
    private val mainScope: CoroutineScope,
    private val workScope: CoroutineScope,
) {
    private val keyStore = ProviderKeyStore()
    private val resolver = ProviderConfigResolver(settingsRepo, keyStore)
    private val tester = ProviderConnectionTester()

    /** 连接测试预算；自动化验证可调小（默认 20s/45s，生产不暴露 UI）。 */
    internal var probeTimeouts: ProviderTimeouts =
        ProviderTimeouts(firstTokenMs = 20_000L, totalMs = 45_000L)

    private var testJob: Job? = null

    fun list(): List<ProviderDto> = IosProviderCatalog.ids.map { id ->
        val config = providers[id]
        val current = settingsRepo.provider.ifBlank { "deepseek" }
        ProviderDto(
            id = id,
            name = IosProviderCatalog.displayName(id),
            modelsCsv = config?.models?.joinToString("\n").orEmpty(),
            configured = resolver.apiKeyFor(id).isNotEmpty(),
            selected = id == current,
            requiresCustomUrl = id == "custom",
            endpointHint = if (id == "custom") settingsRepo.customUrl
            else IosProviderCatalog.endpoint(id, config?.models?.firstOrNull().orEmpty(), ""),
        )
    }

    fun selection(): ProviderSelectionDto = buildSelection(resolver, settingsRepo)

    fun status(): ProviderStatusDto = buildProviderStatus(resolver)

    fun hasApiKey(providerId: String): Boolean = resolver.apiKeyFor(providerId).isNotEmpty()

    /** 密钥存储诊断（Keychain 是否可用 / 是否回退文件容器；不含密钥内容）。 */
    fun keyStoreDiagnostics(): String = resolver.keyStoreDiagnostics()

    /** 连接测试当前预算（诊断用；确认 overrideProbeTimeouts 生效）。 */
    fun probeTimeoutsSummary(): String =
        "firstTokenMs=${probeTimeouts.firstTokenMs} totalMs=${probeTimeouts.totalMs}"

    /**
     * 保存供应商配置。空 [apiKey] 表示「保留已有 Key」（要清除请调 [clearApiKey]）。
     * @return 空串 = 成功；非空 = 失败原因。
     */
    fun save(
        providerId: String,
        model: String,
        customUrl: String,
        apiKey: String,
        temperature: Double,
    ): String {
        if (providers[providerId] == null) return "未知供应商：$providerId"
        val trimmedModel = model.trim()
        if (trimmedModel.isEmpty()) return "模型名不能为空"
        val trimmedUrl = customUrl.trim()
        if (providerId == "custom" && trimmedUrl.isEmpty()) return "自定义端点需要填写 API 地址"
        val collectedApiKey = apiKey.trim()
        if (collectedApiKey.isNotEmpty() && !resolver.saveApiKey(providerId, collectedApiKey)) {
            return "API Key 写入 Keychain 失败，请重试"
        }
        settingsRepo.provider = providerId
        settingsRepo.modelName = trimmedModel
        if (providerId == "custom") settingsRepo.customUrl = trimmedUrl
        settingsRepo.aiTemperature = temperature.coerceIn(0.0, 2.0)
        return ""
    }

    fun clearApiKey(providerId: String): Boolean = resolver.saveApiKey(providerId, "")

    /**
     * 连接测试（异步，回调在 Main）。[apiKey] 为空时使用已保存的 Key。
     * @return 取消句柄（IosCancellable 语义由调用方包裹）。
     */
    fun test(
        providerId: String,
        model: String,
        customUrl: String,
        apiKey: String,
        temperature: Double,
        onDone: (ProviderTestResultDto) -> Unit,
    ): IosCancellable {
        testJob?.cancel()
        val config = buildDraftConfig(providerId, model, customUrl, apiKey, temperature)
        providerLog("test start provider=${config.providerId} model=${config.model} "
            + "first=${probeTimeouts.firstTokenMs} total=${probeTimeouts.totalMs} url=${config.baseUrl}")
        val job = workScope.launch {
            val startedAt = kotlin.time.TimeSource.Monotonic.markNow()
            val outcome = try {
                tester.test(config, probeTimeouts)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                ProviderTestOutcome(
                    ok = false,
                    providerId = config.providerId,
                    providerName = config.providerName,
                    model = config.model,
                    baseUrl = config.baseUrl,
                    latencyMs = 0,
                    message = error.message ?: "连接失败",
                    replyPreview = "",
                )
            }
            providerLog("test end provider=${config.providerId} ok=${outcome.ok} "
                + "elapsed=${startedAt.elapsedNow().inWholeMilliseconds}ms message=${outcome.message.take(120)}")
            mainScope.launch { runCatching { onDone(outcome.toDto()) } }
        }
        testJob = job
        return IosCancellable { job.cancel() }
    }

    fun cancelTest() {
        testJob?.cancel()
        testJob = null
    }

    /** 自动化/诊断用：缩小连接测试超时（毫秒）。 */
    fun overrideProbeTimeouts(firstTokenMs: Long, totalMs: Long) {
        providerLog("override probe timeouts requested first=$firstTokenMs total=$totalMs")
        probeTimeouts = ProviderTimeouts(
            firstTokenMs = firstTokenMs.coerceAtLeast(200L),
            totalMs = totalMs.coerceAtLeast(firstTokenMs.coerceAtLeast(200L)),
        )
    }

    private fun buildDraftConfig(
        providerId: String,
        model: String,
        customUrl: String,
        apiKey: String,
        temperature: Double,
    ): ProviderRuntimeConfig {
        val effectiveKey = apiKey.trim().ifEmpty { resolver.apiKeyFor(providerId) }
        return resolver.draft(
            providerId = providerId,
            model = model.trim(),
            baseUrl = customUrl.trim(),
            apiKey = effectiveKey,
            temperature = temperature,
        )
    }
}

private fun ProviderTestOutcome.toDto(): ProviderTestResultDto = ProviderTestResultDto(
    ok = ok,
    providerId = providerId,
    providerName = providerName,
    model = model,
    baseUrl = baseUrl,
    latencyMs = latencyMs,
    message = message,
    replyPreview = replyPreview,
)
