package com.rhodes.privatechat.shared.iosbridge

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.db.DatabaseWrapper
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.russhwolf.settings.NSUserDefaultsSettings
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSUserDefaults
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T45 iOS 路径测试（`:shared:iosSimulatorArm64Test`，模拟器真实运行）：
 * 验证 `PrivatePromptWiring` 的真实依赖装配 —— 预置 L3 记忆 + 自定义模板引用
 * `{{LONG_TERM_IMPRESSION}}` 后，`PrivatePromptAssembler.build` 输出必须包含：
 * - 预置记忆原文（证明 MemoryV2 稳定印象 provider 已接线）；
 * - 角色名 / 用户名（证明人设与用户档案 provider 已接线）；
 * - `【本轮参考资料】`/`【用户本轮消息】` 四层提示词结构。
 *
 * 不依赖网络/模型供应商（只是 build，不调用模型）。
 */
class T45PromptWiringIosTest {

    private var driver: NativeSqliteDriver? = null
    private val suiteName = "t45_wiring_test_${getpid()}_${kotlin.random.Random.nextInt(100000)}"

    @AfterTest
    fun tearDown() {
        NSUserDefaults(suiteName = suiteName).removePersistentDomainForName(suiteName)
        driver?.close()
        driver = null
    }

    private fun newEnvironment(): Triple<ChatRepository, DatabaseWrapper, SettingsRepository> {
        val dbName = "t45_wiring_${getpid()}_${kotlin.random.Random.nextInt(100000)}.db"
        val created = NativeSqliteDriver(RhodesDatabase.Schema, dbName)
        driver = created
        RhodesDatabase.Schema.create(created)
        val database = RhodesDatabase(created)
        val wrapper = DatabaseWrapper().also { it.injectForTest(database) }
        val settings = SettingsRepository(NSUserDefaultsSettings(NSUserDefaults(suiteName = suiteName)))
        val repo = ChatRepository(wrapper, settings)
        return Triple(repo, wrapper, settings)
    }

    @Test
    fun promptContainsSeededL3MemoryAndProfile() = runBlocking {
        val (repo, wrapper, settings) = newEnvironment()
        repo.operators.ensurePresetOperators()
        val operator = requireNotNull(repo.operators.getAllOperatorsSync().firstOrNull()) { "预设干员缺失" }
        val session = requireNotNull(repo.getOrCreateSession(operator.id, operator.name, operator.avatarUri)) {
            "创建会话失败"
        }
        settings.userName = "T45测试用户"
        val wiring = PrivatePromptWiring(repo, wrapper, settings)

        val token = "T45-IOSTEST-L3-${getpid()}"
        val seedId = wiring.debugSeedL3Memory(session, "用户与${operator.name} 的约定：暗号是 $token。")
        assertTrue(seedId > 0L, "L3 记忆写入失败 id=$seedId")

        // 默认模板（online）不内联 LONG_TERM_IMPRESSION（oracle 同款），用自定义模板验证装配管线。
        settings.saveCustomPromptTemplate(
            "private", session.mode,
            "你是{{OPERATOR_NAME}}，正在以第一人称回应{{USER_NAME}}。\n" +
                "【你记得的相关经历】\n长期印象：{{LONG_TERM_IMPRESSION}}\n{{MEMORY_V2_CONTEXT}}\n",
            0,
        )
        val prompt = wiring.debugBuildPrompt(session, "T45 提示词接线自检：你还记得我们的约定吗？")
        val text = prompt.joinToString("\n") { it.content }
        assertTrue(text.length > 1_000, "提示词过短：${text.length}")
        assertTrue(text.contains(token), "提示词缺少预置 L3 记忆 token")
        assertTrue(text.contains(operator.name), "提示词缺少角色名")
        assertTrue(text.contains("T45测试用户"), "提示词缺少用户档案昵称")
        assertTrue(text.contains("【本轮参考资料】") || text.contains("【资料开始】"), "提示词缺少参考资料边界块")
        assertTrue(text.contains("【用户本轮消息】"), "提示词缺少用户本轮消息块")
        println("T45-IOSTEST prompt chars=${text.length} hasL3=true memorySection=" +
            text.substringAfter("长期印象", "").substringBefore("【").take(120).replace("\n", " "))
    }

    @Test
    fun wiringConstructsRealMemoryAndKnowledgeServices() = runBlocking {
        val (repo, wrapper, settings) = newEnvironment()
        val wiring = PrivatePromptWiring(repo, wrapper, settings)
        assertTrue(wiring.statusLine.contains("assembler=PrivatePromptAssembler"), wiring.statusLine)
        assertTrue(wiring.statusLine.contains("memoryV2=MemoryV2Pipeline"), wiring.statusLine)
        assertTrue(wiring.statusLine.contains("knowledge=KnowledgeBaseService"), wiring.statusLine)
        // 无会话/无消息时提取应安全返回 false（不抛异常、不产生副作用）
        assertTrue(!wiring.ingestPrivateChatAfterReply("not-a-session"), "未知会话应安全返回 false")
    }
}
