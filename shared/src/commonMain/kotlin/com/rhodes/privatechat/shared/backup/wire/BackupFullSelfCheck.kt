package com.rhodes.privatechat.shared.backup.wire

import com.rhodes.privatechat.shared.backup.BackupContentSelection
import com.rhodes.privatechat.shared.backup.BackupManifestWire
import com.rhodes.privatechat.shared.backup.BackupPortableSettings
import com.rhodes.privatechat.shared.backup.BackupRestoreRepository
import com.rhodes.privatechat.shared.backup.BackupRestoreSelfCheckResult
import com.rhodes.privatechat.shared.settings.SettingsRepository

/**
 * 全类目恢复后自检（T31；规格卡契约 C7.2，lead D2/D4 裁决 2026-11-23）。
 *
 * 口径：
 *  - **主口径 = 过滤后 payload 派生计数 vs DB**（权威；补足 Android `manifest.recordCounts` 缺的
 *    memories/anchors/memoryItems/likes/participants/mahjong 六项——D4 批准不改备份格式）；
 *  - manifest.recordCounts 作**交叉校验**：仅当该键在选中类目内且 payload 期望计数 == manifest 值
 *    时比对（孤儿过滤/分类选择会改变计数，此时以 payload 为准，跳过 manifest 比对）；
 *  - 字段级不变量（向量清空 / moments 计数回填）与设置全量键值一并纳入 mismatch；
 *  - 只读，不修改数据；**不翻转**已提交恢复的 `success`（T10 口径）。
 */
class BackupFullSelfCheck(
    private val portableSettings: BackupPortableSettings = BackupPortableSettings(),
) {
    suspend fun check(
        manifest: BackupManifestWire,
        selection: BackupContentSelection,
        payload: FullBackupPayloadWire,
        repository: BackupRestoreRepository,
        settings: SettingsRepository,
    ): BackupRestoreSelfCheckResult {
        val normalized = selection.normalized()
        val expected = BackupFullCounts.expected(payload, normalized)
        var actual: Map<String, Int> = emptyMap()
        var invariants: List<String> = emptyList()
        repository.runRestoreTransaction {
            actual = BackupFullCounts.read(this)
            invariants = BackupFullCounts.invariantViolations(this, normalized)
        }
        val checked = linkedMapOf<String, Pair<Int, Int>>()
        val mismatches = mutableListOf<String>()
        expected.forEach { (key, wanted) ->
            if (key == "settings") return@forEach // Preferences 不在 SQLite，单独键值对账
            val got = actual[key] ?: 0
            checked[key] = wanted to got
            if (got != wanted) mismatches += "$key：期望 $wanted，实际 $got"
        }
        // manifest 交叉校验（仅未被过滤/选择改变计数的键）。
        manifest.recordCounts.forEach { (key, declared) ->
            val wanted = expected[key] ?: return@forEach
            if (wanted != declared) return@forEach
            val got = actual[key] ?: 0
            if (got != declared) mismatches += "manifest.$key：期望 $declared，实际 $got"
        }
        mismatches += invariants
        // 设置全量键值对账（选中 settings 且备份含设置时）。
        if (normalized.settings) {
            val expectedSettings = payload.content.settings
            if (expectedSettings != null) {
                val snapshot = portableSettings.snapshot(repository, settings)
                var verified = 0
                expectedSettings.forEach { (key, encoded) ->
                    if (key == "hidden_ids") {
                        // known quirk（Android 同款）：snapshot 用 getStringSet，apply 写纯字符串。
                        val raw = settings.getString("hidden_ids")
                        if (raw == encoded.removePrefix("s:")) verified++ else {
                            mismatches += "settings.hidden_ids：期望 ${encoded.removePrefix("s:")}，实际 $raw"
                        }
                        return@forEach
                    }
                    val actualValue = snapshot[key]
                    if (actualValue == null) {
                        if (!isRoleLocalKey(key)) mismatches += "settings.$key：恢复后缺失"
                        return@forEach
                    }
                    if (actualValue != encoded) mismatches += "settings.$key：期望 $encoded，实际 $actualValue"
                    else verified++
                }
                checked["settings"] = expectedSettings.size to verified
            }
        }
        return BackupRestoreSelfCheckResult(passed = mismatches.isEmpty(), checked = checked, mismatches = mismatches)
    }

    private fun isRoleLocalKey(key: String): Boolean =
        key.startsWith("operator_prompt_slot_") || key.startsWith("msg_") || key.startsWith("dyn_") ||
            key.startsWith("voice_volume_") || key.startsWith("chat_tts_") || key.startsWith("bg_") ||
            key.startsWith("diary_read_at_") || key.startsWith("last_mode_") || key.startsWith("gbg_")
}
