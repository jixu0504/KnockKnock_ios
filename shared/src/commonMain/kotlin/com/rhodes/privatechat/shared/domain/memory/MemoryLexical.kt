package com.rhodes.privatechat.shared.domain.memory

/**
 * 词法索引的分词与 BM25（计划书 §4.6.2 / T70）。
 *
 * 设计约束：
 * - 中文按 2–3 元 n-gram 建索引（两字查询天然可命中；不依赖 FTS5 trigram 的 3 字下限）；
 * - **停用词不得包含否定词**（不/没/未/别/无/非）——它们是极性判断的关键线索，只是当前
 *   极性判定由事实结构负责（M2），词法通道仍必须保留这些 gram；
 * - 只做去标点、统一小写；不做同义词扩展（同义/概念级召回属于可选嵌入或端侧模型的职责）。
 */
object MemoryLexicalTokenizer {

    /** 单次查询最多使用的 term 数，避免长 query 把 SQL `IN` 撑爆。 */
    const val MAX_QUERY_TERMS = 32

    /**
     * 单条记忆最多写入的 term 数。60 字中文约产生 60 个 bigram + 58 个 trigram，
     * 120 字会接近 240 —— 不设上限会让倒排体积超过向量本身（见 handoff 的存储说明，
     * T79 会用同样的上限做体积标定）。保留靠前的 term：记忆正文通常先给主体与事实。
     */
    const val MAX_TERMS_PER_DOCUMENT = 160

    /** 单字停用词（仅用于过滤「整条 gram 都是停用词」的情况）。否定词不在此列。 */
    private val stopChars = setOf(
        '的', '了', '是', '我', '你', '他', '她', '它', '们', '在', '有', '和', '就', '都', '也',
        '很', '还', '说', '要', '这', '那', '吗', '啊', '呢', '吧', '与', '及', '或', '着', '过',
        '得', '地', '嘛', '呀', '把', '被', '给', '对', '从', '到', '会', '能', '一', '个', '什', '么',
    )

    fun tokenize(text: String): Map<String, Int> {
        if (text.isBlank()) return emptyMap()
        val counts = linkedMapOf<String, Int>()
        fun add(term: String) {
            if (term.isNotBlank()) counts[term] = (counts[term] ?: 0) + 1
        }
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                isCjk(ch) -> {
                    val start = index
                    while (index < text.length && isCjk(text[index])) index++
                    addCjkRun(text.substring(start, index), ::add)
                }

                ch.isLetterOrDigit() -> {
                    val start = index
                    while (index < text.length && text[index].isLetterOrDigit() && !isCjk(text[index])) index++
                    val run = text.substring(start, index).lowercase()
                    if (run.length >= 2 || run.any { it.isDigit() }) add(run)
                }

                else -> index++
            }
        }
        return if (counts.size <= MAX_TERMS_PER_DOCUMENT) counts else {
            LinkedHashMap<String, Int>().apply {
                counts.entries.take(MAX_TERMS_PER_DOCUMENT).forEach { (term, tf) -> put(term, tf) }
            }
        }
    }

    fun queryTerms(query: String): List<String> = tokenize(query).keys.take(MAX_QUERY_TERMS)

    private fun addCjkRun(run: String, add: (String) -> Unit) {
        if (run.length == 1) {
            if (!stopChars.contains(run[0])) add(run)
            return
        }
        for (size in 2..3) {
            if (run.length < size) continue
            for (start in 0..run.length - size) {
                val gram = run.substring(start, start + size)
                // 只有全部由停用字组成的 gram 才丢弃；「不喜」「不喜欢」必须保留。
                if (gram.any { !stopChars.contains(it) }) add(gram)
            }
        }
    }

    private fun isCjk(ch: Char): Boolean =
        ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF || ch.code in 0xF900..0xFAFF
}

/** BM25 评分（k1/b 为计划书初始值，需在回放集上标定）。 */
object MemoryBm25 {
    const val K1 = 1.2
    const val B = 0.75

    fun idf(docCount: Int, documentFrequency: Int): Double {
        val n = docCount.coerceAtLeast(1)
        val df = documentFrequency.coerceIn(0, n)
        return kotlin.math.ln(1.0 + (n - df + 0.5) / (df + 0.5))
    }

    fun score(
        tf: Int,
        df: Int,
        docLength: Int,
        docCount: Int,
        averageLength: Double,
        k1: Double = K1,
        b: Double = B,
    ): Double {
        if (tf <= 0 || docLength <= 0 || docCount <= 0) return 0.0
        val norm = 1.0 - b + b * docLength / averageLength.coerceAtLeast(1e-6)
        return idf(docCount, df) * (tf * (k1 + 1.0)) / (tf + k1 * norm)
    }
}

/**
 * 查询意图（规则分类，零模型调用；计划书 §4.6.1）。
 * 用于选择「主信号」：事实类问题优先词法/实体，偏好类问题优先向量。
 */
enum class MemoryQueryIntent {
    FACT,
    PREFERENCE,
    TIME,
    RELATIONSHIP,
    UNKNOWN;

    companion object {
        // 「哪天/什么时候」是直接问日期的事实问题（FACT）；TIME 负责「以前/上次/最近」这类时间推理。
        private val TIME_WORDS = listOf("以前", "之前", "上次", "最近", "第一次", "多久", "后来", "现在")
        private val RELATIONSHIP_WORDS = listOf("我们", "一起", "答应", "约定", "关系", "朋友", "你们", "对我说", "承诺", "记得我")
        private val PREFERENCE_WORDS = listOf("喜欢", "讨厌", "爱吃", "偏好", "口味", "不喜欢", "最想", "受不了")
        private val FACT_WORDS = listOf(
            "什么", "哪里", "哪儿", "谁", "几", "多少", "生日", "住在", "工作", "名字", "叫",
            "地址", "号码", "几点", "哪一年", "哪个月", "星期", "岁",
        )

        fun classify(query: String): MemoryQueryIntent {
            val q = query.trim()
            if (q.isBlank()) return UNKNOWN
            if (TIME_WORDS.any(q::contains)) return TIME
            if (RELATIONSHIP_WORDS.any(q::contains)) return RELATIONSHIP
            if (PREFERENCE_WORDS.any(q::contains)) return PREFERENCE
            if (FACT_WORDS.any(q::contains)) return FACT
            return UNKNOWN
        }
    }
}