package com.rhodes.privatechat.shared.backup

/**
 * 恢复后自检（规格卡契约 D30；计划书 line 179「Android 备份在 iOS 恢复后自检通过」的 iOS 补强项；
 * T10 规格卡契约 #13 预留）。
 *
 * 口径：恢复**已提交**后按 `manifest.recordCounts` 对账本次选中类目的 DB 计数（只读，不修改数据）。
 * 对账失败不翻转 `BackupRestoreResult.success`（恢复已提交），由调用方展示 mismatch 列表；
 * 这与 Android 现状一致（Android 无显式自检步骤）。
 */
data class BackupRestoreSelfCheckResult(
    val passed: Boolean,
    /** key → (manifest 期望, DB 实际)。 */
    val checked: Map<String, Pair<Int, Int>> = emptyMap(),
    val mismatches: List<String> = emptyList(),
)

class BackupRestoreSelfCheck {

    /**
     * @param selection 实际执行的恢复选择（`normalized()` 后口径）。
     * 检查范围与 [BackupSelectiveRestoreExecutor] 实际承担的类目一致：
     *  - `roles && chats` → operators（整体替换，期望 == manifest）
     *  - `chats` → sessions / messages / displayEvents
     *  - `roles && !chats` → 不检查 operators（upsert 保留现有角色，计数不可预期）
     */
    suspend fun check(
        manifest: BackupManifestWire,
        selection: BackupContentSelection,
        repository: BackupRestoreRepository,
    ): BackupRestoreSelfCheckResult {
        val normalized = selection.normalized()
        var operators = 0
        var sessions = 0
        var messages = 0
        var displayEvents = 0
        repository.runRestoreTransaction {
            operators = operatorsQueries.getCount().executeAsOne().toInt()
            sessions = chatSessionsQueries.getSessionCount().executeAsOne().toInt()
            messages = chatMessagesQueries.getMessageCount().executeAsOne().toInt()
            displayEvents = chatDisplayEventsQueries.getAllDisplayEvents { messageId, _, _, _ -> messageId }.executeAsList().size
        }
        val expected = manifest.recordCounts
        val checked = linkedMapOf<String, Pair<Int, Int>>()
        fun compare(key: String, actual: Int) {
            val wanted = expected[key] ?: return
            checked[key] = wanted to actual
        }
        if (normalized.roles && normalized.chats) compare("operators", operators)
        if (normalized.chats) {
            compare("sessions", sessions)
            compare("messages", messages)
            compare("displayEvents", displayEvents)
        }
        val mismatches = checked.filter { (_, pair) -> pair.first != pair.second }
            .map { (key, pair) -> "$key：期望 ${pair.first}，实际 ${pair.second}" }
        return BackupRestoreSelfCheckResult(passed = mismatches.isEmpty(), checked = checked, mismatches = mismatches)
    }
}
