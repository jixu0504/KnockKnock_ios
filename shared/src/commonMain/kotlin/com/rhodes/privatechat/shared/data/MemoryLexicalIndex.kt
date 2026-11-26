package com.rhodes.privatechat.shared.data

import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.domain.memory.MemoryBm25
import com.rhodes.privatechat.shared.domain.memory.MemoryLexicalTokenizer
import com.rhodes.privatechat.shared.model.MemoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 词法命中（memoryId + BM25 分 + 匹配质量）。
 * [matchedTermCount] / [maxTermIdf] 用于准入判断：只有命中「高区分度词」才算强信号，
 * 避免「最近」这类通用词靠总分堆进注入。
 */
data class LexicalHit(
    val memoryId: Long,
    val score: Double,
    val matchedTermCount: Int,
    val maxTermIdf: Double,
)

/**
 * 记忆词法索引（计划书 §4.6.2 / T70）。
 *
 * - 写入：新记忆落库时在同一事务内增量写倒排与统计（[indexItem]）；
 * - 删除/归档/编辑/恢复：不改写索引，而是通过「语料 docCount ≠ active 条数」判定为陈旧，
 *   在下次检索前整所有者重建（[ensureReady]）——避免在十几个删除路径里各写一遍增量逻辑；
 * - 检索：一次查询取 df、postings、doc length 三组数据，在 Kotlin 侧按 BM25 求和排序。
 *
 * 一致性口径：docCount 与 active 条数一致即认为索引可用；编辑内容但条数不变时索引可能落后
 * 一代（只影响候选排序，召回内容仍从 memory_items 读取最新值）。重建一律以 DB 为准。
 */
class MemoryLexicalIndex(private val db: RhodesDatabase) {

    private val queries get() = db.memoryLexicalQueries

    /** 在记忆插入的同一事务内调用；失败应让事务回滚，不留下「有记忆无索引」的静默状态。 */
    fun indexItem(item: MemoryItem) {
        if (item.id <= 0 || item.content.isBlank()) return
        val terms = MemoryLexicalTokenizer.tokenize(item.content)
        if (terms.isEmpty()) return
        val docLength = terms.values.sum().toLong()
        queries.lexicalInsertDocStats(item.id, docLength)
        terms.forEach { (term, tf) ->
            queries.lexicalInsertTerm(term, item.ownerType, item.ownerId, item.id, tf.toLong())
            queries.lexicalBumpTermStat(term, item.ownerType, item.ownerId, term, item.ownerType, item.ownerId)
        }
        val now = item.updatedAt.takeIf { it > 0 } ?: item.createdAt
        queries.lexicalBumpCorpus(
            item.ownerType,
            item.ownerId,
            item.ownerType,
            item.ownerId,
            item.ownerType,
            item.ownerId,
            docLength,
            now,
        )
    }

    /** 确保该 owner 的索引与 active 条数一致；不一致（含首次/删除/恢复后）则整所有者重建。 */
    suspend fun ensureReady(ownerType: String, ownerId: String, now: Long) = withContext(Dispatchers.Default) {
        val active = queries.lexicalActiveCount(ownerType, ownerId, now).executeAsOne()
        val corpus = queries.lexicalGetCorpus(ownerType, ownerId).executeAsOneOrNull()
        if (corpus == null || corpus.docCount != active) {
            rebuildLocked(ownerType, ownerId, now)
        }
    }

    suspend fun rebuild(ownerType: String, ownerId: String, now: Long) = withContext(Dispatchers.Default) {
        rebuildLocked(ownerType, ownerId, now)
    }

    /** 启动/回前台预热：为所有有 active 记忆的 owner 检查并重建（幂等）。 */
    suspend fun warm(now: Long) = withContext(Dispatchers.Default) {
        queries.lexicalActiveOwners(now).executeAsList().forEach { owner ->
            val active = queries.lexicalActiveCount(owner.ownerType, owner.ownerId, now).executeAsOne()
            val corpus = queries.lexicalGetCorpus(owner.ownerType, owner.ownerId).executeAsOneOrNull()
            if (corpus == null || corpus.docCount != active) {
                rebuildLocked(owner.ownerType, owner.ownerId, now)
            }
        }
    }

    private fun rebuildLocked(ownerType: String, ownerId: String, now: Long) {
        db.transaction {
            queries.lexicalDeleteTerms(ownerType, ownerId)
            queries.lexicalDeleteTermStats(ownerType, ownerId)
            queries.lexicalDeleteDocStatsForOwner(ownerType, ownerId)
            val rows = queries.lexicalActiveContents(ownerType, ownerId, now).executeAsList()
            var totalLength = 0L
            val documentFrequency = mutableMapOf<String, Long>()
            rows.forEach { row ->
                val terms = MemoryLexicalTokenizer.tokenize(row.content)
                if (terms.isEmpty()) return@forEach
                val docLength = terms.values.sum().toLong()
                totalLength += docLength
                queries.lexicalInsertDocStats(row.id, docLength)
                terms.forEach { (term, tf) ->
                    queries.lexicalInsertTerm(term, ownerType, ownerId, row.id, tf.toLong())
                    documentFrequency[term] = (documentFrequency[term] ?: 0L) + 1L
                }
            }
            documentFrequency.forEach { (term, df) ->
                queries.lexicalInsertOrReplaceTermStat(term, ownerType, ownerId, df)
            }
            queries.lexicalInsertOrReplaceCorpus(ownerType, ownerId, rows.size.toLong(), totalLength, now)
        }
    }

    suspend fun search(ownerType: String, ownerId: String, query: String, limit: Int): List<LexicalHit> =
        withContext(Dispatchers.Default) {
            if (limit <= 0) return@withContext emptyList()
            val terms = MemoryLexicalTokenizer.queryTerms(query).distinct()
            if (terms.isEmpty()) return@withContext emptyList()
            val corpus = queries.lexicalGetCorpus(ownerType, ownerId).executeAsOneOrNull()
                ?: return@withContext emptyList()
            if (corpus.docCount <= 0L) return@withContext emptyList()
            val docFrequency = queries.lexicalTermStats(ownerType, ownerId, terms)
                .executeAsList()
                .associate { it.term to it.documentFrequency.toInt() }
            val postings = queries.lexicalPostings(ownerType, ownerId, terms).executeAsList()
            if (postings.isEmpty()) return@withContext emptyList()
            val lengths = queries.lexicalDocLengths(postings.map { it.memoryId }.distinct())
                .executeAsList()
                .associate { it.memoryId to it.documentLength.toInt() }
            val averageLength = corpus.totalDocumentLength.toDouble() / corpus.docCount.toDouble()
            val docCount = corpus.docCount.toInt()
            val scores = mutableMapOf<Long, Double>()
            val matchedTerms = mutableMapOf<Long, Int>()
            val maxIdf = mutableMapOf<Long, Double>()
            postings.forEach { posting ->
                val documentFrequency = docFrequency[posting.term] ?: 0
                val score = MemoryBm25.score(
                    tf = posting.tf.toInt(),
                    df = documentFrequency,
                    docLength = lengths[posting.memoryId] ?: 0,
                    docCount = docCount,
                    averageLength = averageLength,
                )
                if (score <= 0.0) return@forEach
                scores[posting.memoryId] = (scores[posting.memoryId] ?: 0.0) + score
                matchedTerms[posting.memoryId] = (matchedTerms[posting.memoryId] ?: 0) + 1
                val idf = MemoryBm25.idf(docCount, documentFrequency)
                maxIdf[posting.memoryId] = maxOf(maxIdf[posting.memoryId] ?: 0.0, idf)
            }
            scores.entries
                .sortedByDescending { it.value }
                .take(limit)
                .map { hit ->
                    LexicalHit(
                        memoryId = hit.key,
                        score = hit.value,
                        matchedTermCount = matchedTerms[hit.key] ?: 0,
                        maxTermIdf = maxIdf[hit.key] ?: 0.0,
                    )
                }
        }
}