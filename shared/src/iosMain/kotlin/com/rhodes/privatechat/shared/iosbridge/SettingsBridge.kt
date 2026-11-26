package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.modelgateway.effectiveVisionConfiguration
import com.rhodes.privatechat.shared.network.IosProviderCatalog
import com.rhodes.privatechat.shared.network.ProviderKeyStore
import com.rhodes.privatechat.shared.prompt.PromptEditor
import com.rhodes.privatechat.shared.prompt.PromptPlaceholderRegistry
import com.rhodes.privatechat.shared.prompt.PromptResolver
import com.rhodes.privatechat.shared.prompt.PromptSyntaxValidator
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import platform.Foundation.NSUserDefaults

/**
 * 设置读写桥（真实 NSUserDefaults，经 `SettingsRepository`）。
 *
 * 最小面：String / Boolean / Int / Long 四种类型 + 演示用外观项（appearance_skin / dark_mode）。
 * 所有方法同步执行（NSUserDefaults 线程安全），可在 Swift 主线程直接调用。
 *
 * T46 追加（仅新增方法，已有签名不动）：
 *  - 聊天表现：温度 / 深度思考 / 历史回看轮数 / 台词与旁白单段字数上限（SettingsRepository 现有键）；
 *  - 提示词编辑器：模板/模块目录、当前文本（默认/自定义）、保存（含语法校验与警告）、恢复默认
 *    （`prompt_<type>_<mode>` / `prompt_<module>_<type>_<mode>` 键，T18 契约）；
 *  - 识图模型：读写 `effectiveVisionConfiguration` 对应的 vision_* 设置键，API Key 写 Keychain。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "SettingsBridge")
class SettingsBridge internal constructor(
    private val repo: SettingsRepository,
) {
    fun getString(key: String): String = repo.getString(key)
    fun putString(key: String, value: String) {
        repo.putString(key, value)
    }

    fun getBoolean(key: String): Boolean = repo.getBoolean(key)
    fun putBoolean(key: String, value: Boolean) {
        repo.putBoolean(key, value)
    }

    fun getInt(key: String): Int = repo.getInt(key)
    fun putInt(key: String, value: Int) {
        repo.putInt(key, value)
    }

    fun getLong(key: String): Long = repo.getLong(key)
    fun putLong(key: String, value: Long) {
        repo.putLong(key, value)
    }

    /** 外观: rhodes_night / rhodes_dawn …（真实 settings 键 appearance_skin）。 */
    fun appearanceSkin(): String = repo.appearanceSkin
    fun putAppearanceSkin(value: String) {
        repo.appearanceSkin = value
    }

    fun darkMode(): Boolean = repo.darkMode
    fun putDarkMode(value: Boolean) {
        repo.darkMode = value
    }

    /** 诊断快照（demo 展示"写入生效"用）：`dark=true;skin=rhodes_night;demo_key=…`。 */
    fun snapshot(): String =
        "dark=${repo.darkMode};skin=${repo.appearanceSkin};demo_note=${repo.getString(DEMO_NOTE_KEY)}"

    // ------------------------------------------------------------------
    // T46 聊天表现（SettingsRepository 现有键，保存即写，下一轮请求生效）
    // ------------------------------------------------------------------

    /**
     * 聊天表现快照。
     * 键映射（Android oracle `app/ui/chatsettings/ChatSettingsScreen.kt`）：
     *  - ai_temperature           `aiTemperature`（0.00–2.00，整数 ×100 存储，默认 0.70）
     *  - deepseek_thinking_enabled `deepseekThinkingEnabled`（默认 false）
     *  - history_messages         `historyMessages`（0–200，默认 15）
     *  - dia_max                  `diaMax`（台词单段最多字数，默认 300）
     *  - nar_max                  `narMax`（旁白单段最多字数，默认 300）
     */
    fun chatPerformance(): ChatPerformanceDto = ChatPerformanceDto(
        temperature = repo.aiTemperature,
        deepseekThinking = repo.deepseekThinkingEnabled,
        historyMessages = repo.historyMessages,
        dialogueMaxChars = repo.diaMax,
        narrationMaxChars = repo.narMax,
        contextMode = repo.contextMode,
        dialogueMinChars = repo.diaMin,
        narrationMinChars = repo.narMin,
        dialogueSegMin = repo.diaSegMin,
        dialogueSegMax = repo.diaSegMax,
        narrationSegMin = repo.narSegMin,
        narrationSegMax = repo.narSegMax,
        groupMsgMin = repo.groupMsgMin,
        groupMsgMax = repo.groupMsgMax,
        groupSpeechMin = repo.groupSpeechMin,
        groupSpeechMax = repo.groupSpeechMax,
        groupNarSegMin = repo.groupNarSegMin,
        groupNarSegMax = repo.groupNarSegMax,
        groupNarMin = repo.groupNarMin,
        groupNarMax = repo.groupNarMax,
        automaticContextWindow = repo.automaticContextWindow,
        maxContextTokens = repo.maxContextTokens,
    )

    /** 保存聊天表现（空串 = 成功）。值在下一轮请求重新读取 settings 时生效。 */
    fun saveChatPerformance(
        temperature: Double,
        deepseekThinking: Boolean,
        historyMessages: Int,
        dialogueMaxChars: Int,
        narrationMaxChars: Int,
        dialogueMinChars: Int,
        narrationMinChars: Int,
        dialogueSegMin: Int,
        dialogueSegMax: Int,
        narrationSegMin: Int,
        narrationSegMax: Int,
        groupMsgMin: Int,
        groupMsgMax: Int,
        groupSpeechMin: Int,
        groupSpeechMax: Int,
        groupNarSegMin: Int,
        groupNarSegMax: Int,
        groupNarMin: Int,
        groupNarMax: Int,
    ): String {
        repo.aiTemperature = temperature
        repo.deepseekThinkingEnabled = deepseekThinking
        repo.historyMessages = historyMessages
        // 段数先写最大值再写最小值，避免 repo 内部 coerce 时被旧值挤压
        repo.diaMax = dialogueMaxChars
        repo.diaMin = dialogueMinChars
        repo.diaSegMax = dialogueSegMax
        repo.diaSegMin = dialogueSegMin
        repo.narMax = narrationMaxChars
        repo.narMin = narrationMinChars
        repo.narSegMax = narrationSegMax
        repo.narSegMin = narrationSegMin
        // 群聊输出预算（先 max 后 min，避免 coerce 挤压）
        repo.groupMsgMax = groupMsgMax
        repo.groupMsgMin = groupMsgMin
        repo.groupSpeechMax = groupSpeechMax
        repo.groupSpeechMin = groupSpeechMin
        repo.groupNarSegMax = groupNarSegMax
        repo.groupNarSegMin = groupNarSegMin
        repo.groupNarMax = groupNarMax
        repo.groupNarMin = groupNarMin
        return ""
    }

    // ------------------------------------------------------------------
    // T46 提示词编辑器（T18 契约：PromptEditor / PromptResolver / 键 prompt_<type>_<mode>）
    // ------------------------------------------------------------------

    private val promptResolver: PromptResolver = object : PromptResolver {
        override fun resolvePromptTemplate(type: String, mode: String, defaultTemplate: String, defaultVersion: Int): String =
            repo.resolvePromptTemplate(type, mode, defaultTemplate, defaultVersion)

        override fun saveCustomPromptTemplate(type: String, mode: String, value: String, version: Int) =
            repo.saveCustomPromptTemplate(type, mode, value, version)

        override fun removePromptTemplate(type: String, mode: String) = repo.removePromptTemplate(type, mode)

        override fun isPromptTemplateCustom(type: String, mode: String): Boolean =
            repo.isPromptTemplateCustom(type, mode)

        override fun getPromptModule(module: String, type: String, mode: String): String =
            repo.getPromptModule(module, type, mode)

        override fun getCustomPromptModuleOrNull(module: String, type: String, mode: String): String? =
            repo.getCustomPromptModuleOrNull(module, type, mode)

        override fun savePromptModule(module: String, type: String, mode: String, value: String, version: Int) =
            repo.savePromptModule(module, type, mode, value, version)

        override fun removePromptModule(module: String, type: String, mode: String) =
            repo.removePromptModule(module, type, mode)

        override fun isPromptModuleCustom(module: String, type: String, mode: String): Boolean =
            repo.isPromptModuleCustom(module, type, mode)
    }

    /** 模板/模块目录（轻量：不含正文；正文用 [promptItem] 按需读取）。 */
    fun promptCatalog(): List<PromptEntryDto> = PROMPT_ENTRIES.map { spec ->
        val text = resolvedPromptText(spec)
        val custom = isPromptCustom(spec)
        PromptEntryDto(
            type = spec.type,
            mode = spec.mode,
            module = spec.module,
            displayName = spec.displayName,
            groupName = spec.groupName,
            isCustom = custom,
            isDisabled = custom && text.isBlank(),
        )
    }

    /** 单个模板/模块详情（当前文本 = 解析后的实际生效文本，含默认/自定义语义）。 */
    fun promptItem(type: String, mode: String, module: String): PromptItemDto {
        val spec = specFor(type, mode, module)
        val current = resolvedPromptText(spec)
        val default = defaultPromptText(type, mode, module)
        val custom = isPromptCustom(spec)
        val allowed = PromptPlaceholderRegistry.allowed(type, mode)
        val recommended = PromptPlaceholderRegistry.recommended(type, mode)
        val legacy = allowed.filter { it in PromptPlaceholderRegistry.legacyCompatibilityKeys }
        return PromptItemDto(
            type = type,
            mode = mode,
            module = module,
            displayName = spec.displayName,
            groupName = spec.groupName,
            currentText = current,
            defaultText = default,
            isCustom = custom,
            isDisabled = custom && current.isBlank(),
            isEmpty = current.isBlank(),
            version = if (module.isBlank()) repo.getPromptTemplateVersion(type, mode)
            else repo.getPromptModuleVersion(module, type, mode),
            placeholdersCsv = allowed.sorted().joinToString("\n"),
            recommendedCsv = recommended.sorted().joinToString("\n"),
            legacyCsv = legacy.sorted().joinToString("\n"),
        )
    }

    /**
     * 保存模板（module 为空）或模块（behavior/protocol/runtime）。
     * 空文本 = 有意禁用（T18 契约 15/16：保存成功且不回落默认）；语法错误返回 ok=false。
     */
    fun savePromptItem(type: String, mode: String, module: String, text: String): PromptSaveResultDto {
        val errors = if (module.isBlank()) PromptSyntaxValidator.validatePromptTemplate(type, mode, text)
        else PromptSyntaxValidator.validatePromptModule(text)
        if (errors.isNotEmpty()) {
            return PromptSaveResultDto(ok = false, warningsCsv = "", error = errors.joinToString("\n"))
        }
        val warnings = if (module.isBlank()) {
            PromptEditor.savePromptTemplate(promptResolver, type, mode, text)
        } else {
            PromptEditor.savePromptModule(promptResolver, module, type, mode, text)
        }
        return PromptSaveResultDto(ok = true, warningsCsv = warnings.joinToString("\n"), error = "")
    }

    /** 恢复默认：删值 + `_custom` + `_version` 三键（T18 契约 5）。 */
    fun resetPromptItem(type: String, mode: String, module: String) {
        if (module.isBlank()) PromptEditor.resetPromptTemplate(promptResolver, type, mode)
        else PromptEditor.resetPromptModule(promptResolver, module, type, mode)
    }

    /** 模板/模块当前解析文本（诊断用）。 */
    fun promptResolvedText(type: String, mode: String, module: String = ""): String =
        resolvedPromptText(specFor(type, mode, module))

    /** 是否自定义（诊断用）。 */
    fun isPromptCustom(type: String, mode: String, module: String = ""): Boolean =
        isPromptCustom(specFor(type, mode, module))

    // ------------------------------------------------------------------
    // T46 识图模型（effectiveVisionConfiguration 对应 vision_* 设置键 + Keychain）
    // ------------------------------------------------------------------

    /**
     * 识图配置状态。
     * [configured] 严格等于 commonMain `effectiveVisionConfiguration().configured`（聊天引擎真实识图分支判据）；
     * [engineConfigured] 额外考虑 Keychain 直读密钥（T37 聊天 Key 在 Keychain，settings 明文被清空）；
     * [keyConfigured] 仅表示已保存过识图 Key（含 Keychain）。
     */
    fun visionStatus(): VisionStatusDto {
        val effective = repo.effectiveVisionConfiguration()
        val chatProvider = repo.provider.ifBlank { "deepseek" }
        val chatKey = keyStore.get(providerAccount(chatProvider)).orEmpty()
        val storedVisionKey = keyStore.get(visionAccount(repo.visionProvider)).orEmpty()
        val engineKeyAvailable = effective.apiKey.isNotBlank() || storedVisionKey.isNotBlank() ||
            (effective.provider == chatProvider && chatKey.isNotBlank())
        val engineConfigured = effective.endpoint.isNotBlank() && effective.model.isNotBlank() && engineKeyAvailable
        val summary = when {
            effective.configured -> "真实识图：${IosProviderCatalog.displayName(effective.provider)} · ${effective.model}"
            engineConfigured -> "真实识图（Keychain 密钥）：${IosProviderCatalog.displayName(effective.provider)} · ${effective.model}"
            else -> "未配置识图模型（图片聊天使用本地识图桩/受限）"
        }
        return VisionStatusDto(
            mode = repo.visionMode,
            provider = repo.visionProvider,
            providerName = IosProviderCatalog.displayName(repo.visionProvider),
            endpoint = repo.visionBaseUrl,
            model = repo.visionModelName,
            keyConfigured = repo.visionApiKey.isNotBlank() || storedVisionKey.isNotBlank(),
            configured = effective.configured,
            engineConfigured = engineConfigured,
            effectiveProvider = effective.provider,
            effectiveEndpoint = effective.endpoint,
            effectiveModel = effective.model,
            keyStoreBackend = runCatching { keyStore.lastWriteBackend }.getOrDefault("none"),
            summary = summary,
        )
    }

    /**
     * 保存识图配置（空串 = 成功）。
     * 写 vision_mode=independent / vision_provider / vision_base_url / vision_model_name / vision_api_key；
     * API Key 写 Keychain（account=`vision_api_key_<provider>`）。
     *
     * 注意：commonMain `effectiveVisionConfiguration()` 只读 settings（`vision_api_key`），
     * 而实现该解析器的 ChatEngineBridge 在当前批次不可改（T45 并行），因此这里把 Keychain 密钥
     * **同时镜像**到 `vision_api_key`，保证 `configured=true` 且图片聊天走真实识图网关。
     * 镜像属于 iOS 已知差异（见 ios-port/handoffs/impl-settings-pages.md），后续由 T45 后扩展收口。
     */
    fun saveVisionConfiguration(provider: String, endpoint: String, model: String, apiKey: String): String {
        val trimmedProvider = provider.trim()
        val trimmedEndpoint = endpoint.trim()
        val trimmedModel = model.trim()
        val submittedKey = apiKey.trim()
        if (trimmedProvider.isEmpty()) return "请选择识图模型供应商"
        if (trimmedEndpoint.isEmpty()) return "识图端点不能为空"
        if (trimmedModel.isEmpty()) return "识图模型名不能为空"
        val account = visionAccount(trimmedProvider)
        val effectiveKey = if (submittedKey.isNotEmpty()) {
            if (!keyStore.set(account, submittedKey)) return "识图 API Key 写入 Keychain 失败"
            submittedKey
        } else {
            keyStore.get(account).orEmpty()
        }
        repo.visionMode = "independent"
        repo.visionProvider = trimmedProvider
        repo.visionBaseUrl = trimmedEndpoint
        repo.visionModelName = trimmedModel
        repo.visionApiKey = effectiveKey
        return ""
    }

    /** 清空识图配置（回到 auto：复用聊天供应商；同时删 Keychain 密钥与设置键）。 */
    fun clearVisionConfiguration(): Boolean {
        val previousProvider = repo.visionProvider
        val keychainCleared = keyStore.delete(visionAccount(previousProvider))
        repo.visionMode = "auto"
        repo.visionProvider = "ali"
        repo.visionBaseUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
        repo.visionModelName = "qwen3-vl-plus"
        repo.visionApiKey = ""
        return keychainCleared
    }

    /**
     * 把 NSUserDefaults 强制落盘（自检/证据用；生产路径不依赖：系统会在退出时同步）。
     * `synchronize()` 自 iOS 12 起已无必要，但对「立即读 plist 取证」仍是确定性做法。
     */
    fun flushDefaults(): Boolean = NSUserDefaults.standardUserDefaults.synchronize()

    private fun providerAccount(providerId: String): String = "provider_api_key_$providerId"
    private fun visionAccount(providerId: String): String = "vision_api_key_$providerId"

    /** Keychain 优先，OS 拒绝时回退沙盒私密文件（与 T37 ProviderKeyStore 语义一致）。 */
    private val keyStore = ProviderKeyStore()

    private fun specFor(type: String, mode: String, module: String): PromptEntrySpec =
        PROMPT_ENTRIES.firstOrNull { it.type == type && it.mode == mode && it.module == module }
            ?: PromptEntrySpec(
                type = type,
                mode = mode,
                module = module,
                groupName = groupNameFor(type),
                displayName = if (module.isBlank()) "自定义模板" else "自定义模块",
            )

    private fun isPromptCustom(spec: PromptEntrySpec): Boolean =
        if (spec.module.isBlank()) repo.isPromptTemplateCustom(spec.type, spec.mode)
        else repo.isPromptModuleCustom(spec.module, spec.type, spec.mode)

    private fun resolvedPromptText(spec: PromptEntrySpec): String =
        if (spec.module.isBlank()) PromptEditor.getPromptTemplate(promptResolver, spec.type, spec.mode)
        else PromptEditor.getPromptModule(promptResolver, spec.module, spec.type, spec.mode)

    private fun defaultPromptText(type: String, mode: String, module: String): String =
        if (module.isBlank()) PromptEditor.defaultTemplate(type, mode)
        else PromptEditor.defaultPromptModule(module, type, mode)

    companion object {
        /** 演示写入键（真实持久化到 NSUserDefaults，冷启动后仍在）。 */
        const val DEMO_NOTE_KEY: String = "ios_bridge_demo_note"
    }
}

// ---------------------------------------------------------------------------
// T46 DTO（只用简单类型；Swift 侧一一映射，不暴露 Kotlin 集合类型语义）
// ---------------------------------------------------------------------------

/** 聊天表现快照（与 `SettingsRepository` 现有键一一对应，见 [SettingsBridge.chatPerformance]）。 */
class ChatPerformanceDto(
    val temperature: Double,
    val deepseekThinking: Boolean,
    val historyMessages: Int,
    val dialogueMaxChars: Int,
    val narrationMaxChars: Int,
    val contextMode: String,
    val dialogueMinChars: Int,
    val narrationMinChars: Int,
    val dialogueSegMin: Int,
    val dialogueSegMax: Int,
    val narrationSegMin: Int,
    val narrationSegMax: Int,
    val groupMsgMin: Int,
    val groupMsgMax: Int,
    val groupSpeechMin: Int,
    val groupSpeechMax: Int,
    val groupNarSegMin: Int,
    val groupNarSegMax: Int,
    val groupNarMin: Int,
    val groupNarMax: Int,
    val automaticContextWindow: Boolean,
    val maxContextTokens: Int,
)

/** 提示词目录行（轻量，不含正文）。 */
class PromptEntryDto(
    val type: String,
    val mode: String,
    val module: String,
    val displayName: String,
    val groupName: String,
    val isCustom: Boolean,
    val isDisabled: Boolean,
)

/** 提示词详情：当前生效文本 + 默认文本 + 占位符清单。 */
class PromptItemDto(
    val type: String,
    val mode: String,
    val module: String,
    val displayName: String,
    val groupName: String,
    val currentText: String,
    val defaultText: String,
    val isCustom: Boolean,
    val isDisabled: Boolean,
    val isEmpty: Boolean,
    val version: Int,
    /** 当前场景允许的占位符（`\n` 分隔，不含花括号）。 */
    val placeholdersCsv: String,
    /** 出厂模板实际使用的推荐占位符（`\n` 分隔）。 */
    val recommendedCsv: String,
    /** 旧版兼容占位符（`\n` 分隔）。 */
    val legacyCsv: String,
)

/** 提示词保存结果：ok=false 时 [error] 为语法错误；ok=true 时 [warningsCsv] 为占位符/协议警告。 */
class PromptSaveResultDto(
    val ok: Boolean,
    val warningsCsv: String,
    val error: String,
)

/** 识图模型配置状态。 */
class VisionStatusDto(
    /** auto / independent（`vision_mode`）。 */
    val mode: String,
    val provider: String,
    val providerName: String,
    val endpoint: String,
    val model: String,
    val keyConfigured: Boolean,
    /** 严格等于 commonMain `effectiveVisionConfiguration().configured`。 */
    val configured: Boolean,
    /** 聊天引擎实际可用（含 Keychain 直读密钥的兜底判定）。 */
    val engineConfigured: Boolean,
    val effectiveProvider: String,
    val effectiveEndpoint: String,
    val effectiveModel: String,
    /** Keychain 写入后端诊断（keychain / file / none）。 */
    val keyStoreBackend: String,
    val summary: String,
)

// ---------------------------------------------------------------------------
// T46 提示词目录：与 T18 PromptTemplates / PromptModuleDefaults / Android PromptEditorScreen 对齐
// ---------------------------------------------------------------------------

private data class PromptEntrySpec(
    val type: String,
    val mode: String,
    val module: String,
    val groupName: String,
    val displayName: String,
)

private val PROMPT_ENTRIES: List<PromptEntrySpec> = buildList {
    val privateModes = listOf(
        Triple("online", "线上模式", "online"),
        Triple("offline", "线下模式", "offline"),
        Triple("director", "导演模式", "director"),
        Triple("proactive", "主动消息", "proactive"),
    )
    val groupModes = listOf(
        Triple("online", "线上模式", "online"),
        Triple("offline", "线下模式", "offline"),
        Triple("director", "导演模式", "director"),
        Triple("auto", "自动群聊", "auto"),
    )
    val moduleNames = listOf(
        "behavior" to "行为层",
        "protocol" to "输出协议",
        "runtime" to "运行时资料",
    )
    privateModes.forEach { (mode, label, _) ->
        add(PromptEntrySpec("private", mode, "", "私聊", "私聊 · $label"))
    }
    groupModes.forEach { (mode, label, _) ->
        add(PromptEntrySpec("group", mode, "", "群聊", "群聊 · $label"))
    }
    privateModes.forEach { (mode, label, _) ->
        moduleNames.forEach { (module, moduleLabel) ->
            add(PromptEntrySpec("private", mode, module, "私聊", "私聊 · $label · $moduleLabel"))
        }
    }
    groupModes.forEach { (mode, label, _) ->
        moduleNames.forEach { (module, moduleLabel) ->
            add(PromptEntrySpec("group", mode, module, "群聊", "群聊 · $label · $moduleLabel"))
        }
    }
    add(PromptEntrySpec("moment", "", "", "内容生成", "动态"))
    add(PromptEntrySpec("moment_comment", "", "", "内容生成", "动态评论"))
    add(PromptEntrySpec("diary", "", "", "内容生成", "代理人日记"))
    listOf(
        Triple("start", "派遣开局", "start"),
        Triple("progress", "派遣过程", "progress"),
        Triple("ending", "派遣结局", "ending"),
    ).forEach { (mode, label, _) ->
        add(PromptEntrySpec("dispatch", mode, "", "内容生成", "派遣 · $label"))
    }
}

private fun groupNameFor(type: String): String = when (type) {
    "private" -> "私聊"
    "group" -> "群聊"
    else -> "内容生成"
}
