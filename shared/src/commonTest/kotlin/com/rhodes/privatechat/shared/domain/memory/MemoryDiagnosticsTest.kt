package com.rhodes.privatechat.shared.domain.memory

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T27 接线批 B：MemoryDiagnostics operation 时间线接口恢复的回归测试。
 *
 * 覆盖：
 * 1) 默认 no-op（未安装 sink）语义与接线前一致：beginOperation 返回空 id、conversationStep 不抛；
 * 2) 安装 [MemoryOperationSink] 后 beginOperation/conversationStep 原样转发（app 适配 DebugLogger 的契约）；
 * 3) log/trace 通道与 trace 的 allowSensitiveTrace 门禁保持 T24 行为不变。
 */
class MemoryDiagnosticsTest {

    @AfterTest
    fun reset() {
        MemoryDiagnostics.install(null)
        MemoryDiagnostics.installOperations(null)
        MemoryDiagnostics.allowSensitiveTrace = false
    }

    @Test
    fun defaultIsNoOp_whenNoSinkInstalled() {
        assertEquals("", MemoryDiagnostics.beginOperation("私聊记忆提取", "对象", "online"))
        // 不抛即通过（no-op 语义）
        MemoryDiagnostics.conversationStep("", "私聊记忆提取", "本轮总览", "成功", "细节")
        MemoryDiagnostics.log("MemoryV2", "未安装 sink 时静默丢弃")
        MemoryDiagnostics.trace("MemoryV2", "未开启敏感 trace 时静默丢弃")
    }

    @Test
    fun operationSinkReceivesBeginAndStep_inOrder() {
        val calls = mutableListOf<String>()
        MemoryDiagnostics.installOperations(object : MemoryOperationSink {
            override fun beginOperation(surface: String, target: String, mode: String): String {
                calls += "begin:$surface:$target:$mode"
                return "op-1"
            }

            override fun conversationStep(roundId: String, surface: String, stage: String, status: String, details: String) {
                calls += "step:$roundId:$surface:$stage:$status:$details"
            }
        })
        val roundId = MemoryDiagnostics.beginOperation("群聊记忆提取", "群名", "online")
        assertEquals("op-1", roundId)
        MemoryDiagnostics.conversationStep(roundId, "群聊记忆提取", "提取窗口", "进行中", "消息=3")
        MemoryDiagnostics.conversationStep(roundId, "群聊记忆提取", "本轮总览", "成功", "已保存1条")
        assertEquals(
            listOf(
                "begin:群聊记忆提取:群名:online",
                "step:op-1:群聊记忆提取:提取窗口:进行中:消息=3",
                "step:op-1:群聊记忆提取:本轮总览:成功:已保存1条",
            ),
            calls,
        )
    }

    @Test
    fun logSinkAndSensitiveTraceGateRemainUnchanged() {
        val entries = mutableListOf<String>()
        MemoryDiagnostics.install { tag, message -> entries += "$tag|$message" }
        MemoryDiagnostics.log("MemoryV2", "普通日志")
        MemoryDiagnostics.trace("AI/→提取", "敏感报文")
        assertEquals(listOf("MemoryV2|普通日志"), entries, "allowSensitiveTrace=false 时 trace 必须被丢弃")
        MemoryDiagnostics.allowSensitiveTrace = true
        MemoryDiagnostics.trace("AI/→提取", "敏感报文")
        assertTrue(entries.contains("AI/→提取|敏感报文"), "allowSensitiveTrace=true 时 trace 必须转发")
    }
}
