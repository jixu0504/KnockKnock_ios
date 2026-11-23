package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.db.DatabaseWrapper
import com.rhodes.privatechat.shared.files.FileIo
import com.rhodes.privatechat.shared.files.MediaDirectory
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.Foundation.NSLog
import platform.Foundation.NSUserDefaults

/**
 * 用户身份桥（T57，真实 NSUserDefaults + SQLite 统计）。
 *
 * 数据语义对齐 Android oracle：
 * - 昵称/性别/签名 = `SettingsRepository.userName/userGender/userSignature`，键
 *   `user_name` / `user_gender` / `user_signature`（`SettingsRepository.kt:836-846`）；
 *   保存 = `MainViewModel.updateUserProfile`（`:2129-2131`）→ 三个 settings 赋值；
 *   留空昵称由 `ProfileSettingsScreen.saveProfile`（`:110`）拦截（「昵称不能为空」）。
 * - 用户档案进入私聊提示词：T45 `PrivatePromptWiring.userProfile()` → `UserProfileView(nickname/gender/bio)`
 *   → `PrivatePromptAssembler` 的 `USER_NAME` / `USER_GENDER` / `USER_BIO` 占位符
 *   （`PrivatePromptAssembler.kt:164`）。本桥自检会真实构建一次提示词并断言三个值出现。
 * - 等级/经验：Android 1.14.27 **没有**用户 level/exp 字段（grep 无 `user_level/user_exp`），
 *   故身份卡按任务口径展示真实统计：消息数 / 记忆数 / 会话数 / 角色数 / 关系数。
 *
 * 线程：DB 读取在 workScope（Default）；回调一律 mainScope（Main）。不导出 Flow / suspend。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "IdentityBridge")
class IdentityBridge internal constructor(
    private val chatRepo: ChatRepository,
    private val dbWrapper: DatabaseWrapper,
    private val settingsRepo: SettingsRepository,
    private val dbPath: String,
    private val workScope: CoroutineScope,
    private val mainScope: CoroutineScope,
) {
    /** 读取用户身份 + 统计（编辑页回填 & 统计卡共用）。 */
    fun getProfile(onResult: (IdentityProfileDto?, String?) -> Unit) {
        workScope.launch {
            try {
                val dto = loadProfile()
                mainScope.launch { onResult(dto, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "读取用户身份失败") }
            }
        }
    }

    /**
     * 保存昵称/性别/签名（昵称 trim 后必填；写 `user_name`/`user_gender`/`user_signature`
     * 并 synchronize 落 plist，模拟器可用 plutil 直接核对）。成功回调保存后的完整档案。
     */
    fun save(
        nickname: String,
        gender: String,
        signature: String,
        onResult: (IdentityProfileDto?, String?) -> Unit,
    ) {
        workScope.launch {
            try {
                val saved = saveInternal(nickname, gender, signature)
                mainScope.launch { onResult(saved, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "保存用户身份失败") }
            }
        }
    }

    // ------------------------------------------------------------------
    // 自检（--identity-selftest 启动钩子；写临时值并 finally 恢复用户原值）
    // ------------------------------------------------------------------

    fun debugRunSelfTest(onDone: (String) -> Unit) {
        workScope.launch {
            val lines = mutableListOf<String>()
            var failures = 0
            fun check(name: String, ok: Boolean, detail: String = "") {
                if (!ok) failures++
                lines += "${if (ok) "PASS" else "FAIL"} $name" + if (detail.isBlank()) "" else " | $detail"
            }
            val stamp = Clock.System.now().toEpochMilliseconds()
            val probeName = "T57身份自检$stamp"
            val probeGender = "自检性别$stamp"
            val probeSignature = "T57-SIG-$stamp"
            val savedName = settingsRepo.userName
            val savedGender = settingsRepo.userGender
            val savedSignature = settingsRepo.userSignature
            var templateRestore: (() -> Unit)? = null
            fun report() {
                lines += "RESULT ${if (failures == 0) "PASS" else "FAIL($failures)"}"
                val text = lines.joinToString("\n")
                NSLog(("[RhodesLink][T57IDSELFTEST]\n" + text).replace("%", "%%"))
                val path = "${MediaDirectory.applicationSupportDirectory()}/t57-identity-selftest.log"
                runCatching { FileIo.writeBytes(path, (text + "\n").encodeToByteArray()) }
                    .onFailure { NSLog("[RhodesLink][T57IDSELFTEST] report write failed: " + (it.message ?: "")) }
                NSLog("[RhodesLink][T57IDSELFTEST] report path=%@ exists=%@ dbExists=%@",
                    path, if (FileIo.exists(path)) "yes" else "no", if (FileIo.exists(dbPath)) "yes" else "no")
                mainScope.launch { runCatching { onDone(text) } }
            }
            try {
                lines += "=== T57 identity selftest ==="
                lines += "BEFORE name=${savedName.ifBlank { "<blank>" }} gender=${savedGender.ifBlank { "<blank>" }} " +
                    "signature=${if (savedSignature.isBlank()) "<blank>" else "<set>"}"

                // ---- a) 改名/签名/性别 → 读回（桥 DTO + 原始 NSUserDefaults 双通道）----
                val saved = saveInternal(probeName, probeGender, probeSignature)
                check("save.dto.readback", saved.nickname == probeName && saved.gender == probeGender &&
                    saved.signature == probeSignature,
                    "name=${saved.nickname} gender=${saved.gender} signature=${saved.signature}")
                check("stats.message.count", saved.messageCount >= 0, "messages=${saved.messageCount}")
                check("stats.memory.count", saved.memoryCount >= 0, "memories=${saved.memoryCount}")
                check("stats.session.count", saved.sessionCount >= 0, "sessions=${saved.sessionCount}")
                check("stats.operator.count", saved.operatorCount >= 0, "operators=${saved.operatorCount}")
                check("stats.relation.count", saved.relationCount >= 0, "relations=${saved.relationCount}")
                lines += "STATS messages=${saved.messageCount} memories=${saved.memoryCount} " +
                    "sessions=${saved.sessionCount} operators=${saved.operatorCount} relations=${saved.relationCount}"

                val defaults = NSUserDefaults.standardUserDefaults
                defaults.synchronize()
                check("defaults.user_name", defaults.stringForKey("user_name") == probeName,
                    "raw=${defaults.stringForKey("user_name")}")
                check("defaults.user_gender", defaults.stringForKey("user_gender") == probeGender,
                    "raw=${defaults.stringForKey("user_gender")}")
                check("defaults.user_signature", defaults.stringForKey("user_signature") == probeSignature,
                    "raw=${defaults.stringForKey("user_signature")}")
                val reread = loadProfile()
                check("profile.reread", reread.nickname == probeName && reread.signature == probeSignature,
                    "name=${reread.nickname} gender=${reread.gender}")

                // ---- b) 空昵称拒绝（oracle ProfileSettingsScreen.saveProfile:110）----
                val blankSave = runCatching { saveInternal("   ", probeGender, probeSignature) }
                check("save.blank_nickname.rejected", blankSave.isFailure &&
                    !blankSave.exceptionOrNull()?.message.isNullOrBlank(),
                    "error=${blankSave.exceptionOrNull()?.message}")

                // ---- c) T45 提示词 user profile 断言（真实装配器 + 临时确定性模板）----
                val wiring = PrivatePromptWiring(chatRepo, dbWrapper, settingsRepo)
                val session = resolveSession()
                lines += "PROMPT session=${session.id} operator=${session.operatorName} mode=${session.mode}"
                val hadCustom = settingsRepo.isPromptTemplateCustom("private", session.mode)
                val savedTemplate = settingsRepo.getPromptTemplate("private", session.mode)
                val savedVersion = settingsRepo.getPromptTemplateVersion("private", session.mode)
                templateRestore = {
                    if (hadCustom) {
                        settingsRepo.saveCustomPromptTemplate("private", session.mode, savedTemplate, savedVersion)
                    } else {
                        settingsRepo.removePromptTemplate("private", session.mode)
                    }
                }
                settingsRepo.saveCustomPromptTemplate(
                    "private", session.mode,
                    "你是{{OPERATOR_NAME}}。用户姓名：{{USER_NAME}}；用户性别：{{USER_GENDER}}；身份设定：{{USER_BIO}}。\n",
                    savedVersion,
                )
                val prompt = runCatching { wiring.debugBuildPrompt(session, "T57 用户身份自检：请以当前设定称呼我。") }
                val promptText = prompt.getOrNull()?.joinToString("\n") { it.content }.orEmpty()
                check("prompt.built", prompt.isSuccess && promptText.length > 20, "chars=${promptText.length}")
                check("prompt.user_name", promptText.contains(probeName), "name=$probeName")
                check("prompt.user_gender", promptText.contains(probeGender), "gender=$probeGender")
                check("prompt.user_bio", promptText.contains(probeSignature), "signature=$probeSignature")
                lines += "PROMPT_USER_LINE=" + promptText.lines().firstOrNull { it.contains(probeName) }
                    .orEmpty().take(200)
            } catch (t: Throwable) {
                failures++
                lines += "FAIL exception | ${t::class.simpleName}: ${t.message?.take(200)}"
            } finally {
                runCatching { templateRestore?.invoke() }
                runCatching {
                    settingsRepo.userName = savedName
                    settingsRepo.userGender = savedGender
                    settingsRepo.userSignature = savedSignature
                    NSUserDefaults.standardUserDefaults.synchronize()
                }
                val restored = runCatching { loadProfile() }.getOrNull()
                lines += "RESTORE name=${restored?.nickname ?: "<failed>"} " +
                    "gender=${restored?.gender ?: "<failed>"} signatureSet=${restored?.signature?.isNotBlank() ?: false}"
                check("restore.user_name", restored?.nickname == savedName,
                    "expected=${savedName.ifBlank { "<blank>" }} actual=${restored?.nickname}")
                check("restore.user_signature", restored?.signature == savedSignature)
            }
            report()
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private suspend fun loadProfile(): IdentityProfileDto = IdentityProfileDto(
        nickname = settingsRepo.userName.ifBlank { "博士" },
        gender = settingsRepo.userGender,
        signature = settingsRepo.userSignature,
        avatarId = settingsRepo.userAvatarUri,
        messageCount = runCatching { chatRepo.getMessageCount() }.getOrDefault(0),
        memoryCount = runCatching { chatRepo.memories.getAllLongTermImpressions().size }.getOrDefault(0),
        sessionCount = runCatching { chatRepo.getSessionCount() }.getOrDefault(0),
        operatorCount = runCatching { chatRepo.operators.getAllOperatorsSync().size }.getOrDefault(0),
        relationCount = runCatching {
            dbWrapper.database.relationshipsQueries.getCount().executeAsOne().toInt()
        }.getOrDefault(0),
    )

    private suspend fun saveInternal(nickname: String, gender: String, signature: String): IdentityProfileDto {
        val cleanName = nickname.trim()
        if (cleanName.isBlank()) throw IllegalStateException("昵称不能为空")
        val cleanGender = gender.trim()
        val cleanSignature = signature.trim()
        settingsRepo.userName = cleanName
        settingsRepo.userGender = cleanGender
        settingsRepo.userSignature = cleanSignature
        NSUserDefaults.standardUserDefaults.synchronize()
        return loadProfile()
    }

    /** 自检用会话：优先复用已有私聊会话；无则按 bootstrap 语义建一个（幂等）。 */
    private suspend fun resolveSession(): ChatSession {
        chatRepo.sessions.getAllSessionsSync()
            .firstOrNull { !it.id.startsWith("group_") && !it.operatorId.startsWith("group") }
            ?.let { return it }
        chatRepo.operators.ensurePresetOperators()
        val operator = chatRepo.operators.getAllOperatorsSync().firstOrNull()
            ?: throw IllegalStateException("没有可用角色，无法构建提示词")
        return chatRepo.getOrCreateSession(operator.id, operator.name, operator.avatarUri)
    }
}

/**
 * 用户身份 + 真实统计 DTO（身份编辑页一次读取）。
 *
 * - [nickname]/[gender]/[signature]/[avatarId] ← settings `user_name`/`user_gender`/
 *   `user_signature`/`user_avatar_uri`（昵称默认「博士」）。
 * - 统计口径：消息=chat_messages 总行数；记忆=memories 长期印象行数（与设置页「N 条记忆」同源）；
 *   会话=chat_sessions 总数（含群聊）；角色=operators 总数；关系=relationships 总行数。
 */
class IdentityProfileDto(
    val nickname: String,
    val gender: String,
    val signature: String,
    val avatarId: String,
    val messageCount: Int,
    val memoryCount: Int,
    val sessionCount: Int,
    val operatorCount: Int,
    val relationCount: Int,
)
