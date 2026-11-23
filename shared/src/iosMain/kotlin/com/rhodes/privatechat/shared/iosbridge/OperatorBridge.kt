package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.domain.media.MediaRepository
import com.rhodes.privatechat.shared.domain.media.MediaSource
import com.rhodes.privatechat.shared.files.MediaStoreIos
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.Foundation.NSUUID

/**
 * 角色人设编辑桥（T49 加急：新建 / 编辑 / 头像 / 人设 / 提示词槽位 / 删除）。
 *
 * 数据语义对齐 Android oracle：
 * - 保存：`OperatorViewModel.saveOperator`（app/.../viewmodel/OperatorViewModel.kt:27-160）——
 *   姓名 trim + 必填 + 同名去重；`Operator(id, name, title, description, gender, location, activity,
 *   emotion, intimacy, privatePrompt, groupPrompt, memoryInjection, userRelation, avatarUri, lmb,
 *   attack, defense, meldPref, activityLevel, voiceName, voiceSpeed, voicePitch)` 原样插入/更新；
 *   新角色 id 形如 `custom_<uuid>`（对齐 `OperatorEditScreen.kt:101` `custom_${UUID.randomUUID()}`）；
 *   保存后 `syncOperatorAvatar` / `syncOperatorName` 同步已有会话，保证聊天页头像/标题同步。
 * - 表单字段映射（Android `OperatorEditScreen.kt:99-125`）：
 *   姓名→name、身份/标签→title、简介→description、私聊人设→privatePrompt、
 *   群聊人设→groupPrompt、关系→userRelation、头像→avatarUri。
 * - 提示词槽位：`operator_prompt_slot_<id>_{private,group}` = 当前槽位（1..3），
 *   `operator_prompt_slot_<id>_{private,group}_<n>` = 槽位内容；DB 的 privatePrompt/groupPrompt
 *   存当前槽位内容（与 Android `OperatorEditScreen.kt:141-146,181-192` 保存逻辑一致）。
 * - 头像：内置数字串 "1".."147" 原样写（Bundle 渲染，T16 裁决）；相册图片经 Swift 方形裁剪
 *   ≤1024 后写临时文件传入 [setAvatarFromFile]，由 [MediaRepository] 以 PICKER 语义入库为
 *   `media/<uuid>.<ext>` 相对 ID（媒体目录 Application Support/media/images）。
 * - 删除：对齐 `OperatorViewModel.deleteOperators`——`deleteOperatorWithPrivateData`（清私聊会话/
 *   记忆/关系/知识库关联 + operators 行），清 settings `dyn_*` / `msg_*` / 槽位键，
 *   并把 id 记入 `deleted_preset_operator_ids`（预设角色不再被 bootstrap 复活）。
 *
 * 线程：DB/媒体在 [workScope]（Default）；回调一律在 [mainScope]（Main）。不导出 Flow / suspend。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "OperatorBridge")
class OperatorBridge internal constructor(
    private val chatRepo: ChatRepository,
    private val settingsRepo: SettingsRepository,
    private val workScope: CoroutineScope,
    private val mainScope: CoroutineScope,
) {
    private val mediaRepository = MediaRepository(MediaStoreIos())

    // ------------------------------------------------------------------
    // 读：编辑表单回填
    // ------------------------------------------------------------------

    fun load(operatorId: String, onResult: (OperatorEditDto?, String?) -> Unit) {
        workScope.launch {
            try {
                val operator = chatRepo.operators.getOperator(operatorId)
                    ?: throw IllegalStateException("角色不存在：$operatorId")
                mainScope.launch { onResult(operator.toEditDto(), null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "读取角色失败") }
            }
        }
    }

    // ------------------------------------------------------------------
    // 写：新建 / 更新
    // ------------------------------------------------------------------

    /**
     * 新建角色。id 忽略 [OperatorDraftDto.id]（由桥生成 `custom_<uuid>`）。
     * 成功回调保存后的完整表单（含实际 id）。
     */
    fun create(draft: OperatorDraftDto, onResult: (OperatorEditDto?, String?) -> Unit) {
        workScope.launch {
            try {
                val id = "custom_" + NSUUID().UUIDString.lowercase().replace("-", "")
                val saved = save(id, draft, isNew = true)
                mainScope.launch { onResult(saved, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "新建角色失败") }
            }
        }
    }

    /** 更新已有角色（id 必填且必须有对应 operators 行）。 */
    fun update(draft: OperatorDraftDto, onResult: (OperatorEditDto?, String?) -> Unit) {
        workScope.launch {
            try {
                if (draft.id.isBlank()) throw IllegalStateException("角色内部编号无效，请重新进入编辑页")
                chatRepo.operators.getOperator(draft.id)
                    ?: throw IllegalStateException("角色不存在：${draft.id}")
                val saved = save(draft.id, draft, isNew = false)
                // Android OperatorViewModel.saveOperator:124-129 —— 已有角色保存后同步会话头像/标题
                runCatching { chatRepo.syncOperatorAvatar(draft.id, saved.avatarId) }
                runCatching { chatRepo.syncOperatorName(draft.id, saved.name) }
                mainScope.launch { onResult(saved, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "保存角色失败") }
            }
        }
    }

    /** 删除角色（测试清理/用户删除共用）：清私聊会话+记忆+关系+knowledge 关联+operators 行。 */
    fun delete(operatorId: String, onResult: (String?, String?) -> Unit) {
        workScope.launch {
            try {
                if (operatorId.isBlank()) throw IllegalStateException("角色内部编号无效")
                chatRepo.deleteOperatorWithPrivateData(operatorId)
                settingsRepo.remove("dyn_$operatorId")
                settingsRepo.remove("msg_$operatorId")
                (1..3).forEach { slot ->
                    settingsRepo.remove(slotKey(operatorId, "private", slot))
                    settingsRepo.remove(slotKey(operatorId, "group", slot))
                }
                settingsRepo.remove(activeSlotKey(operatorId, "private"))
                settingsRepo.remove(activeSlotKey(operatorId, "group"))
                settingsRepo.putStringSet(
                    "deleted_preset_operator_ids",
                    settingsRepo.getStringSet("deleted_preset_operator_ids") + operatorId,
                )
                mainScope.launch { onResult(operatorId, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "删除角色失败") }
            }
        }
    }

    // ------------------------------------------------------------------
    // 头像
    // ------------------------------------------------------------------

    /** 选中内置头像（1..147）：写 operators.avatarUri 数字串 + 同步已有会话头像。返回实际值。 */
    fun setAvatarBuiltin(operatorId: String, avatarNumber: Int, onResult: (String?, String?) -> Unit) {
        workScope.launch {
            try {
                if (avatarNumber !in 1..147) throw IllegalStateException("内置头像编号需在 1..147")
                val operator = chatRepo.operators.getOperator(operatorId)
                    ?: throw IllegalStateException("角色不存在：$operatorId")
                val value = avatarNumber.toString()
                chatRepo.operators.updateOperator(operator.copy(avatarUri = value))
                runCatching { chatRepo.syncOperatorAvatar(operatorId, value) }
                mainScope.launch { onResult(value, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "设置头像失败") }
            }
        }
    }

    /**
     * 从本地图片文件设置头像（Swift 已方形裁剪 ≤1024 并写临时文件）。
     * 媒体入库：PICKER 语义 → `media/<uuid>.<ext>`，随后写 operators.avatarUri 并同步会话头像。
     * 成功返回媒体 ID（失败返回错误，DB 不变）。
     */
    fun setAvatarFromFile(operatorId: String, sourcePath: String, onResult: (String?, String?) -> Unit) {
        workScope.launch {
            try {
                if (sourcePath.isBlank()) throw IllegalStateException("图片路径为空")
                val operator = chatRepo.operators.getOperator(operatorId)
                    ?: throw IllegalStateException("角色不存在：$operatorId")
                val mediaId = mediaRepository.ingest(MediaSource.PICKER, sourcePath)
                if (mediaId == sourcePath || !mediaId.startsWith(MediaRepository.MEDIA_ID_PREFIX)) {
                    throw IllegalStateException("头像图片入库失败（请重试或换一张 JPG/PNG）")
                }
                chatRepo.operators.updateOperator(operator.copy(avatarUri = mediaId))
                runCatching { chatRepo.syncOperatorAvatar(operatorId, mediaId) }
                mainScope.launch { onResult(mediaId, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "头像入库失败") }
            }
        }
    }

    /** 媒体 ID → 本地 file:// URL（头像预览用）；内置数字串/非法/不存在返回 ""。 */
    fun mediaPath(mediaId: String): String =
        runCatching { mediaRepository.resolve(mediaId).orEmpty() }.getOrDefault("")

    // ------------------------------------------------------------------
    // 关系类型（20+，值域与 Android RelationshipType 一致）
    // ------------------------------------------------------------------

    fun relationTypes(): List<RelationTypeDto> = RELATION_TYPES

    // ------------------------------------------------------------------
    // 内部：保存（对齐 OperatorViewModel.saveOperator）
    // ------------------------------------------------------------------

    private suspend fun save(id: String, draft: OperatorDraftDto, isNew: Boolean): OperatorEditDto {
        val cleanName = draft.name.trim()
        if (cleanName.isBlank()) throw IllegalStateException("请输入干员名称")
        val existing = if (isNew) null else chatRepo.operators.getOperator(id)
        val duplicate = chatRepo.operators.getAllOperatorsSync()
            .firstOrNull { it.id != id && it.name.trim().equals(cleanName, ignoreCase = true) }
        if (duplicate != null) throw IllegalStateException("已存在名为“$cleanName”的干员，请使用其他名称")

        // 槽位读写（Android OperatorEditScreen.kt:141-146,181-192）
        val privateActive = draft.privateActiveSlot.coerceIn(1, 3)
        val groupActive = draft.groupActiveSlot.coerceIn(1, 3)
        val privateSlots = listOf(draft.privateSlot1, draft.privateSlot2, draft.privateSlot3)
        val groupSlots = listOf(draft.groupSlot1, draft.groupSlot2, draft.groupSlot3)
        // 槽位 1 为空时回填旧 DB 值（Android：slot 1 fallback = operator.privatePrompt）
        val privateContent = privateSlots[privateActive - 1].ifBlank {
            existing?.privatePrompt.orEmpty()
        }
        val groupContent = groupSlots[groupActive - 1].ifBlank {
            existing?.groupPrompt.orEmpty()
        }
        privateSlots.forEachIndexed { index, content ->
            val value = content.ifBlank { if (index + 1 == privateActive) privateContent else "" }
            settingsRepo.putString(slotKey(id, "private", index + 1), value)
        }
        groupSlots.forEachIndexed { index, content ->
            val value = content.ifBlank { if (index + 1 == groupActive) groupContent else "" }
            settingsRepo.putString(slotKey(id, "group", index + 1), value)
        }
        settingsRepo.putInt(activeSlotKey(id, "private"), privateActive)
        settingsRepo.putInt(activeSlotKey(id, "group"), groupActive)

        val op = Operator(
            id = id,
            name = cleanName,
            title = draft.tags.trim(),
            description = draft.intro.trim().ifBlank { "$cleanName，罗德岛干员" },
            gender = draft.gender.trim().ifBlank { existing?.gender.orEmpty() },
            avatarUri = draft.avatarId.trim().ifBlank { existing?.avatarUri.orEmpty() },
            location = existing?.location ?: "宿舍",
            activity = existing?.activity ?: "休息",
            emotion = existing?.emotion ?: "平静",
            intimacy = existing?.intimacy ?: 0,
            privatePrompt = privateContent,
            groupPrompt = groupContent,
            memoryInjection = existing?.memoryInjection.orEmpty(),
            userRelation = draft.relationType.trim().ifBlank { existing?.userRelation.orEmpty() },
            lmb = existing?.lmb ?: 10000,
            attack = existing?.attack ?: 0.5f,
            defense = existing?.defense ?: 0.5f,
            meldPref = existing?.meldPref ?: "medium",
            activityLevel = existing?.activityLevel ?: 0.5f,
            voiceName = existing?.voiceName.orEmpty(),
            voiceSpeed = existing?.voiceSpeed.orEmpty(),
            voicePitch = existing?.voicePitch.orEmpty(),
        )
        if (isNew) chatRepo.operators.insertOperator(op) else chatRepo.operators.updateOperator(op)
        val saved = chatRepo.operators.getOperator(id)
            ?: throw IllegalStateException("角色写入后读取不到")
        return saved.toEditDto()
    }

    private fun Operator.toEditDto(): OperatorEditDto {
        val privateActive = settingsRepo.getInt(activeSlotKey(id, "private"), 1).coerceIn(1, 3)
        val groupActive = settingsRepo.getInt(activeSlotKey(id, "group"), 1).coerceIn(1, 3)
        val privateSlots = (1..3).map { slot ->
            settingsRepo.getString(slotKey(id, "private", slot), "")
                .ifBlank { if (slot == 1) privatePrompt else "" }
        }
        val groupSlots = (1..3).map { slot ->
            settingsRepo.getString(slotKey(id, "group", slot), "")
                .ifBlank { if (slot == 1) groupPrompt else "" }
        }
        return OperatorEditDto(
            id = id,
            name = name,
            tags = title,
            persona = privateSlots[privateActive - 1],
            intro = description,
            avatarId = avatarUri,
            gender = gender,
            relationType = userRelation,
            groupPersona = groupSlots[groupActive - 1],
            privateSlot1 = privateSlots[0],
            privateSlot2 = privateSlots[1],
            privateSlot3 = privateSlots[2],
            groupSlot1 = groupSlots[0],
            groupSlot2 = groupSlots[1],
            groupSlot3 = groupSlots[2],
            privateActiveSlot = privateActive,
            groupActiveSlot = groupActive,
        )
    }

    private fun slotKey(operatorId: String, type: String, slot: Int) =
        "operator_prompt_slot_${operatorId}_${type}_$slot"

    private fun activeSlotKey(operatorId: String, type: String) =
        "operator_prompt_slot_${operatorId}_$type"

    companion object {
        /** 值域与 Android `RelationshipType` 枚举一致（commonMain model/Enums.kt:15-24 + Android UI 标签）。 */
        internal val RELATION_TYPES: List<RelationTypeDto> = listOf(
            RelationTypeDto("BIG_SISTER", "姐姐"),
            RelationTypeDto("LITTLE_SISTER", "妹妹"),
            RelationTypeDto("BIG_BROTHER", "哥哥"),
            RelationTypeDto("LITTLE_BROTHER", "弟弟"),
            RelationTypeDto("MOTHER", "母亲"),
            RelationTypeDto("FATHER", "父亲"),
            RelationTypeDto("DAUGHTER", "女儿"),
            RelationTypeDto("SON", "儿子"),
            RelationTypeDto("GUARDIAN", "监护人"),
            RelationTypeDto("BOSS", "上司"),
            RelationTypeDto("SUBORDINATE", "下属"),
            RelationTypeDto("CAPTAIN", "队长"),
            RelationTypeDto("MEMBER", "队员"),
            RelationTypeDto("MENTOR", "导师"),
            RelationTypeDto("STUDENT", "学生"),
            RelationTypeDto("CLOSE_FRIEND", "挚友"),
            RelationTypeDto("FRIEND", "朋友"),
            RelationTypeDto("COMRADE", "战友"),
            RelationTypeDto("TEAMMATE", "队友"),
            RelationTypeDto("RIVAL", "对手"),
            RelationTypeDto("LOVE_RIVAL", "情敌"),
            RelationTypeDto("CRUSH", "暗恋对象"),
            RelationTypeDto("LOVER", "恋人"),
            RelationTypeDto("FAMILY", "家人"),
        )
    }
}

/**
 * 编辑表单 DTO（operators 表一行 + prompt 槽位）。
 *
 * 注意：简介字段命名 [intro] 而非 description —— Kotlin/Native 导出 ObjC 时与
 * `NSObject.description` 冲突（T36 RoleDto 同款踩坑记录）。
 *
 * 字段 ↔ DB/settings 映射：
 * - [tags] → operators.title（Android 原版「身份」；本波 UI 以逗号分隔的标签编辑）
 * - [persona] → operators.privatePrompt（当前私聊人设槽位内容）
 * - [groupPersona] → operators.groupPrompt（当前群聊人设槽位内容）
 * - [relationType] → operators.userRelation
 * - privateSlot1..3 / groupSlot1..3 → `operator_prompt_slot_<id>_{private,group}_<n>`
 * - privateActiveSlot / groupActiveSlot → `operator_prompt_slot_<id>_{private,group}`
 */
class OperatorEditDto(
    val id: String,
    val name: String,
    val tags: String,
    val persona: String,
    val intro: String,
    val avatarId: String,
    val gender: String,
    val relationType: String,
    val groupPersona: String,
    val privateSlot1: String,
    val privateSlot2: String,
    val privateSlot3: String,
    val groupSlot1: String,
    val groupSlot2: String,
    val groupSlot3: String,
    val privateActiveSlot: Int,
    val groupActiveSlot: Int,
)

/**
 * 保存草稿 DTO（Swift 构造后传入 create/update）。
 *
 * - [id]：更新时必填；新建时忽略（桥生成 `custom_<uuid>`）。
 * - [tags]：逗号分隔标签 → operators.title。
 * - [avatarId]：内置数字串 "1".."147" 或媒体 ID（`media/<uuid>.<ext>`）；空 = 保留旧值。
 * - 槽位 1 为空时回退旧 DB 值（Android 同语义）。
 */
class OperatorDraftDto(
    val id: String,
    val name: String,
    val tags: String,
    val persona: String,
    val intro: String,
    val avatarId: String,
    val gender: String,
    val relationType: String,
    val groupPersona: String,
    val privateSlot1: String,
    val privateSlot2: String,
    val privateSlot3: String,
    val groupSlot1: String,
    val groupSlot2: String,
    val groupSlot3: String,
    val privateActiveSlot: Int,
    val groupActiveSlot: Int,
)

/** 关系类型（code = Android RelationshipType 枚举名；label = Android UI 中文标签）。 */
class RelationTypeDto(
    val code: String,
    val label: String,
)
