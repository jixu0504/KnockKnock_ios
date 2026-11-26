package com.rhodes.privatechat.shared.backup.wire

import com.rhodes.privatechat.shared.backup.BackupPortableSettings
import com.rhodes.privatechat.shared.backup.BackupRestoreRepository
import com.rhodes.privatechat.shared.settings.SettingsRepository

/**
 * 全类目设置恢复（T31；规格卡契约 C8，lead D3 裁决 2026-11-23）。
 *
 * - `selection.settings=true`：快照 → `clearForRestore` → apply 备份全部非敏感设置（Android 语义）；
 * - `selection.settings=false` 但恢复 roles/chats：**只 apply 角色局部 key 家族**
 *   （`operator_prompt_slot_*`/`msg_*`/`dyn_*`/`voice_volume_*`/`chat_tts_*`/`bg_*`/`diary_read_at_*`/
 *   `last_mode_*`/`gbg_*`）——保留 Android「角色 prompt 槽位随角色恢复」意图，不覆盖用户全局设置；
 * - 回滚：`apply(previous)` + 删除本次新增且原快照不存在的 key（快照回放 + 补强，T10 三层回滚第 3 层）。
 *
 * 密钥类设置不在 `BackupPortableSettings` 白名单，永不进备份、恢复不触碰。
 */
class BackupFullSettingsRestorer(
    private val portable: BackupPortableSettings = BackupPortableSettings(),
) {
    suspend fun snapshot(repository: BackupRestoreRepository, settings: SettingsRepository): Map<String, String> =
        portable.snapshot(repository, settings)

    suspend fun clearForRestore(repository: BackupRestoreRepository, settings: SettingsRepository) {
        portable.clearForRestore(repository, settings)
    }

    /** 待 apply 的设置值：全量（settings 选中）或角色局部子集（未选中但恢复角色/会话）。 */
    fun select(values: Map<String, String>, full: Boolean): Map<String, String> =
        if (full) values else values.filterKeys(::isRoleLocalKey)

    fun apply(settings: SettingsRepository, values: Map<String, String>) {
        if (values.isNotEmpty()) portable.apply(settings, values)
    }

    /** 回滚：先删本次新增 key，再回放旧快照（顺序保证不残留）。 */
    fun rollback(settings: SettingsRepository, previous: Map<String, String>, applied: Map<String, String>) {
        (applied.keys - previous.keys).forEach(settings::remove)
        portable.apply(settings, previous)
    }

    private fun isRoleLocalKey(key: String): Boolean =
        key.startsWith("operator_prompt_slot_") || key.startsWith("msg_") || key.startsWith("dyn_") ||
            key.startsWith("voice_volume_") || key.startsWith("chat_tts_") || key.startsWith("bg_") ||
            key.startsWith("diary_read_at_") || key.startsWith("last_mode_") || key.startsWith("gbg_")
}
