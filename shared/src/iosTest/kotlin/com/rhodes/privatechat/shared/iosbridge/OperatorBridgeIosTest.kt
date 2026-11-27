package com.rhodes.privatechat.shared.iosbridge

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.db.DatabaseWrapper
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.russhwolf.settings.NSUserDefaultsSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSUserDefaults
import platform.posix.getpid
import kotlin.coroutines.resume
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 已有角色编辑 → 保存 → 重新读取的真实 SQLite 回归。 */
class OperatorBridgeIosTest {

    private var driver: NativeSqliteDriver? = null
    private val suiteName = "operator_edit_test_${getpid()}_${kotlin.random.Random.nextInt(100000)}"

    @AfterTest
    fun tearDown() {
        NSUserDefaults(suiteName = suiteName).removePersistentDomainForName(suiteName)
        driver?.close()
        driver = null
    }

    private suspend fun CoroutineScope.awaitEdit(
        call: (((OperatorEditDto?, String?) -> Unit) -> Unit),
    ): Pair<OperatorEditDto?, String?> = suspendCancellableCoroutine { continuation ->
        call { dto, error -> continuation.resume(dto to error) }
    }

    @Test
    fun updatingExistingOperatorPersistsAndReadsBack() = runBlocking {
        val dbName = "operator_edit_${getpid()}_${kotlin.random.Random.nextInt(100000)}.db"
        val created = NativeSqliteDriver(RhodesDatabase.Schema, dbName)
        driver = created
        RhodesDatabase.Schema.create(created)
        val wrapper = DatabaseWrapper().also { it.injectForTest(RhodesDatabase(created)) }
        val settings = SettingsRepository(NSUserDefaultsSettings(NSUserDefaults(suiteName = suiteName)))
        val repo = ChatRepository(wrapper, settings)
        repo.operators.ensurePresetOperators()
        val original = requireNotNull(repo.operators.getAllOperatorsSync().firstOrNull())
        val bridge = OperatorBridge(repo, settings, this, this)

        val loaded = awaitEdit { bridge.load(original.id, it) }
        assertNull(loaded.second)
        val originalItem = requireNotNull(loaded.first)
        val draft = OperatorDraftDto(
            id = originalItem.id,
            name = originalItem.name + "（已修改）",
            tags = originalItem.tags,
            persona = "更新后的私聊人设：回答简短、先说结论。",
            intro = "更新后的角色简介",
            avatarId = originalItem.avatarId,
            gender = originalItem.gender,
            relationType = originalItem.relationType,
            groupPersona = "更新后的群聊人设",
            privateSlot1 = "更新后的私聊人设：回答简短、先说结论。",
            privateSlot2 = "第二槽位也同步保存",
            privateSlot3 = originalItem.privateSlot3,
            groupSlot1 = "更新后的群聊人设",
            groupSlot2 = originalItem.groupSlot2,
            groupSlot3 = originalItem.groupSlot3,
            privateActiveSlot = 1,
            groupActiveSlot = originalItem.groupActiveSlot,
        )

        val saved = awaitEdit { bridge.update(draft, it) }
        assertNull(saved.second, "保存已有角色不应失败")
        assertEquals(draft.name, saved.first?.name)
        assertEquals(draft.persona, saved.first?.persona)

        val reread = awaitEdit { bridge.load(original.id, it) }
        assertNull(reread.second)
        val item = requireNotNull(reread.first)
        assertEquals(draft.name, item.name)
        assertEquals(draft.persona, item.persona)
        assertEquals(draft.intro, item.intro)
        assertEquals("第二槽位也同步保存", item.privateSlot2)
        assertEquals("更新后的群聊人设", item.groupPersona)

        val dbOperator = requireNotNull(repo.operators.getOperator(original.id))
        assertEquals(draft.name, dbOperator.name)
        assertEquals(draft.persona, dbOperator.privatePrompt)
        assertTrue(settings.getString("operator_prompt_slot_${original.id}_private_2", "").contains("第二槽位"))
    }
}
