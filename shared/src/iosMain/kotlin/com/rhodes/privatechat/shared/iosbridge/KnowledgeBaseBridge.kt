package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.data.KnowledgeBaseRepository
import com.rhodes.privatechat.shared.db.DatabaseWrapper
import com.rhodes.privatechat.shared.domain.knowledge.KnowledgeBaseService
import com.rhodes.privatechat.shared.knowledge.KnowledgeBaseContextBuilder
import com.rhodes.privatechat.shared.knowledge.KnowledgeBaseImportService
import com.rhodes.privatechat.shared.knowledge.KnowledgeBaseIndexService
import com.rhodes.privatechat.shared.model.KnowledgeBase
import com.rhodes.privatechat.shared.model.KnowledgeBaseChunk
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.shared.vector.LocalVectorStoreGateway
import com.rhodes.privatechat.shared.vector.MemoryVectorService
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 知识库桥（T48，P1 知识库 UI）：向 SwiftUI 暴露 shared 知识库域门面
 * `shared.domain.knowledge.KnowledgeBaseService` 的导入/切块/索引/召回能力。
 *
 * 桥约定（与 MemoryBridge/GroupChatBridge 一致）：
 * - 全部回调在 Main 队列触发；DB / 向量化在 `Dispatchers.Default`（workScope）；
 * - DTO 只用简单类型 + List，不导出 Flow / sealed / Kotlin 集合别名；
 * - 不向上暴露 Kotlin 异常：错误经回调第二参数（中文文案）返回。
 *
 * 服务构造：`KnowledgeBaseService` 是 T24 的域门面（commonMain，零平台耦合）；
 * 这里按真实依赖（KnowledgeBaseRepository + MemoryVectorService(local) + SettingsRepository）
 * 组装一次，生命周期跟随 IosAppGraph。
 *
 * Oracle 语义（Android 1.14.27）：导入即切块入库；`vector_provider_mode=local` 时导入后
 * 自动后台索引（本地哈希嵌入，无网络/无付费）；远程模式需 UI 确认（planIndex.requiresUserConfirmation）。
 * 召回按「干员 ↔ 知识库」关联（operator_knowledge_bases）过滤，未关联时返回 no_assignment。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "KnowledgeBaseBridge")
class KnowledgeBaseBridge internal constructor(
    private val chatRepo: ChatRepository,
    dbWrapper: DatabaseWrapper,
    settingsRepo: SettingsRepository,
    private val workScope: CoroutineScope,
    private val mainScope: CoroutineScope,
) {
    private val repository: KnowledgeBaseRepository = chatRepo.knowledgeBases
    private val vectorService = MemoryVectorService(settingsRepo, LocalVectorStoreGateway(dbWrapper))
    private val indexService = KnowledgeBaseIndexService(repository, vectorService, settingsRepo)
    private val importService = KnowledgeBaseImportService(repository, indexService, settingsRepo)
    private val contextBuilder = KnowledgeBaseContextBuilder(repository, vectorService)
    private val service = KnowledgeBaseService(importService, indexService, contextBuilder)

    // ------------------------------------------------------------------ 列表 / 详情

    /** 全部知识库（名称 / 切块数 / 已索引数 / 失败数 / 索引状态 / 更新时间）。 */
    fun list(onResult: (List<KnowledgeBaseDto>, String?) -> Unit) {
        workScope.launch {
            try {
                val books = repository.getAll()
                val dtos = books.map { book ->
                    val chunks = repository.getChunks(book.id)
                    book.toDto(chunks, emptyList())
                }
                mainScope.launch { onResult(dtos, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(emptyList(), errorText(t, "读取知识库失败")) }
            }
        }
    }

    /**
     * 详情：知识库 + 前 [chunkLimit] 个切块预览（<=0 表示全部）+ 已关联干员 id。
     * 切块内容在 UI 侧截断展示，桥不做二次裁剪（预览条数由 chunkLimit 控制）。
     */
    fun detail(knowledgeBaseId: String, chunkLimit: Int, onResult: (KnowledgeBaseDetailDto?, String?) -> Unit) {
        workScope.launch {
            try {
                val book = repository.get(knowledgeBaseId) ?: error("知识库不存在或已删除")
                val allChunks = repository.getChunks(knowledgeBaseId)
                val limit = if (chunkLimit <= 0) allChunks.size else chunkLimit
                val assigned = repository.getAssignmentsForKnowledgeBase(knowledgeBaseId)
                    .filter { it.enabled }
                    .map { it.operatorId }
                val chunks = allChunks.take(limit).map { it.toDto() }
                val dto = KnowledgeBaseDetailDto(
                    book = book.toDto(allChunks, assigned),
                    chunks = chunks,
                    totalChunks = allChunks.size,
                    assignedOperatorIds = assigned,
                )
                mainScope.launch { onResult(dto, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, errorText(t, "读取知识库详情失败")) }
            }
        }
    }

    /** 索引状态查询（轮询用）：状态字符串 + 切块进度 + 计数。 */
    fun status(knowledgeBaseId: String, onResult: (KnowledgeBaseStatusDto?, String?) -> Unit) {
        workScope.launch {
            try {
                val book = repository.get(knowledgeBaseId) ?: error("知识库不存在或已删除")
                val chunks = repository.getChunks(knowledgeBaseId)
                val indexed = chunks.count { it.enabled && it.indexedAt > 0L && it.indexError.isBlank() }
                val failed = chunks.count { it.indexError.isNotBlank() }
                val dto = KnowledgeBaseStatusDto(
                    knowledgeBaseId = book.id,
                    indexStatus = book.indexStatus,
                    indexStatusText = indexStatusText(book.indexStatus),
                    chunkCount = chunks.size,
                    indexedChunkCount = indexed,
                    failedChunkCount = failed,
                    updatedAt = book.updatedAt,
                )
                mainScope.launch { onResult(dto, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, errorText(t, "读取索引状态失败")) }
            }
        }
    }

    // ------------------------------------------------------------------ 创建 / 重命名 / 删除

    /** 粘贴文本创建（切块同步入库；local 模式自动后台索引，语义同 Android）。 */
    fun createText(name: String, content: String, onResult: (KnowledgeBaseDto?, String?) -> Unit) {
        workScope.launch {
            try {
                val book = service.saveText(name, content)
                val chunks = repository.getChunks(book.id)
                val dto = book.toDto(chunks, emptyList())
                mainScope.launch { onResult(dto, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, errorText(t, "创建知识库失败")) }
            }
        }
    }

    /**
     * 文件导入（bytes 版本，核心实现）：支持 UTF-8 文本 / Markdown（TXT/MD），
     * 大小与切块上限由 `KnowledgeBaseTextProcessor` 校验（5MB / 60 万字 / 1800 段）。
     */
    fun importFile(fileName: String, bytes: ByteArray, onResult: (KnowledgeBaseDto?, String?) -> Unit) {
        workScope.launch {
            try {
                val book = service.importFile(fileName, bytes)
                val chunks = repository.getChunks(book.id)
                val dto = book.toDto(chunks, emptyList())
                mainScope.launch { onResult(dto, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, errorText(t, "导入知识库文件失败")) }
            }
        }
    }

    /**
     * 文件导入（路径版本，Swift 侧 DocumentPicker 用）：Swift 把安全作用域内的文件
     * 暂存到临时路径后传入，桥内部读 bytes 走同一 [importFile] 管线。
     */
    fun importFileFromPath(fileName: String, path: String, onResult: (KnowledgeBaseDto?, String?) -> Unit) {
        val bytes = IosFileSupport.readBytes(path)
        if (bytes == null || bytes.isEmpty()) {
            mainScope.launch { onResult(null, "文件不存在、不可读或为空：$path") }
            return
        }
        importFile(fileName, bytes, onResult)
    }

    /** 重命名（只改名称，不重切块、不使索引失效——对齐 Android renameKnowledgeBase）。 */
    fun rename(knowledgeBaseId: String, name: String, onResult: (KnowledgeBaseDto?, String?) -> Unit) {
        workScope.launch {
            try {
                require(name.trim().isNotBlank()) { "知识库名称不能为空" }
                repository.rename(knowledgeBaseId, name.trim())
                val book = repository.get(knowledgeBaseId) ?: error("知识库不存在或已删除")
                val chunks = repository.getChunks(knowledgeBaseId)
                val assigned = repository.getAssignmentsForKnowledgeBase(knowledgeBaseId)
                    .filter { it.enabled }.map { it.operatorId }
                val dto = book.toDto(chunks, assigned)
                mainScope.launch { onResult(dto, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, errorText(t, "重命名知识库失败")) }
            }
        }
    }

    /** 删除（连带切块 / 干员关联 / 向量分区，单事务；语义同 Android repository.delete）。 */
    fun delete(knowledgeBaseId: String, onResult: (String?) -> Unit) {
        workScope.launch {
            try {
                indexService.cancelAndRemove(knowledgeBaseId)
                repository.delete(knowledgeBaseId)
                mainScope.launch { onResult(null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(errorText(t, "删除知识库失败")) }
            }
        }
    }

    // ------------------------------------------------------------------ 索引

    /** 索引计划：切块数 / 是否远程嵌入（付费需确认）。 */
    fun planIndex(knowledgeBaseId: String, onResult: (KnowledgeIndexPlanDto?, String?) -> Unit) {
        workScope.launch {
            try {
                val plan = service.planIndex(knowledgeBaseId)
                val dto = KnowledgeIndexPlanDto(
                    chunkCount = plan.chunkCount,
                    usesRemoteEmbedding = plan.usesRemoteEmbedding,
                    requiresUserConfirmation = plan.requiresUserConfirmation,
                )
                mainScope.launch { onResult(dto, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, errorText(t, "读取索引计划失败")) }
            }
        }
    }

    /**
     * 全量索引（重建，等价 Android「重建索引」/ indexBook）：进度回调 (done, total)。
     * 远程嵌入模式需先经 UI 确认；本环境默认 local（本地哈希嵌入，离线免费）。
     */
    fun index(
        knowledgeBaseId: String,
        onProgress: (Int, Int) -> Unit,
        onResult: (KnowledgeIndexResultDto?, String?) -> Unit,
    ) {
        workScope.launch {
            try {
                val result = service.indexBook(knowledgeBaseId) { done, total ->
                    mainScope.launch { runCatching { onProgress(done, total) } }
                }
                val book = repository.get(knowledgeBaseId)
                val dto = result.toDto(book?.indexStatus.orEmpty())
                mainScope.launch { onResult(dto, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, errorText(t, "索引知识库失败")) }
            }
        }
    }

    /** 重试失败切块（只处理 indexedAt<=0 或 indexError 非空的块）。 */
    fun retryFailedChunks(
        knowledgeBaseId: String,
        onProgress: (Int, Int) -> Unit,
        onResult: (KnowledgeIndexResultDto?, String?) -> Unit,
    ) {
        workScope.launch {
            try {
                val result = service.retryFailedChunks(knowledgeBaseId) { done, total ->
                    mainScope.launch { runCatching { onProgress(done, total) } }
                }
                val book = repository.get(knowledgeBaseId)
                val dto = result.toDto(book?.indexStatus.orEmpty())
                mainScope.launch { onResult(dto, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, errorText(t, "重试失败切块失败")) }
            }
        }
    }

    /** 使索引失效（清向量分区 + 切块索引标记，状态回 pending；不动正文与切块）。 */
    fun invalidate(knowledgeBaseId: String, onResult: (String?) -> Unit) {
        workScope.launch {
            try {
                service.invalidate(knowledgeBaseId)
                mainScope.launch { onResult(null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(errorText(t, "清除索引失败")) }
            }
        }
    }

    // ------------------------------------------------------------------ 关联干员（召回前提）

    /** 可关联干员（operators 表，真实数据）。 */
    fun listOperators(onResult: (List<KnowledgeOperatorDto>, String?) -> Unit) {
        workScope.launch {
            try {
                val dtos = chatRepo.operators.getAllOperatorsSync().map {
                    KnowledgeOperatorDto(id = it.id, name = it.name, avatarId = it.avatarUri)
                }
                mainScope.launch { onResult(dtos, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(emptyList(), errorText(t, "读取干员列表失败")) }
            }
        }
    }

    /**
     * 设置「知识库 ↔ 干员」关联（召回过滤条件，语义同 Android 角色知识库绑定）。
     * 回调返回更新后的已关联干员 id 列表。
     */
    fun setAssignment(
        knowledgeBaseId: String,
        operatorId: String,
        enabled: Boolean,
        onResult: (List<String>?, String?) -> Unit,
    ) {
        workScope.launch {
            try {
                val current = repository.getAssignmentsForKnowledgeBase(knowledgeBaseId)
                    .filter { it.enabled }
                    .map { it.operatorId }
                val updated = if (enabled) (current + operatorId).distinct() else current - operatorId
                repository.replaceAssignmentsForKnowledgeBase(knowledgeBaseId, updated)
                mainScope.launch { onResult(updated, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, errorText(t, "更新知识库关联失败")) }
            }
        }
    }

    // ------------------------------------------------------------------ 召回测试

    /**
     * 召回测试：`query → 相关块 + 分数`（真实 `KnowledgeBaseService.recallForPrivateChat`，
     * 与聊天时注入知识库资料走同一条链路，含 4.5s 工作预算 / 3 条 / 1200 字上限）。
     *
     * @param bookIds 限定参与召回的知识库；空列表 = 该干员关联的的全部知识库。
     * @param reason 返回原因码（no_assignment / index_not_ready / no_match / embedding_unavailable / blank_query）。
     */
    fun recallTest(
        operatorId: String,
        query: String,
        bookIds: List<String>,
        onResult: (KnowledgeRecallDto?, String?) -> Unit,
    ) {
        workScope.launch {
            try {
                val recall = service.recallForPrivateChat(
                    operatorId = operatorId,
                    query = query,
                    assignedBookIds = bookIds.toSet().takeIf { it.isNotEmpty() },
                )
                val dto = KnowledgeRecallDto(
                    text = recall.text,
                    hits = recall.hits.map {
                        KnowledgeRecallHitDto(
                            book = it.book,
                            snippet = it.snippet,
                            score = it.score,
                            injected = it.injected,
                        )
                    },
                    reason = recall.reason,
                    reasonText = recallReasonText(recall.reason, recall.hits.size),
                    assignedBooks = recall.assignedBooks,
                )
                mainScope.launch { onResult(dto, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, errorText(t, "召回测试失败")) }
            }
        }
    }

    // ------------------------------------------------------------------ 内部映射

    private fun KnowledgeBase.toDto(chunks: List<KnowledgeBaseChunk>, assignedOperatorIds: List<String>) = KnowledgeBaseDto(
        id = id,
        name = name,
        chunkCount = chunks.size,
        indexedChunkCount = chunks.count { it.enabled && it.indexedAt > 0L && it.indexError.isBlank() },
        failedChunkCount = chunks.count { it.indexError.isNotBlank() },
        indexStatus = indexStatus,
        indexStatusText = indexStatusText(indexStatus),
        sourceFormat = sourceFormat,
        sourceFileName = sourceFileName,
        rawContentLength = rawContent.length,
        rawContentPreview = rawContent.take(160),
        updatedAt = updatedAt,
        createdAt = createdAt,
        assignedOperatorCount = assignedOperatorIds.size,
    )

    private fun KnowledgeBaseChunk.toDto() = KnowledgeChunkDto(
        id = id,
        ordinal = ordinal,
        heading = sourceHeading,
        content = content,
        enabled = enabled,
        indexedAt = indexedAt,
        indexError = indexError,
    )

    private fun com.rhodes.privatechat.shared.knowledge.KnowledgeBaseIndexResult.toDto(statusAfter: String) =
        KnowledgeIndexResultDto(
            knowledgeBaseId = knowledgeBaseId,
            total = total,
            succeeded = succeeded,
            failed = failed,
            errors = errors,
            statusAfter = statusAfter,
            statusAfterText = indexStatusText(statusAfter),
        )

    private fun indexStatusText(status: String): String = when {
        status == "ready" -> "已索引"
        status == "pending" -> "待索引"
        status == "pending_confirm" -> "待确认（远程嵌入）"
        status == "processing" -> "切块中"
        status.startsWith("indexing") -> "索引中"
        status.startsWith("partial_indexing") -> "索引中（增量）"
        status == "partial_failed" -> "部分失败"
        status == "partial_pending_confirm" -> "部分待确认"
        status == "failed" -> "索引失败"
        status.isBlank() -> "未知"
        else -> status
    }

    private fun recallReasonText(reason: String, hitCount: Int): String = when (reason) {
        "" -> if (hitCount > 0) "命中 $hitCount 条相关块" else "未命中"
        "blank_query" -> "请输入召回测试的查询内容"
        "no_assignment" -> "该知识库尚未关联到所选干员：请在详情页「关联干员」中勾选后再测试"
        "embedding_unavailable" -> "向量模型不可用：请检查设置中的本地/远程向量配置"
        "index_not_ready" -> "索引未就绪：请先执行「索引 / 重建」并等待状态变为已索引"
        "no_match" -> "没有匹配到相关块（可换更贴近正文的关键词）"
        else -> reason
    }

    private fun errorText(t: Throwable, prefix: String): String {
        val raw = t.message.orEmpty()
        return if (raw.isBlank()) prefix else "$prefix：$raw"
    }
}

// ---------------------------------------------------------------------------
// DTO（纪律同 IosDtos.kt：普通 class + 简单类型，只读）
// ---------------------------------------------------------------------------

/** 知识库列表/详情用的知识库摘要。 */
class KnowledgeBaseDto(
    val id: String,
    val name: String,
    val chunkCount: Int,
    val indexedChunkCount: Int,
    val failedChunkCount: Int,
    /** DB 原始状态：pending / pending_confirm / processing / indexing:x/y / ready / partial_failed … */
    val indexStatus: String,
    /** 中文状态标签（徽标展示）。 */
    val indexStatusText: String,
    val sourceFormat: String,
    val sourceFileName: String,
    val rawContentLength: Int,
    val rawContentPreview: String,
    val updatedAt: Long,
    val createdAt: Long,
    val assignedOperatorCount: Int,
)

/** 切块预览（前 N 块；正文由 UI 截断展示）。 */
class KnowledgeChunkDto(
    val id: String,
    val ordinal: Int,
    val heading: String,
    val content: String,
    val enabled: Boolean,
    val indexedAt: Long,
    val indexError: String,
)

class KnowledgeBaseDetailDto(
    val book: KnowledgeBaseDto,
    val chunks: List<KnowledgeChunkDto>,
    val totalChunks: Int,
    val assignedOperatorIds: List<String>,
)

class KnowledgeBaseStatusDto(
    val knowledgeBaseId: String,
    val indexStatus: String,
    val indexStatusText: String,
    val chunkCount: Int,
    val indexedChunkCount: Int,
    val failedChunkCount: Int,
    val updatedAt: Long,
)

class KnowledgeIndexPlanDto(
    val chunkCount: Int,
    val usesRemoteEmbedding: Boolean,
    val requiresUserConfirmation: Boolean,
)

class KnowledgeIndexResultDto(
    val knowledgeBaseId: String,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val errors: List<String>,
    val statusAfter: String,
    val statusAfterText: String,
)

class KnowledgeOperatorDto(
    val id: String,
    val name: String,
    val avatarId: String,
)

class KnowledgeRecallHitDto(
    val book: String,
    val snippet: String,
    val score: Double,
    val injected: Boolean,
)

class KnowledgeRecallDto(
    val text: String,
    val hits: List<KnowledgeRecallHitDto>,
    val reason: String,
    val reasonText: String,
    val assignedBooks: Int,
)
