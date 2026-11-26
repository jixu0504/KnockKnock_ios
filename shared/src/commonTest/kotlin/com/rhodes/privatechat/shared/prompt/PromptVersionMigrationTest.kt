package com.rhodes.privatechat.shared.prompt

import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * VERSION 迁移矩阵 + 编辑器三键语义（规格卡验收 #4；契约 3/4/5/15/16）。
 * 直接驱动真实 SettingsRepository（shared 已含 oracle 逻辑 SettingsRepository.kt:1270-1357），
 * settings 存储用内存实现。
 */
class PromptVersionMigrationTest {

    private fun repo(store: PromptTestSettings): SettingsRepository = SettingsRepository(store)

    private fun s(r: SettingsRepository): PromptResolver = SettingsPromptResolver(r)

    private fun storeWith(value: String, custom: Boolean = false, version: Int = 0): Pair<SettingsRepository, PromptTestSettings> {
        val store = PromptTestSettings()
        val r = repo(store)
        store.putString("prompt_private", value)
        store.putBoolean("prompt_private_custom", custom)
        store.putInt("prompt_private_version", version)
        return r to store
    }

    // ---- 契约 3：resolvePromptTemplate 四分支（oracle SettingsRepository.kt:1296-1304）----

    @Test
    fun branchA_customTrueKeepsSavedTemplateAcrossUpgrade() {
        val (r, _) = storeWith("我的自定义模板", custom = true, version = 38)
        assertEquals("我的自定义模板", r.resolvePromptTemplate("private", "", "新默认模板", 39))
    }

    @Test
    fun branchB_blankSavedFallsBackToShippedDefault() {
        val (r, _) = storeWith("", custom = false, version = 0)
        assertEquals("随包默认", r.resolvePromptTemplate("private", "", "随包默认", 39))
    }

    @Test
    fun branchC_legacyShippedDefaultIsUpgraded() {
        val (r, _) = storeWith("旧随包默认", custom = false, version = 38)
        assertEquals("升级后默认", r.resolvePromptTemplate("private", "", "升级后默认", 39))
    }

    @Test
    fun branchD_currentVersionKeepsSavedTemplate() {
        val (r, _) = storeWith("当前版本模板", custom = false, version = 39)
        assertEquals("当前版本模板", r.resolvePromptTemplate("private", "", "新默认", 39))
    }

    @Test
    fun modeSuffixKeysResolveIndependently() {
        val store = PromptTestSettings()
        val r = repo(store)
        store.putString("prompt_private_offline", "离线自定义")
        store.putBoolean("prompt_private_offline_custom", true)
        assertEquals("离线自定义", r.resolvePromptTemplate("private", "offline", "默认离线", 39))
        assertEquals("默认在线", r.resolvePromptTemplate("private", "online", "默认在线", 39))
    }

    // ---- 契约 4：保存 = 值 + _custom=true + _version=VERSION 三键 ----

    @Test
    fun validTemplateWithAllowedPlaceholdersReturnsNoWarnings() {
        val store = PromptTestSettings()
        val r = repo(store)
        val warnings = PromptEditor.savePromptTemplate(s(r), "private", "", "自定义 {{OPERATOR_NAME}} {{CURRENT_TIME}}")
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun savePromptTemplatePersistsValueCustomAndVersion() {
        val store = PromptTestSettings()
        val r = repo(store)
        val result = PromptEditor.savePromptTemplate(s(r), "private", "offline", "离线模板")
        assertTrue(result.isEmpty() || result.any { it.contains("保留原样") || it.contains("旧版兼容") })
        assertEquals("离线模板", store.getString("prompt_private_offline", ""))
        assertTrue(store.getBoolean("prompt_private_offline_custom", false))
        assertEquals(PromptTemplates.VERSION, store.getInt("prompt_private_offline_version", 0))
        assertEquals(40, PromptTemplates.VERSION)
    }

    @Test
    fun unknownPlaceholderWarnsButSaves() {
        val store = PromptTestSettings()
        val r = repo(store)
        val warnings = PromptEditor.savePromptTemplate(s(r), "private", "", "{{COMPLETELY_UNKNOWN_TOKEN}}")
        assertEquals(listOf("{{COMPLETELY_UNKNOWN_TOKEN}} 当前不会自动替换，发送给 AI 时会保留原样。"), warnings)
        assertEquals("{{COMPLETELY_UNKNOWN_TOKEN}}", store.getString("prompt_private", ""))
    }

    @Test
    fun legacyPlaceholderGetsCompatibilityWarning() {
        val store = PromptTestSettings()
        val r = repo(store)
        val warnings = PromptEditor.savePromptTemplate(s(r), "private", "", "{{GROUP_SUMMARIES}}")
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("旧版兼容"))
        assertFalse(warnings.single().contains("保留原样"))
    }

    @Test
    fun malformedSyntaxBlocksSave() {
        val store = PromptTestSettings()
        val r = repo(store)
        val errors = PromptEditor.savePromptTemplate(s(r), "private", "", "孤立 }} 结尾")
        assertEquals(listOf("}} 格式不正确，请使用 {{名称}}。"), errors)
        assertFalse(store.hasKey("prompt_private"))
    }

    // ---- 契约 5：恢复默认 = 删三键 ----

    @Test
    fun resetPromptTemplateRemovesAllThreeKeys() {
        val store = PromptTestSettings()
        val r = repo(store)
        PromptEditor.savePromptTemplate(s(r), "private", "", "自定义")
        assertTrue(store.hasKey("prompt_private"))
        PromptEditor.resetPromptTemplate(s(r), "private", "")
        assertFalse(store.hasKey("prompt_private"))
        assertFalse(store.hasKey("prompt_private_custom"))
        assertFalse(store.hasKey("prompt_private_version"))
        // 恢复后解析回到随包默认
        assertEquals(PromptTemplates.get("private"), r.resolvePromptTemplate("private", "", PromptTemplates.get("private"), PromptTemplates.VERSION))
    }

    // ---- 契约 15：模块启停（空串=有意禁用）+ version 写但不解析 ----

    @Test
    fun emptyCustomModuleIsDeliberateDisableNotFallback() {
        val store = PromptTestSettings()
        val r = repo(store)
        PromptEditor.savePromptModule(s(r), "behavior", "private", "online", "")
        assertEquals("", PromptEditor.getPromptModule(s(r), "behavior", "private", "online"))
        assertEquals("", r.getCustomPromptModuleOrNull("behavior", "private", "online"))
        // 未自定义的模块回落随包默认
        assertTrue(PromptEditor.getPromptModule(s(r), "behavior", "private", "offline").isNotBlank())
        // 从未自定义 → null
        assertEquals(null, r.getCustomPromptModuleOrNull("runtime", "private", "online"))    }

    @Test
    fun moduleVersionIsWrittenButNeverParsed() {
        val store = PromptTestSettings()
        val r = repo(store)
        PromptEditor.savePromptModule(s(r), "behavior", "private", "online", "自定义行为")
        assertEquals(PromptTemplates.VERSION, r.getPromptModuleVersion("behavior", "private", "online"))
        // 版本键被改小也不影响自定义生效（不解析版本比较——lead 裁决）
        r.putPromptModuleVersion("behavior", "private", "online", 0)
        assertEquals("自定义行为", PromptEditor.getPromptModule(s(r), "behavior", "private", "online"))
    }

    @Test
    fun customModuleBeatsShippedDefault() {
        val store = PromptTestSettings()
        val r = repo(store)
        assertEquals(PromptModuleDefaults.behavior("private", "online"), PromptEditor.getPromptModule(s(r), "behavior", "private", "online"))
        PromptEditor.savePromptModule(s(r), "behavior", "private", "online", "我的自定义行为")
        assertEquals("我的自定义行为", PromptEditor.getPromptModule(s(r), "behavior", "private", "online"))
        PromptEditor.resetPromptModule(s(r), "behavior", "private", "online")
        assertEquals(PromptModuleDefaults.behavior("private", "online"), PromptEditor.getPromptModule(s(r), "behavior", "private", "online"))
    }

    @Test
    fun moduleDefaultsDispatchThroughPromptEditor() {
        val store = PromptTestSettings()
        val r = repo(store)
        assertEquals(PromptModuleDefaults.outputProtocol("private", "online"), PromptEditor.defaultPromptModule("protocol", "private", "online"))
        assertEquals(PromptModuleDefaults.runtime("group", ""), PromptEditor.defaultPromptModule("runtime", "group", ""))
        assertEquals("", PromptEditor.defaultPromptModule("narration", "private", "online"))
    }

    @Test
    fun promptEditorDefaultTemplateUsesLibraryDispatch() {
        assertEquals(PromptTemplates.get("group", "auto"), PromptEditor.defaultTemplate("group", "auto"))
        assertEquals("", PromptEditor.defaultTemplate("unknown"))
    }
}
