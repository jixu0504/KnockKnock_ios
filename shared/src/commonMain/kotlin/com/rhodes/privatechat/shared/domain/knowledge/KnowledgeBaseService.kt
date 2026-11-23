package com.rhodes.privatechat.shared.domain.knowledge

import com.rhodes.privatechat.shared.knowledge.KnowledgeBaseContextBuilder
import com.rhodes.privatechat.shared.knowledge.KnowledgeBaseImportService
import com.rhodes.privatechat.shared.knowledge.KnowledgeBaseIndexResult
import com.rhodes.privatechat.shared.knowledge.KnowledgeBaseIndexService
import com.rhodes.privatechat.shared.knowledge.KnowledgeBaseRecallPolicy
import com.rhodes.privatechat.shared.model.KnowledgeBase

/**
 * 知识库域门面（规格卡「抽取落点建议」）：聚合导入/索引/召回三服务，供私聊/群聊组装层经
 * `shared.domain.knowledge` 单一接口跨域调用，不直接触碰 `shared.knowledge` 实现类。
 *
 * 实现全部复用现有 shared/knowledge（5 文件，已 KMP 且无平台耦合，契约 22-24 的行为在那里）；
 * 本门面只做签名归一与召回参数默认值，不含新逻辑。
 */
class KnowledgeBaseService(
    private val importService: KnowledgeBaseImportService,
    private val indexService: KnowledgeBaseIndexService,
    private val contextBuilder: KnowledgeBaseContextBuilder,
) {

    // --- 导入（契约 22）---
    suspend fun importFile(fileName: String, bytes: ByteArray): KnowledgeBase =
        importService.importFile(fileName, bytes)

    suspend fun saveText(
        name: String,
        content: String,
        sourceFormat: String = "txt",
        sourceType: String = "user",
    ): KnowledgeBase = importService.saveText(name, content, sourceFormat, sourceType)

    suspend fun updateText(existing: KnowledgeBase, name: String, content: String): KnowledgeBase =
        importService.updateText(existing, name, content)

    // --- 索引（契约 23）---
    suspend fun planIndex(knowledgeBaseId: String) = indexService.planIndex(knowledgeBaseId)

    suspend fun planDirtyIndex(knowledgeBaseId: String) = indexService.planDirtyIndex(knowledgeBaseId)

    suspend fun indexBook(
        knowledgeBaseId: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): KnowledgeBaseIndexResult = indexService.indexBook(knowledgeBaseId, onProgress)

    suspend fun retryFailedChunks(
        knowledgeBaseId: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): KnowledgeBaseIndexResult = indexService.retryFailedChunks(knowledgeBaseId, onProgress)

    suspend fun invalidate(knowledgeBaseId: String) = indexService.invalidate(knowledgeBaseId)

    suspend fun markChunksPendingConfirmation(knowledgeBaseId: String) =
        indexService.markChunksPendingConfirmation(knowledgeBaseId)

    // --- 召回（契约 24）---
    suspend fun recallForPrivateChat(
        operatorId: String,
        query: String,
        assignedBookIds: Set<String>?,
        candidateLimit: Int = KnowledgeBaseRecallPolicy.PRIVATE_CANDIDATE_LIMIT,
    ): KnowledgeBaseContextBuilder.Recall = contextBuilder.forOperatorRecall(
        operatorId = operatorId,
        query = query,
        maxEntries = KnowledgeBaseRecallPolicy.PRIVATE_FINAL_RESULTS,
        maxChars = KnowledgeBaseRecallPolicy.PRIVATE_MAX_CHARS,
        allowedBookIds = assignedBookIds,
        perBookResults = KnowledgeBaseRecallPolicy.PRIVATE_PER_BOOK_RESULTS,
        candidateLimit = candidateLimit,
    )

    suspend fun recallForGroupChat(
        operatorIds: Collection<String>,
        query: String,
        assignedBookIds: Set<String>,
    ): KnowledgeBaseContextBuilder.Recall = contextBuilder.forOperatorsRecall(
        operatorIds = operatorIds,
        query = query,
        maxEntries = KnowledgeBaseRecallPolicy.PRIVATE_FINAL_RESULTS,
        maxChars = KnowledgeBaseRecallPolicy.PRIVATE_MAX_CHARS,
        allowedBookIds = assignedBookIds,
    )
}
