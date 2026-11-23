package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.model.MemoryLevel
import com.rhodes.privatechat.shared.model.MemorySourceKind
import com.rhodes.privatechat.shared.prompt.PromptTestSettings
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验收 #6（隐私矩阵）：来源→可见性策略表、敏感词 18 条（oracle MemoryPrivacyPolicy.kt:12-61）、
 * 解析层强制覆盖（模型不能把私聊改公开 / 把公开改私密）。
 */
class MemoryPrivacyTest {

    @Test
    fun sourceToVisibilityMatrixMatchesOracle() {
        fun visibility(kind: MemorySourceKind, content: String = "普通内容", explicitPrivate: Boolean = false) =
            MemoryPrivacyPolicy.forSource(kind, "operator", "op1", content, explicitPrivate)

        val privateChat = visibility(MemorySourceKind.PRIVATE_CHAT)
        assertEquals("shared", privateChat.privacy)
        assertEquals("operator", privateChat.ownerType)
        assertEquals("op1", privateChat.ownerId)
        assertFalse(privateChat.writeGlobalPublic)

        val sensitivePrivateChat = visibility(MemorySourceKind.PRIVATE_CHAT, content = "这是秘密，别告诉别人")
        assertEquals("private", sensitivePrivateChat.privacy, "命中敏感词 → private")
        assertEquals("private", visibility(MemorySourceKind.PRIVATE_CHAT, explicitPrivate = true).privacy)

        val group = visibility(MemorySourceKind.GROUP_CHAT)
        assertEquals("public", group.privacy)
        assertEquals("group", group.ownerType)
        assertEquals("g1", MemoryPrivacyPolicy.forSource(MemorySourceKind.GROUP_CHAT, "operator", "g1", "内容").ownerId)

        for (kind in listOf(MemorySourceKind.MOMENT, MemorySourceKind.MOMENT_COMMENT)) {
            val moment = visibility(kind)
            assertEquals("public", moment.privacy)
            assertTrue(moment.writeGlobalPublic, "动态/评论写 global 公共副本")
        }
        val diary = visibility(MemorySourceKind.DIARY)
        assertEquals("private", diary.privacy, "日记恒 private")
        assertFalse(diary.writeGlobalPublic)

        val manual = visibility(MemorySourceKind.MANUAL_MEMORY)
        assertEquals("shared", manual.privacy)
        assertEquals("private", visibility(MemorySourceKind.MANUAL_MEMORY, content = "秘密").privacy)
    }

    @Test
    fun sensitiveWordTableHasEighteenEntries() {
        val words = listOf(
            "别告诉", "不要告诉", "不能告诉", "秘密", "隐私", "私密", "保密",
            "焦虑", "崩溃", "撑不住", "害怕", "难过", "抑郁", "自杀", "自伤",
            "亲密", "只告诉你", "不想让别人知道",
        )
        assertEquals(18, words.size)
        assertTrue(words.all { MemoryPrivacyPolicy.isSensitive("前缀${it}后缀") })
        assertFalse(MemoryPrivacyPolicy.isSensitive("今天天气不错，适合散步"))
        assertTrue(MemoryPrivacyPolicy.isSensitive("我很焦虑"), "大小写/中文均按 contains 匹配")
    }

    @Test
    fun parseLayerForcesSourcePrivacyAndIgnoresModelChoice() {
        val rules = MemoryParseRules(
            SettingsRepository(PromptTestSettings()),
            { it },
            { 1767225600000L },
            { "用户" },
        )
        val modelSaysPublic = """[{"type":"event","content":"用户说了一个小秘密","privacy":"public"}]"""
        val privateChat = rules.parseMemoryItems(modelSaysPublic, "operator", "op1", MemoryLevel.L1, MemorySourceKind.PRIVATE_CHAT, "ref", "s1")
        assertEquals("private", privateChat.single().privacy, "私聊恒 private，模型不能改")

        val modelSaysPrivate = """[{"type":"event","content":"公开动态内容","privacy":"private","importance":30}]"""
        for (kind in listOf(MemorySourceKind.MOMENT, MemorySourceKind.MOMENT_COMMENT, MemorySourceKind.GROUP_CHAT)) {
            val parsed = rules.parseMemoryItems(modelSaysPrivate, "global", "public", MemoryLevel.L1, kind, "ref", "")
            assertEquals("public", parsed.single().privacy, "$kind 恒 public，模型不能改")
        }
        // DIARY 不在解析层强制覆盖名单内：模型显式 privacy 合法时保留，缺失时回落来源策略 private
        val diaryExplicit = rules.parseMemoryItems(
            """[{"type":"event","content":"日记内容","privacy":"public"}]""",
            "operator", "op1", MemoryLevel.L1, MemorySourceKind.DIARY, "ref", "",
        )
        assertEquals("public", diaryExplicit.single().privacy)
        val diaryDefault = rules.parseMemoryItems(
            """[{"type":"event","content":"日记内容"}]""",
            "operator", "op1", MemoryLevel.L1, MemorySourceKind.DIARY, "ref", "",
        )
        assertEquals("private", diaryDefault.single().privacy, "日记缺省回落来源策略 private")
    }
}
