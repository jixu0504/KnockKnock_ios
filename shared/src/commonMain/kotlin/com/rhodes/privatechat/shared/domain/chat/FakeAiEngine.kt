package com.rhodes.privatechat.shared.domain.chat

import com.rhodes.privatechat.shared.model.AiMessage

/**
 * 确定性 fake AI（lead 裁决，规格卡验收 #6）：输入提示词快照 → 脚本化输出序列。
 *
 * 两种脚本模式：
 * 1. 顺序脚本（sequence）：第 n 次调用返回 scripts[min(n, last)]；
 * 2. 内容匹配（byUserContent）：按最后一条 user 消息内容的子串匹配返回（金样回放用，
 *    使「输入序列 → 输出序列」的对应关系显式写在测试里）。
 * 每次调用都会记录（stage + 完整消息快照 + 返回文本），供金样回放断言输入序列。
 */
class FakeAiEngine(
    scripts: List<String>,
    private val byUserContent: Map<String, String> = emptyMap(),
    private val defaultOutput: String = FAKE_REPLY_JSON,
) : StreamingClient {

    private val sequence = scripts.toList()

    data class RecordedRequest(
        val stage: String,
        val messages: List<AiMessage>,
        val output: String,
    )

    private val _requests = mutableListOf<RecordedRequest>()
    val requests: List<RecordedRequest> get() = _requests.toList()

    private var sequenceIndex = 0
    var suspendBeforeCompleteMillis: Long = 0L

    override suspend fun complete(messages: List<AiMessage>, stage: String): String {
        val matched = byUserContent.entries.firstOrNull { (needle, _) ->
            messages.asReversed().firstOrNull { it.role == "user" }?.content?.contains(needle) == true
        }
        val output = matched?.value
            ?: if (sequence.isEmpty()) defaultOutput else sequence[minOf(sequenceIndex++, sequence.lastIndex)]
        // 先记录请求（在飞观察用），再模拟模型耗时。
        _requests += RecordedRequest(stage, messages.map { it.copy() }, output)
        if (suspendBeforeCompleteMillis > 0) kotlinx.coroutines.delay(suspendBeforeCompleteMillis)
        return output
    }

    /** 最后一条 user 消息内容（金样输入序列断言用）。 */
    fun lastUserContent(): String? =
        _requests.lastOrNull()?.messages?.asReversed()?.firstOrNull { it.role == "user" }?.content

    fun reset() {
        _requests.clear()
        sequenceIndex = 0
    }

    companion object {
        const val FAKE_REPLY_JSON =
            """{"emotion":"平静","state":"坐在窗边","location":"宿舍","continuity":"继续当前话题","segments":[{"type":"dialogue","content":"嗯，我在听。","speaker":""}]}"""

        /** 只有旁白、无台词的输出（触发内容重试 / 无可见回复判定）。 */
        const val NARRATION_ONLY_JSON =
            """{"emotion":"平静","state":"站在门口","location":"走廊","continuity":"继续","segments":[{"type":"narration","content":"她站在原地，没有开口。","speaker":""}]}"""

        fun dialogue(text: String): String =
            """{"emotion":"平静","state":"坐着","location":"房间","continuity":"继续","segments":[{"type":"dialogue","content":"$text","speaker":""}]}"""
    }
}
