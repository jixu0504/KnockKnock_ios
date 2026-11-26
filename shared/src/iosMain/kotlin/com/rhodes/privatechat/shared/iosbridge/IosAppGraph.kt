package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.data.ImpressionEvidenceRepository
import com.rhodes.privatechat.shared.data.ImpressionRepository
import com.rhodes.privatechat.shared.db.DatabaseLocations
import com.rhodes.privatechat.shared.db.DatabaseWrapper
import com.rhodes.privatechat.shared.domain.memory.MemoryDiagnostics
import com.rhodes.privatechat.shared.domain.memory.MemoryOperationSink
import com.rhodes.privatechat.shared.platform.IosDebugLogger
import com.rhodes.privatechat.shared.model.Memory
import com.rhodes.privatechat.shared.model.MemoryType
import com.rhodes.privatechat.shared.network.ProviderConfigResolver
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.shared.settings.createPlatformSettings
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.time.Clock
import platform.Foundation.NSLog
import platform.Foundation.NSProcessInfo

/**
 * iOS 应用图（窄门面）：SwiftUI 唯一需要接触的 Kotlin 入口。
 *
 * 五大桥接：
 * - [chatEngine]  私聊发送（真实的 domain ChatEngine + fake AI 通道 + 真实 DB 落库）
 * - [sessions]    会话列表（真实 DB）
 * - [settings]    设置读写（真实 NSUserDefaults/SettingsRepository）
 * - [backup]      导出 + 两阶段恢复（真实文件）
 * - [memory]      长期印象列表（真实 DB，只读）
 * - [history]     消息历史分页（真实 DB，只读；T34）
 * - [roles]       角色/关系网/开始聊天（真实 operators+sessions+relationships+memories；T36）
 * - [providers]   模型供应商配置/连接测试（11 家 + 自定义端点；T37）
 * - [groups]      群聊（群列表/群详情/成员/分页/真实 GroupChatEngine 发送/自动互动开关；T43）
 *
 * 生命周期：App 启动创建一份（`makeAppGraph()`），整个 App 共用；`close()` 释放协程。
 * DB 路径：Application Support/rhodes.db（复用 T9 DatabaseWrapper/DatabaseLocations 的
 * Application Support 语义，真机与模拟器一致）。
 *
 * 线程：回调一律在 Main 队列；DB/AI 在后台 Dispatchers.Default。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "RhodesAppGraph")
class IosAppGraph internal constructor() {
    private val dbPath: String = DatabaseLocations.defaultDatabasePath()

    /** 两阶段恢复：在第一次打开数据库之前换入 pending 备份（否则驱动会锁文件）。 */
    private val restoredFromPending: Boolean = BackupBridge.applyPendingRestoreIfAny(dbPath)

    private val dbWrapper: DatabaseWrapper = DatabaseWrapper()
    private val settingsRepo: SettingsRepository = SettingsRepository(createPlatformSettings())

    init {
        // 文件级恢复（冷启动换入备份）后，向量索引可能来自旧嵌入签名或与恢复的记忆不匹配：
        // 标记一次重建，由下次活跃的维护任务分批补齐（不阻塞启动）。
        if (restoredFromPending) {
            settingsRepo.memoryIndexRebuildPending = true
            NSLog("[RhodesLink][Memory] pending restore applied → index rebuild scheduled")
        }
        installMemoryDiagnostics()
    }

    /** T66：把记忆域诊断接回宿主持久日志（此前 MemoryDiagnostics 无 install，全部日志被丢弃）。 */
    private fun installMemoryDiagnostics() {
        MemoryDiagnostics.enabled = true
        MemoryDiagnostics.install { tag, message -> IosDebugLogger.log("INFO", "Mem/$tag", message) }
        val sequence = operationSequence()
        MemoryDiagnostics.installOperations(object : MemoryOperationSink {
            override fun beginOperation(surface: String, target: String, mode: String): String {
                val id = "mem-" + sequence()
                IosDebugLogger.log("INFO", "Mem/Op", "开始 id=$id surface=$surface target=$target mode=$mode")
                return id
            }

            override fun conversationStep(roundId: String, surface: String, stage: String, status: String, details: String) {
                IosDebugLogger.log("INFO", "Mem/Op", "步骤 id=$roundId stage=$stage status=$status $details")
            }
        })
    }

    /** 进程内单调序号：只用于日志关联 operation id，不做业务标识。 */
    private fun operationSequence(): () -> Int {
        var counter = 0
        return {
            counter += 1
            counter
        }
    }
    private val chatRepo: ChatRepository = ChatRepository(dbWrapper, settingsRepo)

    // T82：印象实体表读写（页面优先读 impressions，桥内做人工裁决与证据展开）。
    private val impressionRepository = ImpressionRepository(dbWrapper.database)
    private val impressionEvidenceRepository = ImpressionEvidenceRepository(dbWrapper.database)

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val sessionBridge = SessionListBridge(chatRepo, settingsRepo, workScope, mainScope)
    private val settingsBridge = SettingsBridge(settingsRepo)
    private val providerBridge = ProviderBridge(settingsRepo, mainScope, workScope)
    private val memoryBridge = MemoryBridge(chatRepo, settingsRepo, workScope, mainScope)
    private val historyBridge = MessageHistoryBridge(chatRepo, workScope, mainScope)
    private val roleBridge = RoleBridge(chatRepo, workScope, mainScope)
    // T49：角色人设编辑（新建/编辑/头像/提示词槽位/删除）。
    private val operatorBridge = OperatorBridge(chatRepo, settingsRepo, workScope, mainScope)
    // T57：关系网编辑（relationships 表单向语义 + oracle 互补关系映射）。
    private val relationBridge = RelationBridge(chatRepo, dbWrapper, dbPath, workScope, mainScope)
    // T57：用户身份（settings user_name/user_gender/user_signature + 真实统计）。
    private val identityBridge = IdentityBridge(chatRepo, dbWrapper, settingsRepo, dbPath, workScope, mainScope)
    private val backupBridge = BackupBridge(dbPath, workScope, mainScope)
    private val dataBackupBridge = DataBackupBridge(chatRepo, settingsRepo, workScope, mainScope)
    // T58：Markdown 聊天记录导入（解析预览 + 目标角色导入 + 自检）。
    private val markdownImportBridge = MarkdownChatImportBridge(chatRepo, settingsRepo, workScope, mainScope, dbPath)
    private val chatEngineBridge = ChatEngineBridge(
        chatRepo = chatRepo,
        dbWrapper = dbWrapper,
        settingsRepo = settingsRepo,
        workScope = workScope,
        mainScope = mainScope,
        sessionProvider = sessionBridge,
    )
    private val groupChatBridge = GroupChatBridge(
        chatRepo = chatRepo,
        dbWrapper = dbWrapper,
        settingsRepo = settingsRepo,
        workScope = workScope,
        mainScope = mainScope,
        sessionProvider = sessionBridge,
    )

    init {
        // T62：回复落库 → A7 上下文维护（滚动摘要/失败窗口重试）。lambda 内才触达 a7Glue，保持懒构建。
        chatEngineBridge.setReplyMaintenanceHook { sessionId -> a7Glue.onPrivateReplyStored(sessionId) }
        groupChatBridge.setReplyMaintenanceHook { groupId -> a7Glue.onGroupReplyStored(groupId) }
    }

    // T48：知识库（导入/切块/索引/召回测试）。服务在桥内按真实依赖组装，见 KnowledgeBaseBridge。
    private val knowledgeBridge = KnowledgeBaseBridge(
        chatRepo = chatRepo,
        dbWrapper = dbWrapper,
        settingsRepo = settingsRepo,
        workScope = workScope,
        mainScope = mainScope,
    )

    // T50：动态广场（真实 moments/moment_likes/moment_comments）。
    // T60：发布成功后回调 A7 层调度干员自动评论/点赞（hook 内部兜底，失败不影响发布）。
    private val momentsBridge = MomentsBridge(
        chatRepo = chatRepo,
        dbWrapper = dbWrapper,
        settingsRepo = settingsRepo,
        workScope = workScope,
        mainScope = mainScope,
        momentReplyHook = { momentId -> a7Glue.onUserMomentPublished(momentId) },
    )

    // T51：干员日记（真实 diaries + settings diary_read_at_*）。
    private val diaryBridge = DiaryBridge(
        chatRepo = chatRepo,
        settingsRepo = settingsRepo,
        workScope = workScope,
        mainScope = mainScope,
    )

    // T61：干员日记自动生成（真实 diaries + settings diary_auto_* + 真实模型/演示回退）。
    private val diaryAutoGlue = DiaryAutoGlue(
        chatRepo = chatRepo,
        settingsRepo = settingsRepo,
        workScope = workScope,
        mainScope = mainScope,
    )

    // T54：聊天排行榜/消费统计（真实 chat_messages + settings token 键 + 自检夹具）。
    private val rankingBridge = RankingBridge(
        chatRepo = chatRepo,
        settingsRepo = settingsRepo,
        workScope = workScope,
        mainScope = mainScope,
    )

    // T82：大家的印象（优先 impressions 实体表，空表回退 memory_items 旧视图 + 旧版 memories 兜底）。
    private val impressionsBridge = ImpressionsBridge(
        chatRepo = chatRepo,
        impressionRepo = impressionRepository,
        evidenceRepo = impressionEvidenceRepository,
        workScope = workScope,
        mainScope = mainScope,
    )

    // T56：A7 自动化接线（延迟任务/前台 tick/通知事件源）。懒构建：仅 Swift 调 a7() 或自检时创建。
    private val memoryWiring: A7MemoryWiring by lazy { A7MemoryWiring(chatRepo, dbWrapper, settingsRepo) }

    private val a7Glue: A7Glue by lazy {
        val wiring = memoryWiring
        A7Glue(
            chatRepo = chatRepo,
            dbWrapper = dbWrapper,
            settings = settingsRepo,
            workScope = workScope,
            mainScope = mainScope,
            groupChat = groupChatBridge,
            momentAi = A7MomentStreamingClient(ProviderConfigResolver(settingsRepo)),
            wiring = wiring,
            summaryGenerator = A7RealSummaryGenerator(chatRepo, settingsRepo, wiring.aiService),
            memoryIngestor = A7RealMemoryIngestor(wiring.memoryV2),
            memoryMaintenance = A7RealMemoryMaintenance(wiring.memoryV2),
            notificationSender = IosA7NotificationSender(),
        )
    }

    /** A7 自动化接线（T56）：start/setForeground/catchUpNow/状态/通知记录。 */
    fun a7(): A7Glue = a7Glue

    /**
     * T56：启动参数 `--a7-glue-selftest` → 自动跑 A7 接线自检（catch-up 执行 + DB 证据 + 通知记录 +
     * tick 补触发 + 通知过滤矩阵 + 层 KEEP/取消语义）。结果写 Application Support/t56-a7-glue-selftest.log。
     * 未带参数时零开销、零副作用。
     */
    private fun maybeRunA7GlueSelfTest() {
        val args = (NSProcessInfo.processInfo.arguments as List<*>).mapNotNull { it?.toString() }
        if (args.none { it.contains("--a7-glue-selftest") }) return
        NSLog("[RhodesLink][T56] --a7-glue-selftest detected, running A7 glue selftest")
        a7Glue.debugRunSelfTest { report ->
            NSLog("[RhodesLink][T56] selftest report written, lastLine=" + report.lineSequence().lastOrNull().orEmpty())
        }
    }

    init {
        maybeRunA7GlueSelfTest()
    }

    /**
     * T50：启动参数 `--moments-selftest` → 自动跑动态广场端到端自检（发布/列表/点赞/评论/删除/DB 核对），
     * 结果写 Application Support/t50-moments-selftest.log 并逐行 NSLog；
     * `--moments-fixture-clean` → 清理自检/截图夹具。未带参数时零开销、零副作用。
     */
    private fun maybeRunMomentsSelfTest() {
        val args = (NSProcessInfo.processInfo.arguments as List<*>).mapNotNull { it?.toString() }
        if (args.any { it.contains("--moments-fixture-clean") }) {
            NSLog("[RhodesLink][T50] --moments-fixture-clean detected")
            momentsBridge.debugCleanFixtures { removed, error ->
                NSLog("[RhodesLink][T50] fixture clean removed=" + removed + " error=" + (error ?: "-"))
            }
        }
        if (args.none { it.contains("--moments-selftest") }) return
        NSLog("[RhodesLink][T50] --moments-selftest detected, running moments selftest")
        momentsBridge.debugRunSelfTest { report ->
            NSLog("[RhodesLink][T50] selftest report written, lastLine=" + report.lineSequence().lastOrNull().orEmpty())
        }
    }

    init {
        maybeRunMomentsSelfTest()
    }

    /**
     * T61：启动参数 `--diary-auto-selftest` → 自动跑日记自动生成端到端自检
     * （mock 内容走真实提示词→规范化→DB→幂等→开关链路），结果写
     * Application Support/t61-diary-auto-selftest.log 并逐行 NSLog。
     * 未带参数时零开销、零副作用。
     */
    private fun maybeRunDiaryAutoSelfTest() {
        val args = (NSProcessInfo.processInfo.arguments as List<*>).mapNotNull { it?.toString() }
        if (args.none { it.contains("--diary-auto-selftest") }) return
        NSLog("[RhodesLink][T61] --diary-auto-selftest detected, running diary auto selftest")
        diaryAutoGlue.debugRunSelfTest { report ->
            NSLog("[RhodesLink][T61] selftest report written, lastLine=" + report.lineSequence().lastOrNull().orEmpty())
        }
    }

    init {
        maybeRunDiaryAutoSelfTest()
    }

    fun chatEngine(): ChatEngineBridge = chatEngineBridge

    /** 动态广场（T50）：列表分页/发布/点赞/评论/删除/未读/图片入库。 */
    fun moments(): MomentsBridge = momentsBridge

    /** 干员日记（T51）：列表/未读筛选/详情/标记已读/未读计数/自检夹具。 */
    fun diary(): DiaryBridge = diaryBridge

    /** 干员日记自动生成（T61）：状态/开关/立即生成/每日 tick/自检。 */
    fun diaryAuto(): DiaryAutoGlue = diaryAutoGlue

    /** 聊天排行榜/消费统计（T54）：消息数/互动天数/消费估算 + Token 今日/本周/全部 + 自检。 */
    fun ranking(): RankingBridge = rankingBridge

    /** 大家的印象（T82）：impressions 实体表优先（冻结/拒绝/证据），空表回退 memory_items 旧视图。 */
    fun impressions(): ImpressionsBridge = impressionsBridge

    /**
     * T45：启动参数 `--prompt-wiring-selftest` → 自动跑真实提示词/记忆/知识库接线自检。
     * 纯 Kotlin 侧启动钩子（不需要改 Swift）：结果写 Application Support/t45-prompt-wiring-selftest.log，
     * 并逐行 NSLog。未带该参数时零开销、零副作用。
     */
    private fun maybeRunPromptWiringSelfTest() {
        val args = (NSProcessInfo.processInfo.arguments as List<*>).mapNotNull { it?.toString() }
        if (args.none { it.contains("--prompt-wiring-selftest") }) return
        NSLog("[RhodesLink][T45] --prompt-wiring-selftest detected, running wiring selftest")
        chatEngineBridge.debugPromptWiringSelftest { report ->
            NSLog("[RhodesLink][T45] selftest report written, lastLine=" + report.lineSequence().lastOrNull().orEmpty())
        }
    }

    init {
        maybeRunPromptWiringSelfTest()
    }

    /**
     * T55：启动参数 `--mode-switch-selftest` → 聊天模式切换自检（DB mode 字段 + 三模式提示词断言）。
     * 纯 Kotlin 侧启动钩子；结果写 Application Support/t55-mode-switch-selftest.log 并逐行 NSLog。
     * 未带该参数时零开销、零副作用。
     */
    private fun maybeRunModeSwitchSelfTest() {
        val args = (NSProcessInfo.processInfo.arguments as List<*>).mapNotNull { it?.toString() }
        if (args.none { it.contains("--mode-switch-selftest") }) return
        NSLog("[RhodesLink][T55] --mode-switch-selftest detected, running mode switch selftest")
        chatEngineBridge.debugModeSwitchSelftest { report ->
            NSLog("[RhodesLink][T55] selftest report written, lastLine=" + report.lineSequence().lastOrNull().orEmpty())
        }
    }

    init {
        maybeRunModeSwitchSelfTest()
    }

    /** 群聊（T43）：群列表 / 群详情+成员 / 分页 / 真实 GroupChatEngine 发送 / 自动互动开关。 */
    fun groups(): GroupChatBridge = groupChatBridge

    /** 知识库（T48）：列表/创建/重命名/删除/文件导入/切块预览/索引/重试/失效/召回测试/关联干员。 */
    fun knowledge(): KnowledgeBaseBridge = knowledgeBridge

    fun sessions(): SessionListBridge = sessionBridge

    fun settings(): SettingsBridge = settingsBridge

    /** 模型供应商配置 / 连接测试（T37：真实模型接入设置页）。 */
    fun providers(): ProviderBridge = providerBridge

    fun backup(): BackupBridge = backupBridge

    /** 角色卡 / 聊天记录导入导出（T38：设置「数据与备份」页）。 */
    fun dataBackup(): DataBackupBridge = dataBackupBridge

    /** Markdown 聊天记录导入（T58）：解析 .md/.markdown/.txt → 预览 → 目标角色导入。 */
    fun markdownImport(): MarkdownChatImportBridge = markdownImportBridge

    fun memory(): MemoryBridge = memoryBridge

    /** 消息历史分页（真实 chat_messages，只读；T34）。 */
    fun history(): MessageHistoryBridge = historyBridge

    /** 角色 / 关系网 / 开始聊天（真实 operators + chat_sessions + relationships + memories；T36）。 */
    fun roles(): RoleBridge = roleBridge

    /** 角色人设编辑（T49：新建/编辑/内置头像/相册头像/提示词槽位/删除）。 */
    fun operators(): OperatorBridge = operatorBridge

    /** 关系网编辑（T57：列表/候选角色/24 类型目录/添加(含双向互补)/更新/删除/自检）。 */
    fun relations(): RelationBridge = relationBridge

    /** 用户身份（T57：昵称/性别/签名读写 + 消息/记忆/会话/角色/关系统计 + 自检）。 */
    fun identity(): IdentityBridge = identityBridge

    /**
     * T57：启动参数 `--relations-selftest` / `--identity-selftest` → 自动跑关系网 / 用户身份端到端自检；
     * `--relations-demo` → 写入可截图的关系演示夹具（note 前缀 `[T57演示]`）；
     * `--relations-fixture-clean` → 清理演示夹具。未带参数时零开销、零副作用。
     * 结果写 Application Support/t57-*-selftest.log 并逐行 NSLog。
     */
    private fun maybeRunT57Hooks() {
        val args = (NSProcessInfo.processInfo.arguments as List<*>).mapNotNull { it?.toString() }
        if (args.any { it.contains("--relations-fixture-clean") }) {
            NSLog("[RhodesLink][T57] --relations-fixture-clean detected")
            relationBridge.debugCleanDemonstrationFixtures { removed, error ->
                NSLog("[RhodesLink][T57] fixture clean removed=" + removed + " error=" + (error ?: "-"))
            }
        }
        if (args.any { it.contains("--relations-demo") }) {
            NSLog("[RhodesLink][T57] --relations-demo detected, seeding demonstration fixtures")
            relationBridge.debugSeedDemonstrationFixtures { summary, error ->
                NSLog("[RhodesLink][T57] demo fixtures summary=" + (summary ?: "-") + " error=" + (error ?: "-"))
            }
        }
        if (args.any { it.contains("--relations-selftest") }) {
            NSLog("[RhodesLink][T57] --relations-selftest detected, running relations selftest")
            relationBridge.debugRunSelfTest { report ->
                NSLog("[RhodesLink][T57] relations selftest report written, lastLine=" +
                    report.lineSequence().lastOrNull().orEmpty())
            }
        }
        if (args.any { it.contains("--identity-selftest") }) {
            NSLog("[RhodesLink][T57] --identity-selftest detected, running identity selftest")
            identityBridge.debugRunSelfTest { report ->
                NSLog("[RhodesLink][T57] identity selftest report written, lastLine=" +
                    report.lineSequence().lastOrNull().orEmpty())
            }
        }
    }

    init {
        maybeRunT57Hooks()
    }

    /**
     * T58：启动参数 `--markdown-import-selftest` → Markdown 聊天记录导入端到端自检
     * （Documents 写夹具 → 解析 → 导入真实 DB → sqlite 核对条数/顺序/内容/senderName →
     * 重复导入零新增 → 清理）。报告写 Application Support/t58-markdown-import-selftest.log。
     * 未带参数时零开销、零副作用。
     */
    private fun maybeRunMarkdownImportSelfTest() {
        val args = (NSProcessInfo.processInfo.arguments as List<*>).mapNotNull { it?.toString() }
        if (args.none { it.contains("--markdown-import-selftest") }) return
        NSLog("[RhodesLink][T58] --markdown-import-selftest detected, running markdown import selftest")
        markdownImportBridge.debugRunSelfTest { report ->
            NSLog("[RhodesLink][T58] selftest report written, lastLine=" +
                report.lineSequence().lastOrNull().orEmpty())
        }
    }

    init {
        maybeRunMarkdownImportSelfTest()
    }

    /**
     * T60：`--moment-reply-selftest`（发布 → 调度 → tick/catch-up 执行 → DB/UI 断言，跑完清理）/
     * `--moment-reply-demo`（同路径但保留截图夹具）/ `--moment-reply-fixture-clean`（清理夹具）。
     * 未带参数时零开销、零副作用。报告写 Application Support/t60-moment-reply-{selftest,demo}.log。
     */
    private fun maybeRunMomentReplyHooks() {
        val args = (NSProcessInfo.processInfo.arguments as List<*>).mapNotNull { it?.toString() }
        if (args.any { it.contains("--moment-reply-fixture-clean") }) {
            NSLog("[RhodesLink][T60] --moment-reply-fixture-clean detected")
            a7Glue.debugCleanMomentReplyFixtures { removed, error ->
                NSLog("[RhodesLink][T60] fixture clean removed=" + removed + " error=" + (error ?: "-"))
            }
        }
        val selfTest = args.any { it.contains("--moment-reply-selftest") }
        val demo = args.any { it.contains("--moment-reply-demo") }
        if (!selfTest && !demo) return
        NSLog("[RhodesLink][T60] selfTest=" + selfTest + " demo=" + demo)
        a7Glue.debugRunMomentReplySelfTest(momentsBridge, keepFixture = demo && !selfTest) { report ->
            NSLog("[RhodesLink][T60] selftest report written, lastLine=" + report.lineSequence().lastOrNull().orEmpty())
        }
    }

    init {
        maybeRunMomentReplyHooks()
    }

    fun databasePath(): String = dbPath

    /**
     * 首次启动播种演示数据（幂等）：预设角色 → 若无会话则为第一位角色建会话 →
     * 若无长期印象则写一条真实 L3 记忆。回调 (会话数, 错误)。
     */
    fun bootstrap(onDone: (Int, String) -> Unit) {
        workScope.launch {
            try {
                chatRepo.operators.ensurePresetOperators()
                chatRepo.initPresetGroups()
                var session = chatRepo.sessions.getAllSessionsSync().firstOrNull { !it.id.startsWith("group_") }
                if (session == null) {
                    val operators = chatRepo.operators.getAllOperatorsSync()
                    val operator = operators.firstOrNull()
                    if (operator != null) {
                        session = chatRepo.getOrCreateSession(operator.id, operator.name, operator.avatarUri)
                    }
                }
                if (session != null && chatRepo.memories.getAllLongTermImpressions().isEmpty()) {
                    chatRepo.memories.saveMemory(
                        Memory(
                            sessionId = session.id,
                            operatorId = session.operatorId,
                            type = MemoryType.LONG_TERM,
                            content = "演示记忆（真实 DB 写入）：绳匠与${session.operatorName}在新艾利都的街头第一次说话。",
                            createdAt = Clock.System.now().toEpochMilliseconds(),
                        ),
                    )
                }
                val count = chatRepo.sessions.getAllSessionsSync().size
                mainScope.launch { onDone(count, "") }
            } catch (t: Throwable) {
                mainScope.launch { onDone(0, t.message ?: "bootstrap 失败") }
            }
        }
    }

    /** 释放协程资源（demo 不调用；正式 App 在 App 生命周期结束时调用）。 */
    fun close() {
        runCatching { a7Glue.close() }
        mainScope.cancel()
        workScope.cancel()
    }
}

/**
 * Swift 侧入口：`IosAppGraphKt.makeAppGraph()`（Swift 名以生成的 Shared.h 为准）。
 * 返回单例式对象（每次调用新建独立 DB 连接，Swift 包装层只调用一次）。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "makeAppGraph")
fun makeAppGraph(): IosAppGraph = IosAppGraph()
