package com.rhodes.privatechat.shared.prompt

import com.rhodes.privatechat.shared.settings.AgentProfiles
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 金样 diff 门禁（规格卡验收 #1，Tier-1 47 件）。
 *
 * 每件断言：(a) 模板字面量与 `ios-port/golden/prompts/raw/` 金样逐字节比对——
 * 严格比对（两侧对称去掉抽取脚本保留的首/尾空行）；`$X` 组合模板在比对前于金样侧
 * 解析为迁移后的引用件文本（引用件自身另有严格比对，链式锁定）。
 * 白名单仅开放第③类「行首空白」（读取 allowlist.json，不允许测试内硬编码豁免）。
 * (b) 固定 Clock + fixture 替换表渲染后无残留 token。
 * 金样文件缺失 fail-fast：报告完整缺件清单。
 *
 * strict/canonical/rawAnchor 通过件数有明确断言，防止白名单被静默放宽。
 */
class GoldenPromptTest {

    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private val fixedInstant: Instant = Instant.parse("2026-06-15T09:30:00Z") // 北京时间 2026-06-15 17:30 下午

    private class GoldenItem(
        val id: String,
        val raw: String,
        val refs: Map<String, String> = emptyMap(),
        val rawAnchor: Boolean = false,
        val actual: () -> String,
    )

    private fun profile(id: String): String = AgentProfiles.byId(id).prompt

    private val items: List<GoldenItem> = listOf(
        // ---- PromptTemplates（22 件）----
        GoldenItem("PromptTemplates/HUMAN_REALISM", "PromptTemplates_L25_HUMAN_REALISM.md") { PromptTemplates.HUMAN_REALISM },
        GoldenItem("PromptTemplates/TAG_PRIVATE_ONLINE", "PromptTemplates_L38_TAG_PRIVATE_ONLINE.md", mapOf("HUMAN_REALISM" to PromptTemplates.HUMAN_REALISM)) { PromptTemplates.get("private", "online") },
        GoldenItem("PromptTemplates/TAG_PRIVATE_OFFLINE", "PromptTemplates_L54_TAG_PRIVATE_OFFLINE.md", mapOf("HUMAN_REALISM" to PromptTemplates.HUMAN_REALISM)) { PromptTemplates.get("private", "offline") },
        GoldenItem("PromptTemplates/TAG_PRIVATE_DIRECTOR", "PromptTemplates_L68_TAG_PRIVATE_DIRECTOR.md", mapOf("HUMAN_REALISM" to PromptTemplates.HUMAN_REALISM)) { PromptTemplates.get("private", "director") },
        GoldenItem("PromptTemplates/TAG_GROUP_ONLINE", "PromptTemplates_L81_TAG_GROUP_ONLINE.md", mapOf("HUMAN_REALISM" to PromptTemplates.HUMAN_REALISM)) { PromptTemplates.get("group", "online") },
        GoldenItem("PromptTemplates/TAG_GROUP_OFFLINE", "PromptTemplates_L95_TAG_GROUP_OFFLINE.md", mapOf("HUMAN_REALISM" to PromptTemplates.HUMAN_REALISM)) { PromptTemplates.get("group", "offline") },
        GoldenItem("PromptTemplates/TAG_GROUP_DIRECTOR", "PromptTemplates_L109_TAG_GROUP_DIRECTOR.md", mapOf("HUMAN_REALISM" to PromptTemplates.HUMAN_REALISM)) { PromptTemplates.get("group", "director") },
        GoldenItem("PromptTemplates/TAG_GROUP_AUTO", "PromptTemplates_L121_TAG_GROUP_AUTO.md", mapOf("HUMAN_REALISM" to PromptTemplates.HUMAN_REALISM)) { PromptTemplates.get("group", "auto") },
        GoldenItem("PromptTemplates/DISPATCH_START", "PromptTemplates_L136_DISPATCH_START.md") { PromptTemplates.get("dispatch", "start") },
        GoldenItem("PromptTemplates/DISPATCH_PROGRESS", "PromptTemplates_L159_DISPATCH_PROGRESS.md") { PromptTemplates.get("dispatch", "progress") },
        GoldenItem("PromptTemplates/DISPATCH_ENDING", "PromptTemplates_L185_DISPATCH_ENDING.md") { PromptTemplates.get("dispatch", "ending") },
        GoldenItem("PromptTemplates/PRIVATE_OFFLINE", "PromptTemplates_L221_PRIVATE_OFFLINE.md", rawAnchor = true) { PromptTemplates.ArchivedTemplates.privateOfflineArchived },
        GoldenItem("PromptTemplates/PRIVATE_DIRECTOR", "PromptTemplates_L339_PRIVATE_DIRECTOR.md", rawAnchor = true) { PromptTemplates.ArchivedTemplates.privateDirectorArchived },
        GoldenItem("PromptTemplates/PRIVATE_ONLINE", "PromptTemplates_L452_PRIVATE_ONLINE.md", rawAnchor = true) { PromptTemplates.ArchivedTemplates.privateOnlineArchived },
        GoldenItem("PromptTemplates/PRIVATE_PROACTIVE", "PromptTemplates_L561_PRIVATE_PROACTIVE.md") { PromptTemplates.get("private", "proactive") },
        GoldenItem("PromptTemplates/LEGACY_GROUP_TAG_TEMPLATES", "PromptTemplates_L615_LEGACY_GROUP_TAG_TEMPLATES.md", rawAnchor = true) { PromptTemplates.ArchivedTemplates.legacyGroupTagTemplatesArchived },
        GoldenItem("PromptTemplates/GROUP_DIRECTOR", "PromptTemplates_L744_GROUP_DIRECTOR.md", rawAnchor = true) { PromptTemplates.ArchivedTemplates.groupDirectorArchived },
        GoldenItem("PromptTemplates/GROUP_AUTO", "PromptTemplates_L881_GROUP_AUTO.md", rawAnchor = true) { PromptTemplates.ArchivedTemplates.groupAutoArchived },
        GoldenItem("PromptTemplates/GROUP_ONLINE", "PromptTemplates_L1005_GROUP_ONLINE.md", rawAnchor = true) { PromptTemplates.ArchivedTemplates.groupOnlineArchived },
        GoldenItem("PromptTemplates/MOMENT", "PromptTemplates_L1120_MOMENT.md") { PromptTemplates.get("moment") },
        GoldenItem("PromptTemplates/MOMENT_COMMENT", "PromptTemplates_L1166_MOMENT_COMMENT.md") { PromptTemplates.get("moment_comment") },
        GoldenItem("PromptTemplates/DIARY", "PromptTemplates_L1228_DIARY.md") { PromptTemplates.get("diary") },
        // ---- MemoryV2PromptTemplates（8 件）----
        GoldenItem("MemoryV2/DATA_BOUNDARY", "MemoryV2PromptTemplates_L4_DATA_BOUNDARY.md") { MemoryV2PromptTemplates.DATA_BOUNDARY },
        GoldenItem("MemoryV2/L2", "MemoryV2PromptTemplates_L17_L2.md", mapOf("SCHEMA" to MemoryV2PromptTemplates.SCHEMA, "DATA_BOUNDARY" to MemoryV2PromptTemplates.DATA_BOUNDARY)) { MemoryV2PromptTemplates.L2 },
        GoldenItem("MemoryV2/L3", "MemoryV2PromptTemplates_L35_L3.md", mapOf("SCHEMA" to MemoryV2PromptTemplates.SCHEMA, "DATA_BOUNDARY" to MemoryV2PromptTemplates.DATA_BOUNDARY)) { MemoryV2PromptTemplates.L3 },
        GoldenItem("MemoryV2/PRIVATE_L1", "MemoryV2PromptTemplates_L53_PRIVATE_L1.md", mapOf("SCHEMA" to MemoryV2PromptTemplates.SCHEMA, "DATA_BOUNDARY" to MemoryV2PromptTemplates.DATA_BOUNDARY)) { MemoryV2PromptTemplates.getL1("PRIVATE_CHAT") },
        GoldenItem("MemoryV2/GROUP_L1", "MemoryV2PromptTemplates_L76_GROUP_L1.md", mapOf("SCHEMA" to MemoryV2PromptTemplates.SCHEMA, "DATA_BOUNDARY" to MemoryV2PromptTemplates.DATA_BOUNDARY)) { MemoryV2PromptTemplates.getL1("GROUP_CHAT") },
        GoldenItem("MemoryV2/EVENT_L1", "MemoryV2PromptTemplates_L97_EVENT_L1.md", mapOf("SCHEMA" to MemoryV2PromptTemplates.SCHEMA, "DATA_BOUNDARY" to MemoryV2PromptTemplates.DATA_BOUNDARY)) { MemoryV2PromptTemplates.getL1("MOMENT") },
        GoldenItem("MemoryV2/DIARY_L1", "MemoryV2PromptTemplates_L116_DIARY_L1.md", mapOf("SCHEMA" to MemoryV2PromptTemplates.SCHEMA, "DATA_BOUNDARY" to MemoryV2PromptTemplates.DATA_BOUNDARY)) { MemoryV2PromptTemplates.getL1("DIARY") },
        GoldenItem("MemoryV2/SCHEMA", "MemoryV2PromptTemplates_L136_SCHEMA.md") { MemoryV2PromptTemplates.SCHEMA },
        // ---- SleepPrompts（2 件）----
        GoldenItem("SleepPrompts/CALL", "SleepPrompts_L4_CALL.md") { SleepPrompts.CALL },
        GoldenItem("SleepPrompts/WAKE", "SleepPrompts_L18_WAKE.md") { SleepPrompts.WAKE },
        // ---- PromptModuleDefaults（9 件：3 函数 + 6 源分片锚点）----
        GoldenItem("ModuleDefaults/narrationProtocol", "PromptModuleDefaults_L187_in_narrationProtocol.md") { PromptModuleDefaults.narrationProtocol("offline") },
        GoldenItem("ModuleDefaults/runtime-private", "PromptModuleDefaults_L336_in_runtime.md") { PromptModuleDefaults.runtime("private", "") },
        GoldenItem("ModuleDefaults/runtime-group", "PromptModuleDefaults_L360_in_runtime.md") { PromptModuleDefaults.runtime("group", "") },
        GoldenItem("ModuleDefaults/privateOutputProtocolHead", "PromptModuleDefaults_L207_in_privateOutputProtocol.md", rawAnchor = true) { PromptModuleDefaults.SourceFragments.privateOutputProtocolHead },
        GoldenItem("ModuleDefaults/privateOutputProtocolElseSeparator", "PromptModuleDefaults_L237_in_privateOutputProtocol.md", rawAnchor = true) { PromptModuleDefaults.SourceFragments.privateOutputProtocolElseSeparator },
        GoldenItem("ModuleDefaults/privateOutputProtocolTail", "PromptModuleDefaults_L247_in_privateOutputProtocol.md", rawAnchor = true) { PromptModuleDefaults.SourceFragments.privateOutputProtocolTail },
        GoldenItem("ModuleDefaults/groupOutputProtocolHead", "PromptModuleDefaults_L283_in_groupOutputProtocol.md", rawAnchor = true) { PromptModuleDefaults.SourceFragments.groupOutputProtocolHead },
        GoldenItem("ModuleDefaults/groupOutputProtocolElseSeparator", "PromptModuleDefaults_L309_in_groupOutputProtocol.md", rawAnchor = true) { PromptModuleDefaults.SourceFragments.groupOutputProtocolElseSeparator },
        GoldenItem("ModuleDefaults/groupOutputProtocolTail", "PromptModuleDefaults_L316_in_groupOutputProtocol.md", rawAnchor = true) { PromptModuleDefaults.SourceFragments.groupOutputProtocolTail },
        // ---- AgentProfiles（6 件，已在 shared/settings，金样比对即可）----
        GoldenItem("AgentProfiles/nuan", "AgentProfiles_L30.md") { profile("nuan") },
        GoldenItem("AgentProfiles/yu", "AgentProfiles_L42.md") { profile("yu") },
        GoldenItem("AgentProfiles/fei", "AgentProfiles_L54.md") { profile("fei") },
        GoldenItem("AgentProfiles/chuan", "AgentProfiles_L66.md") { profile("chuan") },
        GoldenItem("AgentProfiles/lin", "AgentProfiles_L78.md") { profile("lin") },
        GoldenItem("AgentProfiles/tuan", "AgentProfiles_L90.md") { profile("tuan") },
    )

    @Test
    fun tier1_fortySevenGoldenLiteralsMatchByteForByte() {
        val missing = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val allowlist = GoldenGate.loadAllowlist()
        var strict = 0
        var canonical = 0
        var anchors = 0
        val strictIds = mutableListOf<String>()
        val canonicalIds = mutableListOf<String>()
        for (item in items) {
            val body = try {
                GoldenGate.goldenBody(item.raw)
            } catch (e: Throwable) {
                missing += item.raw
                continue
            }
            if (item.rawAnchor) {
                // 源分片锚点：值 = 金样正文（含 `${if...}` 语法原文），严格相等。
                if (item.actual() == body) anchors++ else failures += diff(item.raw, item.actual(), body)
                continue
            }
            val expected = GoldenGate.stripOuterBlankLines(GoldenGate.resolveDollarRefs(body, item.refs))
            val actual = GoldenGate.stripOuterBlankLines(item.actual())
            if (actual == expected) {
                strict++
                strictIds += item.id
                continue
            }
            val allowed = allowlist.any { it.raw == item.raw && it.field == "leading-whitespace" }
            if (allowed && GoldenGate.canonical(actual) == GoldenGate.canonical(expected)) {
                canonical++
                canonicalIds += item.id
                continue
            }
            failures += diff(item.raw, actual, expected)
        }
        assertTrue(missing.isEmpty(), "金样缺件清单（fail-fast）：\n${missing.joinToString("\n")}")
        assertTrue(failures.isEmpty(), "金样字节差异：\n${failures.joinToString("\n\n")}")
        assertEquals(47, items.size)
        assertEquals(13, anchors, "rawAnchor 严格通过件数漂移（6 件模块分片锚点 + 7 件归档模板锚点）")
        assertEquals(27, strict, "strict 严格通过件数漂移（当前=$strict，实际清单：${strictIds.joinToString("; ")})")
        assertEquals(7, canonical, "白名单 canonical 通过件数漂移（当前=$canonical，实际清单：${canonicalIds.joinToString("; ")})")
    }

    @Test
    fun tier1_renderedTemplatesLeaveNoUnresolvedTokens() {
        val clock = FixedClock(fixedInstant)
        items.filter { !it.rawAnchor }.forEach { item ->
            val template = item.actual()
            val tokens = TEMPLATE_PLACEHOLDER_PATTERN.findAll(template).map { it.groupValues[1] }.toSet()
            val fixtures = tokens.associateWith { key ->
                when (key) {
                    "CURRENT_TIME" -> PromptClock.beijingPromptTime(clock, fixedInstant)
                    "CURRENT_DATE" -> "2026-06-15"
                    "YESTERDAY_DATE" -> "2026-06-14"
                    "TIME_OF_DAY" -> PromptRender.getTimeOfDay(17)
                    else -> "fixture-$key"
                }
            }
            val rendered = PromptRender.applyTemplate(template, fixtures)
            val residue = TEMPLATE_PLACEHOLDER_PATTERN.findAll(rendered).map { it.value }.toSet()
            assertTrue(residue.isEmpty(), "${item.raw} 渲染后残留 token: $residue")
        }
    }

    private fun diff(raw: String, actual: String, expected: String): String {
        val a = actual.split("\n")
        val e = expected.split("\n")
        val sb = StringBuilder("$raw: ")
        if (a.size != e.size) {
            sb.append("行数 ${a.size} vs ${e.size}")
        } else {
            for (i in a.indices) {
                if (a[i] != e[i]) {
                    sb.append("第${i + 1}行\n  expected=${e[i].take(80)}\n  actual  =${a[i].take(80)}")
                    break
                }
            }
        }
        return sb.toString()
    }
}
