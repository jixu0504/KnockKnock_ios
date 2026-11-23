package com.rhodes.privatechat.shared.prompt

/**
 * 编辑器模型与存储桥（契约 3/4/5/15/16）。
 *
 * `PromptResolver` 是 prompt 域对 settings 存取能力的最小接口（由 settings 域实现，
 * 现有实现：SettingsRepository.resolvePromptTemplate / saveCustomPromptTemplate /
 * removePromptTemplate / getPromptModule / getCustomPromptModuleOrNull / savePromptModule /
 * removePromptModule —— oracle SettingsRepository.kt:1270-1357）。prompt 域不持有存储。
 *
 * `PromptEditor` 是 MainViewModel.kt:422-578 管理 API 的纯逻辑形态（oracle 语义逐条保留）：
 * - 保存 = 值 + `_custom=true` + `_version=VERSION` 三键（契约 4）；
 * - 恢复默认 = 删三键（契约 5）；
 * - 模块自定义：仅 `_custom=true` 时用已存值；空字符串是「有意禁用」的合法自定义，
 *   不回落默认（契约 15）；模块 version 键写但不参与解析（lead 裁决）。
 */
interface PromptResolver {
    fun resolvePromptTemplate(type: String, mode: String, defaultTemplate: String, defaultVersion: Int): String
    fun saveCustomPromptTemplate(type: String, mode: String, value: String, version: Int)
    fun removePromptTemplate(type: String, mode: String)
    fun isPromptTemplateCustom(type: String, mode: String): Boolean
    fun getPromptModule(module: String, type: String, mode: String): String
    fun getCustomPromptModuleOrNull(module: String, type: String, mode: String): String?
    fun savePromptModule(module: String, type: String, mode: String, value: String, version: Int)
    fun removePromptModule(module: String, type: String, mode: String)
    fun isPromptModuleCustom(module: String, type: String, mode: String): Boolean
}

object PromptEditor {

    fun defaultTemplate(type: String, mode: String = ""): String = PromptTemplates.get(type, mode)

    /** 主模板解析：四分支语义在 resolver 实现内（SettingsRepository.resolvePromptTemplate，oracle :1296-1304）。 */
    fun getPromptTemplate(resolver: PromptResolver, type: String, mode: String = ""): String =
        resolver.resolvePromptTemplate(type, mode, defaultTemplate(type, mode), PromptTemplates.VERSION)

    fun isPromptTemplateCustom(resolver: PromptResolver, type: String, mode: String = ""): Boolean =
        resolver.isPromptTemplateCustom(type, mode)

    /** 保存：先过语法校验（阻断畸形语法），再写三键，返回 warnings（oracle MainViewModel.kt:482-488）。 */
    fun savePromptTemplate(resolver: PromptResolver, type: String, mode: String, template: String): List<String> {
        val errors = PromptSyntaxValidator.validatePromptTemplate(type, mode, template)
        if (errors.isEmpty()) {
            resolver.saveCustomPromptTemplate(type, mode, template, PromptTemplates.VERSION)
        }
        return if (errors.isNotEmpty()) errors else PromptSyntaxValidator.promptWarnings(type, mode, template)
    }

    fun resetPromptTemplate(resolver: PromptResolver, type: String, mode: String = "") {
        resolver.removePromptTemplate(type, mode)
    }

    fun defaultPromptModule(module: String, type: String, mode: String = ""): String = when (module) {
        "protocol" -> PromptModuleDefaults.outputProtocol(type, mode)
        "runtime" -> PromptModuleDefaults.runtime(type, mode)
        "behavior" -> PromptModuleDefaults.behavior(type, mode)
        else -> ""
    }

    /** 仅当 `_custom=true` 时用已存值，否则随包默认（oracle MainViewModel.kt:497-506）。 */
    fun getPromptModule(resolver: PromptResolver, module: String, type: String, mode: String = ""): String {
        val saved = resolver.getPromptModule(module, type, mode)
        if (resolver.isPromptModuleCustom(module, type, mode)) return saved
        return defaultPromptModule(module, type, mode)
    }

    fun isPromptModuleCustom(resolver: PromptResolver, module: String, type: String, mode: String = ""): Boolean =
        resolver.isPromptModuleCustom(module, type, mode)

    /** 模块保存：校验同模板语法；写值+`_custom`+`_version`；空串=有意禁用（契约 16，oracle MainViewModel.kt:518-526）。 */
    fun savePromptModule(resolver: PromptResolver, module: String, type: String, mode: String, value: String): List<String> {
        val errors = PromptSyntaxValidator.promptSyntaxErrors(value)
        if (errors.isNotEmpty()) return errors
        val tokens = Regex("\\{\\{([^{}\\r\\n]+)\\}\\}").findAll(value).map { it.groupValues[1].trim() }.toSet()
        val warnings = tokens.filter { it !in PromptPlaceholderRegistry.allowed(type, mode) }
            .sorted().map { PromptPlaceholderRegistry.unsupportedWarning(it) }
        resolver.savePromptModule(module, type, mode, value, PromptTemplates.VERSION)
        return warnings
    }

    fun resetPromptModule(resolver: PromptResolver, module: String, type: String, mode: String = "") {
        resolver.removePromptModule(module, type, mode)
    }
}
