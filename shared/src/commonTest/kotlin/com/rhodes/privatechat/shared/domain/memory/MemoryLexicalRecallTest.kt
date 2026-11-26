package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.prompt.PromptTestSettings
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.shared.vector.DisabledVectorStoreGateway
import com.rhodes.privatechat.shared.vector.MemoryVectorService
import com.rhodes.privatechat.shared.vector.VectorMemory
import com.rhodes.privatechat.shared.vector.VectorSearchRequest
import com.rhodes.privatechat.shared.vector.VectorStoreGateway
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T70 / T72：词法通道（分词、BM25、意图、准入与 abstention）。
 *
 * 这里验证的是「准入规则」，不是召回率：阈值是回放集标定前的保守初始值，
 * 单通道词法命中必须有足够强的 BM25 分，且不能挤满注入预算。
 */
class MemoryLexicalRecallTest {

    private val now = 1_800_000_000_000L

    private fun engine(lexical: List<VectorMemory>): MemoryRecallEngine {
        val settings = SettingsRepository(PromptTestSettings())
        val service = MemoryVectorService(settings, DisabledVectorStoreGateway())
        return MemoryRecallEngine(
            settings = settings,
            memoryVectorService = service,
            lexicalRecall = { _, _ -> lexical },
            nowMillis = { now },
        )
    }

    private fun memory(
        id: Long,
        content: String,
        lexicalScore: Double,
        level: String = "L1",
        type: String = "event",
        createdAt: Long = now - 86_400_000L,
    ) = VectorMemory(
        id = "v$id",
        ownerType = "operator",
        ownerId = "op1",
        sourceType = "memory_v2_${level.lowercase()}",
        sourceId = "s1",
        content = "[2026-03-14·私聊] $content",
        importance = 0.8,
        tags = "$level,$type,PRIVATE_CHAT",
        visibility = "private",
        createdAt = createdAt,
        originMemoryId = id,
        lexicalScore = lexicalScore,
        // 真实词法命中一定带「判别性词」与「命中词数」；否则会被准入的 IDF 门槛挡掉。
        lexicalMatchCount = if (lexicalScore > 0) 2 else 0,
        lexicalMaxTermIdf = if (lexicalScore > 0) 2.5 else 0.0,
    )

    // ------------------------------------------------------------------ 分词

    @Test
    fun tokenizerKeepsNegationAndDropsStopwordOnlyGrams() {
        val terms = MemoryLexicalTokenizer.tokenize("用户不喜欢吃辣")
        assertTrue("不喜欢" in terms, "否定 trigram 必须保留")
        assertTrue("不喜" in terms, "否定前缀 bigram 必须保留")
        assertTrue("喜欢" in terms)
        assertTrue("吃辣" in terms)

        assertTrue(
            MemoryLexicalTokenizer.tokenize("我的").isEmpty(),
            "整条 gram 都是停用字时丢弃",
        )
        // 含实词的三元组保留
        assertTrue("我的猫" in MemoryLexicalTokenizer.tokenize("我的猫"))
    }

    @Test
    fun tokenizerNormalizesLatinAndPunctuation() {
        val terms = MemoryLexicalTokenizer.tokenize("DeepSeek API：v3，可用？")
        assertTrue("deepseek" in terms)
        assertTrue("api" in terms)
        assertTrue("v3" in terms)
        assertFalse("：" in terms)
    }

    // ------------------------------------------------------------------ BM25

    @Test
    fun bm25MatchesHandComputedValue() {
        // N=3, df=1 → idf = ln(1 + 2.5/1.5) = 0.9808；tf=1，dl=avgdl → 归一化项为 1
        val score = MemoryBm25.score(tf = 1, df = 1, docLength = 10, docCount = 3, averageLength = 10.0)
        assertEquals(0.9808, score, 1e-3)

        // tf=2, df=2, N=10, dl=avgdl=15 → idf=ln(4.4)=1.4816；tf 项=4.4/3.2=1.375
        val second = MemoryBm25.score(tf = 2, df = 2, docLength = 15, docCount = 10, averageLength = 15.0)
        assertEquals(2.037, second, 1e-2)

        assertTrue(
            MemoryBm25.idf(10, 1) > MemoryBm25.idf(10, 5),
            "df 越大 IDF 越小",
        )
        val longer = MemoryBm25.score(tf = 1, df = 1, docLength = 40, docCount = 10, averageLength = 10.0)
        val shorter = MemoryBm25.score(tf = 1, df = 1, docLength = 10, docCount = 10, averageLength = 10.0)
        assertTrue(shorter > longer, "文档越长同一 term 的分数越低（b=0.75 长度归一）")
    }

    // ------------------------------------------------------------------ 意图

    @Test
    fun queryIntentRulesCoverPlanTaxonomy() {
        assertEquals(MemoryQueryIntent.FACT, MemoryQueryIntent.classify("用户生日是哪天"))
        assertEquals(MemoryQueryIntent.PREFERENCE, MemoryQueryIntent.classify("我喜欢吃什么"))
        assertEquals(MemoryQueryIntent.TIME, MemoryQueryIntent.classify("上次说的搬家是什么时候"))
        assertEquals(MemoryQueryIntent.RELATIONSHIP, MemoryQueryIntent.classify("我们答应过什么"))
        assertEquals(MemoryQueryIntent.UNKNOWN, MemoryQueryIntent.classify("今天天气不错"))
        assertEquals(MemoryQueryIntent.UNKNOWN, MemoryQueryIntent.classify(""))
    }

    // ------------------------------------------------------------------ 准入与 abstention

    @Test
    fun strongLexicalHitIsAdmittedForFactQuery() = runBlocking {
        val block = engine(listOf(memory(7, "用户生日是三月十四日", lexicalScore = 3.2)))
            .buildPrivateChatMemoryContext("op1", "用户生日是哪天", allowedSources = setOf("PRIVATE_CHAT"))
        assertTrue(block.contains("生日"), "事实类查询下强词法命中必须进入注入块，实际：$block")
    }

    @Test
    fun dualChannelCandidateKeepsLexicalSignal() = runBlocking {
        val settings = SettingsRepository(PromptTestSettings())
        val vectorHit = memory(7, "用户生日是三月十四日", lexicalScore = 0.0).copy(similarity = 0.05)
        val gateway = object : VectorStoreGateway {
            override suspend fun upsert(memory: VectorMemory) = Unit
            override suspend fun search(request: VectorSearchRequest) = listOf(vectorHit)
            override suspend fun listMemories(ownerType: String, ownerId: String) = emptyList<VectorMemory>()
            override suspend fun delete(memoryId: String) = Unit
            override suspend fun deleteBySource(ownerType: String, ownerId: String, sourceId: String) = Unit
            override suspend fun clearOwnerMemory(ownerType: String, ownerId: String) = Unit
            override suspend fun clearAllMemories() = Unit
        }
        val engine = MemoryRecallEngine(
            settings = settings,
            memoryVectorService = MemoryVectorService(settings, gateway),
            lexicalRecall = { _, _ -> listOf(memory(7, "用户生日是三月十四日", lexicalScore = 3.0)) },
            nowMillis = { now },
        )
        val block = engine.buildPrivateChatMemoryContext("op1", "用户生日是哪天", allowedSources = setOf("PRIVATE_CHAT"))
        assertTrue(block.contains("生日"), "同一记忆的双通道信号必须合并保留：$block")
    }

    @Test
    fun weakLexicalHitIsRejectedAndAbstains() = runBlocking {
        val block = engine(listOf(memory(7, "用户生日是三月十四日", lexicalScore = 0.9)))
            .buildPrivateChatMemoryContext("op1", "用户生日是哪天", allowedSources = setOf("PRIVATE_CHAT"))
        assertEquals("", block, "弱词法命中不得强行注入；无相关记忆时应 abstain")
    }

    @Test
    fun lexicalOnlyCandidatesCannotFillInjectionBudget() = runBlocking {
        val candidates = (1L..4L).map { id ->
            memory(id, "用户生日线索$id", lexicalScore = 3.0)
        }
        val block = engine(candidates)
            .buildPrivateChatMemoryContext("op1", "用户生日是哪天", allowedSources = setOf("PRIVATE_CHAT"))
        val injected = block.lines().count { it.startsWith("- ") }
        assertTrue(injected in 1..2, "词法单通道命中最多占 2 条注入名额，实际 $injected 条：$block")
    }
}