@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.rhodes.privatechat.viewmodel

import com.rhodes.privatechat.MainActivity
import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.domain.chat.ChatClock
import com.rhodes.privatechat.shared.domain.chat.ChatEngine
import com.rhodes.privatechat.shared.domain.chat.ChatEngineConfig
import com.rhodes.privatechat.shared.domain.chat.ChatEngineSemantics
import com.rhodes.privatechat.shared.domain.chat.DefaultReplyCodec
import com.rhodes.privatechat.shared.domain.chat.EngineEventSink
import com.rhodes.privatechat.shared.domain.chat.PrivateContextProviders
import com.rhodes.privatechat.shared.domain.chat.PrivatePromptAssembler
import com.rhodes.privatechat.shared.domain.chat.PromptAssembler
import com.rhodes.privatechat.shared.domain.chat.RepositoryChatStore
import com.rhodes.privatechat.shared.domain.chat.StreamingClient
import com.rhodes.privatechat.shared.domain.chat.SystemChatClock
import com.rhodes.privatechat.shared.domain.chat.UserProfileView
import com.rhodes.privatechat.shared.model.AiMessage
import com.rhodes.privatechat.shared.modelgateway.VisionAnalyzeRequest
import com.rhodes.privatechat.shared.modelgateway.VisionGateway
import com.rhodes.privatechat.shared.modelgateway.createVisionGateway
import com.rhodes.privatechat.shared.modelgateway.hasVisionConfiguration
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.viewmodel.shared.SharedUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * T35 批 C 续作 步骤 1：ChatEngine 适配层工厂（**唯一**把 ChatViewModel 现有依赖映射为
 * ChatEngine 注入接口的地方；任何后续入口替换都必须经本工厂构造引擎，禁止各自 new ChatEngine）。
 *
 * 依赖映射表（oracle = Android 1.14.27 的 ChatViewModel 构造参数）：
 *
 * | ChatViewModel 依赖 | ChatEngine 注入 | 说明 |
 * |---|---|---|
 * | `repository: ChatRepository` | `RepositoryChatStore(repository)`（ChatStore） | 真实 DB 适配器；不 mock |
 * | `repository.messages.allocator` | `allocator: MessageIdAllocator` | **唯一实例**（T28 步骤 0a 契约；严禁第二实例） |
 * | `settings: SettingsRepository` | `settings` + `ChatEngineConfig.deepseekThinkingActive` | 预算/深度思考加成同源（oracle :107-140） |
 * | `buildApiMessages`（VM 私有，3500 行） | `PrivatePromptAssembler(store, settings, codec, clock, providers, profile)` | **shim 阶段**：可选上下文 providers 默认取共享层占位值（等价 oracle 占位），VM 的 memoryV2/知识库接入留给后续入口替换 |
 * | `sharedUtils.chatResult(...)`（含厂商/温度/思考/token 日志） | `SharedUtilsStreamingClient` | 保持 oracle 的请求参数与 DebugLogger 行为 |
 * | `sharedUtils.aiService.normalizeOfflineResponse/cleanJson` | `DefaultReplyCodec` | 输出编解码 |
 * | `isVisionConfigured()` / `currentVisionGateway()` | `visionConfigured` / `visionAnalyze` | 识图提示词逐字复刻 oracle :1902-1913 |
 * | `MainActivity.imageForModel(uri)` | `imageForModelResolver` | Android 专属；iOS 侧由宿主换实现 |
 * | `sharedUtils.chatConfigurationError()` | `chatConfigError` | 发送前配置守卫（oracle :1317-1322） |
 * | `viewModelScope` | `scope` | 引擎 fire-and-forget job 归宿主 scope 管理 |
 * | 引擎事件 | `EngineEventSink`（[ChatEngineEventMapper]） | 冻结映射表见 [ChatEngineHost] |
 *
 * 语义默认值：[ANDROID_BASELINE_SEMANTICS]（D1 取消不写 DB，与 oracle 基线一致）。
 * 新宿主（iOS）若要走引擎更完整的取消语义，显式传入 `ChatEngineSemantics()`。
 */
object ChatEngineFactory {

    /**
     * Android oracle 基线语义（T28 §3.2 实测）：`cancelSessionRequests` 后用户消息保持 `text`、
     * 租约保持 `running`（已取消协程中的 DB 写入被跳过）。
     */
    val ANDROID_BASELINE_SEMANTICS: ChatEngineSemantics = ChatEngineSemantics(cancelSkipsDbWrites = true)

    /** oracle :1902-1913 的识图提示词（逐字复刻，含 5 字段 JSON 契约）。 */
    const val VISION_ANALYZE_PROMPT: String = """请分析这张图片，用以下 JSON 格式回答（只输出 JSON，不要 Markdown 标记）：
{
  "visibleSummary": "一句话描述画面中最重要的可见内容",
  "userStateGuess": "基于画面的谨慎推测，不确定写 unknown",
  "notableObjects": ["物体1", "物体2"],
  "sceneQuality": "clear | dim | blurry | blocked | unknown",
  "confidence": 0.0~1.0
}
要求：只描述确定看到的内容，不确定的字段填 unknown，不要编造看不见的内容，输出中文。"""

    /**
     * 由 settings 构造引擎预算配置：`deepseekThinkingActive` 与 oracle 同口径
     * （provider == "deepseek" && deepseekThinkingEnabled），其余预算为 [ChatEngineConfig] 默认值
     * （= `ChatStageBudget` 常数，逐项对应 oracle :107-124）。
     */
    fun buildConfig(
        settings: SettingsRepository,
        base: ChatEngineConfig = ChatEngineConfig(),
        deepseekThinkingActive: Boolean = settings.provider == "deepseek" && settings.deepseekThinkingEnabled,
    ): ChatEngineConfig = base.copy().also { it.deepseekThinkingActive = deepseekThinkingActive }

    /**
     * 构造生产私聊引擎（Android 宿主）。所有参数都有生产默认值；测试可整体替换（假模型/短预算/事件记录）。
     *
     * 注意：`repository.messages.allocator` 是惰性属性，本方法不在构造期触发开库/分配（T28 步骤 0a 契约）。
     */
    fun createPrivateEngine(
        scope: CoroutineScope,
        repository: ChatRepository,
        settings: SettingsRepository,
        sharedUtils: SharedUtils,
        eventSink: EngineEventSink = EngineEventSink {},
        visionGateway: VisionGateway? = null,
        userProfileProvider: () -> UserProfileView = { UserProfileView() },
        providers: PrivateContextProviders = PrivateContextProviders(),
        assembler: PromptAssembler? = null,
        config: ChatEngineConfig = buildConfig(settings),
        semantics: ChatEngineSemantics = ANDROID_BASELINE_SEMANTICS,
        clock: ChatClock = SystemChatClock(),
        imageForModelResolver: (String) -> String? = { uri -> MainActivity.imageForModel(uri) },
        promptBuildDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    ): ChatEngine {
        val store = RepositoryChatStore(repository)
        val codec = DefaultReplyCodec()
        val vision = LazyVisionAnalyzer(settings, visionGateway)
        return ChatEngine(
            scope = scope,
            store = store,
            // 唯一 allocator（T28 步骤 0a）：与 MessageRepository.getNextMessageId 共享同一实例与水位。
            allocator = repository.messages.allocator,
            settings = settings,
            assembler = assembler ?: PrivatePromptAssembler(
                store = store,
                settings = settings,
                codec = codec,
                clock = clock,
                providers = providers,
                userProfileProvider = userProfileProvider,
                config = config,
            ),
            aiClient = SharedUtilsStreamingClient(sharedUtils),
            codec = codec,
            clock = clock,
            config = config,
            semantics = semantics,
            events = eventSink,
            visionConfigured = { settings.hasVisionConfiguration() },
            visionAnalyze = { imageForModel -> vision.analyze(imageForModel) },
            imageForModelResolver = imageForModelResolver,
            chatConfigError = { sharedUtils.chatConfigurationError() },
            promptBuildDispatcher = promptBuildDispatcher,
        )
    }

    /**
     * 只由本文件构造的识图适配器：显式 gateway 优先，否则按 settings 惰性创建（oracle currentVisionGateway :2014）。
     * 返回值裁剪/空值判定由引擎负责（oracle :1924-1927）。
     */
    private class LazyVisionAnalyzer(
        private val settings: SettingsRepository,
        private val gateway: VisionGateway?,
    ) {
        private val resolved: VisionGateway by lazy { gateway ?: createVisionGateway(settings) }

        suspend fun analyze(imageForModel: String): String? =
            resolved.analyzeImage(VisionAnalyzeRequest(imageForModel, VISION_ANALYZE_PROMPT)).text
    }
}

/**
 * `StreamingClient` 的 Android 生产适配器：走 [SharedUtils.chatResult]，保持 oracle 的
 * 厂商/温度/深度思考/token 日志/缓存统计行为（oracle generateCompletePrivateReply :1157-1175）。
 */
class SharedUtilsStreamingClient(
    private val sharedUtils: SharedUtils,
) : StreamingClient {
    override suspend fun complete(messages: List<AiMessage>, stage: String): String {
        // oracle :1163-1164：首次请求 tag=Chat，内容重试 tag=ChatContentRetry（DebugLogger 归类口径）。
        val logTag = if (stage == "model_primary_request") "Chat" else "ChatContentRetry"
        return sharedUtils.chatResult(messages, logTag).content
    }
}
