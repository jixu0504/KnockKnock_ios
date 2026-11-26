package com.rhodes.privatechat.shared.domain.memory

/**
 * 记忆实体抽取（T69）：把结构化事实的主体/客体转成实体草稿，供实体通道索引。
 *
 * 纯函数、无存储依赖；Pipeline 接线由 Lead 后续集成。否定事实同样抽取——否定的是关系，
 * 不是「这个实体被提到过」这一事实。
 */
internal object MemoryEntityExtractor {

    const val KIND = "entity"
    const val SUBJECT_ROLE = "subject"
    const val OBJECT_ROLE = "object"
    const val DEFAULT_CONFIDENCE = 0.8

    /** 代词与泛指词不作为实体；单字（含单字名）由长度门槛统一过滤。 */
    private val stopwords = setOf(
        "我", "你", "他", "她", "它", "我们", "你们", "他们", "她们", "它们",
        "自己", "本人", "对方", "大家", "别人", "有人", "某人",
        "这个", "那个", "这些", "那些", "这里", "那里", "什么", "东西", "事情",
        // 抽取提示词把说话人写成「用户」；它不是实体，只是代词的角色中性写法。
        "用户", "角色", "对方", "说话人",
    )

    fun extract(
        fact: MemoryParseRules.StructuredFact,
        ownerType: String,
        ownerId: String,
    ): List<MemoryEntityDraft> = listOfNotNull(
        draft(fact.subject, SUBJECT_ROLE, ownerType, ownerId),
        draft(fact.objectValue, OBJECT_ROLE, ownerType, ownerId),
    )

    private fun draft(raw: String, role: String, ownerType: String, ownerId: String): MemoryEntityDraft? {
        val normalized = MemoryParseRules.normalizeFactPart(raw)
        if (normalized.length < 2 || normalized in stopwords) return null
        return MemoryEntityDraft(
            ownerType = ownerType,
            ownerId = ownerId,
            kind = KIND,
            name = raw.trim().ifBlank { normalized },
            normalizedName = normalized,
            role = role,
            confidence = DEFAULT_CONFIDENCE,
        )
    }
}

internal data class MemoryEntityDraft(
    val ownerType: String,
    val ownerId: String,
    val kind: String,
    val name: String,
    val normalizedName: String,
    val role: String,
    val confidence: Double,
)