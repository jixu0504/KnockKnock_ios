package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.prompt.PromptTestSettings
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.shared.vector.DisabledVectorStoreGateway
import com.rhodes.privatechat.shared.vector.MemoryVectorService
import com.rhodes.privatechat.shared.vector.VectorMemory
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 小语料的「查询—事实」证据（第三轮 10 轮实测）：5 条记忆也必须能回答，
 * 同时「花生多少钱一斤」这类一般问题不能被打成记忆查询。
 */
class MemoryQueryEvidenceTest {

    private val now = 1_800_000_000_000L

    private val facts = linkedMapOf(
        1L to Triple("用户喜欢喝不加糖的美式咖啡", "fact:positive:user|prefer|美式咖啡|q=不加糖", "preference_expression"),
        2L to Triple("用户养了一只三岁的布偶猫，名字叫团子", "fact:positive:user|own|布偶猫团子三岁", "event"),
        3L to Triple("用户不喜欢吃香菜，点餐时要避免加香菜", "fact:negative:user|dislike|香菜", "preference_expression"),
        4L to Triple("用户住在杭州", "fact:positive:user|reside|杭州", "event"),
        5L to Triple("用户答应与角色周末一起去看电影", "fact:positive:user|promise|看电影|q=周末", "agreement_commitment"),
    )

    private fun candidates() = facts.map { (id, fact) ->
        VectorMemory(
            id = "memory_v2_operator_op1_l1_$id",
            ownerType = "operator",
            ownerId = "op1",
            sourceType = "memory_v2_l1",
            sourceId = "s1",
            content = fact.first,
            originContent = fact.first,
            importance = 0.6,
            tags = "L1,${fact.third},PRIVATE_CHAT",
            visibility = "private",
            createdAt = now - id * 3_600_000L,
            originMemoryId = id,
            lexicalScore = 1.39,
            lexicalMatchCount = 2,
            lexicalMaxTermIdf = 1.39,
        )
    }

    private fun engine(): MemoryRecallEngine {
        val settings = SettingsRepository(PromptTestSettings())
        return MemoryRecallEngine(
            settings = settings,
            memoryVectorService = MemoryVectorService(settings, DisabledVectorStoreGateway()),
            lexicalRecall = { _, _ -> candidates() },
            factKeyResolver = { ids -> facts.filterKeys { it in ids }.mapValues { it.value.second } },
            nowMillis = { now },
        )
    }

    private suspend fun recall(query: String): String =
        engine().buildPrivateChatMemoryContext("op1", query, allowedSources = setOf("PRIVATE_CHAT"))

    @Test
    fun smallCorpusAnswersPreferenceEntityDislikeAndResidence() = runBlocking {
        assertTrue(recall("我喜欢喝什么咖啡？").contains("美式咖啡"))
        assertTrue(recall("团子几岁了？").contains("团子"))
        assertTrue(recall("我不吃什么？").contains("香菜"))
        assertTrue(recall("我住在哪？").contains("杭州"))
    }

    @Test
    fun generalKnowledgeQuestionStillAbstains() = runBlocking {
        assertTrue(recall("花生多少钱一斤").isEmpty())
        assertTrue(recall("推荐一部电影").isEmpty())
    }
}
