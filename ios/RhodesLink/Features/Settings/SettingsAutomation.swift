//
//  SettingsAutomation.swift
//  T46 设置页端到端自动化验证（仅启动参数启用，对用户路径零影响）：
//
//   `--settings-selftest`          写入阶段：聊天表现默认值读取 → 改温度/深度思考/历史轮数/回复长度上限
//                                  → 读回一致 → 提示词保存/语法阻断/恢复默认往返 → 模块空串禁用语义
//                                  → 识图配置保存（Keychain + vision_* 键）→ effectiveVisionConfiguration.configured=true
//                                  → flushDefaults() 强制 NSUserDefaults 落盘。
//   `--settings-selftest-verify`   冷启动校验阶段：不写任何值，只读取并断言上一轮写入已落盘（NSUserDefaults 持久化）。
//   `--settings-selftest-restore`  恢复阶段：聊天参数恢复 Android 默认值（0.70/false/15/300/300）、提示词恢复默认、
//                                  识图配置清空回 auto。
//
//  证据：NSLog + `--selftest` 时落盘 Documents/ios-shell-selftest.log（simctl get_app_container 取出）；
//  外部证据：simctl 容器内 Library/Preferences/com.rhodeslink.app.plist（plutil -p）。
//

import Foundation

@MainActor
enum SettingsAutomation {
    private static let arguments = ProcessInfo.processInfo.arguments
    private static let writeEnabled = arguments.contains("--settings-selftest")
    private static let verifyEnabled = arguments.contains("--settings-selftest-verify")
    private static let restoreEnabled = arguments.contains("--settings-selftest-restore")
    private static var didRun = false

    /// 写入阶段的期望值（验证阶段复用）
    private static let expectedTemperature = 1.23
    private static let expectedThinking = true
    private static let expectedHistory = 42
    private static let expectedDialogueMax = 555
    private static let expectedNarrationMax = 444
    private static let expectedDialogueMin = 20
    private static let expectedNarrationMin = 60
    private static let expectedDialogueSegMin = 2
    private static let expectedDialogueSegMax = 5
    private static let expectedNarrationSegMin = 1
    private static let expectedNarrationSegMax = 4
    private static let expectedGroupMsgMin = 15
    private static let expectedGroupMsgMax = 150
    private static let expectedGroupSpeechMin = 1
    private static let expectedGroupSpeechMax = 3
    private static let expectedGroupNarSegMin = 1
    private static let expectedGroupNarSegMax = 2
    private static let expectedGroupNarMin = 25
    private static let expectedGroupNarMax = 120

    /// Android 默认值（`SettingsRepository` 键默认值与 ChatSettingsScreen 默认一致）
    private static let defaultTemperature = 0.70
    private static let defaultThinking = false
    private static let defaultHistory = 15
    private static let defaultDialogueMax = 300
    private static let defaultNarrationMax = 300
    private static let defaultDialogueMin = 10
    private static let defaultNarrationMin = 50
    private static let defaultDialogueSegMin = 1
    private static let defaultDialogueSegMax = 3
    private static let defaultNarrationSegMin = 1
    private static let defaultNarrationSegMax = 3
    private static let defaultGroupMsgMin = 10
    private static let defaultGroupMsgMax = 100
    private static let defaultGroupSpeechMin = 1
    private static let defaultGroupSpeechMax = 2
    private static let defaultGroupNarSegMin = 1
    private static let defaultGroupNarSegMax = 1
    private static let defaultGroupNarMin = 20
    private static let defaultGroupNarMax = 100

    private static func argValue(_ flag: String, default fallback: String) -> String {
        guard let index = arguments.firstIndex(of: flag), index + 1 < arguments.count else { return fallback }
        return arguments[index + 1]
    }

    static func runIfNeeded(environment: AppEnvironment) async {
        guard writeEnabled || verifyEnabled || restoreEnabled else { return }
        guard !didRun else { return }
        didRun = true
        let bridge = environment.bridge

        if restoreEnabled {
            restore(bridge: bridge)
            return
        }
        if verifyEnabled {
            verify(bridge: bridge)
            return
        }
        write(bridge: bridge)
    }

    // MARK: - 写入阶段

    private static func write(bridge: AppGraphClient) {
        SelfTestLog.add("=== settings selftest write start ===")
        var passCount = 0
        var failCount = 0
        func check(_ name: String, _ condition: Bool, _ detail: String) {
            if condition { passCount += 1 } else { failCount += 1 }
            SelfTestLog.add("settings-check \(condition ? "PASS" : "FAIL") \(name) \(detail)")
        }

        // 1. 聊天表现：默认值读取 → 改值 → 读回
        let initial = bridge.chatPerformance()
        SelfTestLog.add(
            "settings chat-performance initial temp=\(initial.temperature) thinking=\(initial.deepseekThinking) " +
            "history=\(initial.historyMessages) diaMax=\(initial.dialogueMaxChars) narMax=\(initial.narrationMaxChars) " +
            "diaMin=\(initial.dialogueMinChars) narMin=\(initial.narrationMinChars) contextMode=\(initial.contextMode)"
        )
        let saveResult = bridge.saveChatPerformance(
            temperature: expectedTemperature,
            deepseekThinking: expectedThinking,
            historyMessages: expectedHistory,
            dialogueMaxChars: expectedDialogueMax,
            narrationMaxChars: expectedNarrationMax,
            dialogueMinChars: expectedDialogueMin,
            narrationMinChars: expectedNarrationMin,
            dialogueSegMin: expectedDialogueSegMin,
            dialogueSegMax: expectedDialogueSegMax,
            narrationSegMin: expectedNarrationSegMin,
            narrationSegMax: expectedNarrationSegMax,
            groupMsgMin: expectedGroupMsgMin,
            groupMsgMax: expectedGroupMsgMax,
            groupSpeechMin: expectedGroupSpeechMin,
            groupSpeechMax: expectedGroupSpeechMax,
            groupNarSegMin: expectedGroupNarSegMin,
            groupNarSegMax: expectedGroupNarSegMax,
            groupNarMin: expectedGroupNarMin,
            groupNarMax: expectedGroupNarMax
        )
        let after = bridge.chatPerformance()
        let paramsOK = saveResult.isEmpty
            && abs(after.temperature - expectedTemperature) < 0.001
            && after.deepseekThinking == expectedThinking
            && after.historyMessages == expectedHistory
            && after.dialogueMaxChars == expectedDialogueMax
            && after.narrationMaxChars == expectedNarrationMax
            && after.dialogueMinChars == expectedDialogueMin
            && after.narrationMinChars == expectedNarrationMin
            && after.dialogueSegMin == expectedDialogueSegMin
            && after.dialogueSegMax == expectedDialogueSegMax
            && after.narrationSegMin == expectedNarrationSegMin
            && after.narrationSegMax == expectedNarrationSegMax
            && after.groupMsgMin == expectedGroupMsgMin
            && after.groupMsgMax == expectedGroupMsgMax
            && after.groupSpeechMin == expectedGroupSpeechMin
            && after.groupSpeechMax == expectedGroupSpeechMax
            && after.groupNarSegMin == expectedGroupNarSegMin
            && after.groupNarSegMax == expectedGroupNarSegMax
            && after.groupNarMin == expectedGroupNarMin
            && after.groupNarMax == expectedGroupNarMax
        check(
            "chat-performance.save-readback",
            paramsOK,
            "result='\(saveResult)' temp=\(after.temperature) thinking=\(after.deepseekThinking) " +
            "history=\(after.historyMessages) dia=\(after.dialogueMinChars)-\(after.dialogueMaxChars) " +
            "nar=\(after.narrationMinChars)-\(after.narrationMaxChars) diaSeg=\(after.dialogueSegMin)-\(after.dialogueSegMax) " +
            "narSeg=\(after.narrationSegMin)-\(after.narrationSegMax)"
        )

        // 2. 提示词：保存自定义 → 读回（含 _custom 标记）→ 语法阻断 → 恢复默认
        let type = "private"
        let mode = "offline"
        let module = ""
        let before = bridge.promptItem(type: type, mode: mode, module: module)
        let customText = "【T46 自检】你是{{OPERATOR_NAME}}。这是 iOS 提示词编辑器写入的自定义模板，用于验证「保存 / 读回 / 恢复默认」往返。"
        let presetSave = bridge.savePromptItem(type: type, mode: mode, module: module, text: customText)
        let afterSave = bridge.promptItem(type: type, mode: mode, module: module)
        check(
            "prompt.save-readback",
            presetSave.ok && afterSave.isCustom && afterSave.currentText == customText && !afterSave.isDisabled,
            "saveOk=\(presetSave.ok) warnings=\(presetSave.warnings.count) isCustom=\(afterSave.isCustom) " +
            "version=\(afterSave.version) len=\(afterSave.currentText.count) defaultLen=\(before.defaultText.count)"
        )
        let invalidSave = bridge.savePromptItem(type: type, mode: mode, module: module, text: "{{未闭合占位符")
        check(
            "prompt.invalid-syntax-blocked",
            !invalidSave.ok && !invalidSave.error.isEmpty,
            "ok=\(invalidSave.ok) error=\(invalidSave.error.replacingOccurrences(of: "\n", with: " / "))"
        )
        bridge.resetPromptItem(type: type, mode: mode, module: module)
        let afterReset = bridge.promptItem(type: type, mode: mode, module: module)
        check(
            "prompt.reset-default",
            !afterReset.isCustom && afterReset.currentText == afterReset.defaultText && afterReset.version == 0,
            "isCustom=\(afterReset.isCustom) version=\(afterReset.version) len=\(afterReset.currentText.count) " +
            "placeholders=\(afterReset.placeholders.count) recommended=\(afterReset.recommended.count) legacy=\(afterReset.legacy.count)"
        )

        // 3. 模块空串禁用语义（T18 契约 15/16）
        let moduleType = "private"
        let moduleMode = "online"
        let moduleName = "behavior"
        let moduleSave = bridge.savePromptItem(type: moduleType, mode: moduleMode, module: moduleName, text: "")
        let moduleAfter = bridge.promptItem(type: moduleType, mode: moduleMode, module: moduleName)
        check(
            "prompt.module-empty-disable",
            moduleSave.ok && moduleAfter.isCustom && moduleAfter.isDisabled && moduleAfter.currentText.isEmpty,
            "saveOk=\(moduleSave.ok) isCustom=\(moduleAfter.isCustom) isDisabled=\(moduleAfter.isDisabled) textLen=\(moduleAfter.currentText.count)"
        )
        bridge.resetPromptItem(type: moduleType, mode: moduleMode, module: moduleName)
        let moduleReset = bridge.promptItem(type: moduleType, mode: moduleMode, module: moduleName)
        check("prompt.module-reset", !moduleReset.isCustom && !moduleReset.isDisabled, "isCustom=\(moduleReset.isCustom)")

        // 4. 识图模型：保存（Keychain + vision_* 键）→ effectiveVisionConfiguration.configured=true
        let endpoint = argValue("--vision-endpoint", default: "http://127.0.0.1:18764/v1/chat/completions")
        let model = argValue("--vision-model", default: "mock-vision")
        let apiKey = argValue("--vision-key", default: "sk-mock-vision-t46")
        let visionSave = bridge.saveVisionConfiguration(provider: "custom", endpoint: endpoint, model: model, apiKey: apiKey)
        let vision = bridge.visionStatus()
        check(
            "vision.save-effective-configured",
            visionSave.isEmpty && vision.configured && vision.configured == vision.engineConfigured && vision.mode == "independent",
            "result='\(visionSave)' mode=\(vision.mode) provider=\(vision.provider) configured=\(vision.configured) " +
            "engineConfigured=\(vision.engineConfigured) keyConfigured=\(vision.keyConfigured) backend=\(vision.keyStoreBackend) " +
            "effectiveEndpoint=\(vision.effectiveEndpoint)"
        )

        // 5. 落盘（证据：plist 检查 + 冷启动 --settings-selftest-verify）
        let flushed = bridge.flushSettingsDefaults()
        SelfTestLog.add("settings flushDefaults=\(flushed)")
        let summary = "settings selftest write summary pass=\(passCount) fail=\(failCount) " +
            "temp=\(expectedTemperature) thinking=\(expectedThinking) history=\(expectedHistory) " +
            "diaMax=\(expectedDialogueMax) narMax=\(expectedNarrationMax) visionConfigured=\(vision.configured)"
        SelfTestLog.add("=== settings selftest write done pass=\(passCount) fail=\(failCount) ===")
        SelfTestLog.flushToDisk(summary: summary)
    }

    // MARK: - 冷启动校验阶段（只读 + 断言持久化）

    private static func verify(bridge: AppGraphClient) {
        SelfTestLog.add("=== settings selftest verify start (cold start, read-only) ===")
        var passCount = 0
        var failCount = 0
        func check(_ name: String, _ condition: Bool, _ detail: String) {
            if condition { passCount += 1 } else { failCount += 1 }
            SelfTestLog.add("settings-check \(condition ? "PASS" : "FAIL") \(name) \(detail)")
        }

        let perf = bridge.chatPerformance()
        check(
            "persisted.chat-performance",
            abs(perf.temperature - expectedTemperature) < 0.001
                && perf.deepseekThinking == expectedThinking
                && perf.historyMessages == expectedHistory
                && perf.dialogueMaxChars == expectedDialogueMax
                && perf.narrationMaxChars == expectedNarrationMax
                && perf.dialogueMinChars == expectedDialogueMin
                && perf.narrationMinChars == expectedNarrationMin
                && perf.dialogueSegMin == expectedDialogueSegMin
                && perf.dialogueSegMax == expectedDialogueSegMax
                && perf.narrationSegMin == expectedNarrationSegMin
                && perf.narrationSegMax == expectedNarrationSegMax
                && perf.groupMsgMin == expectedGroupMsgMin
                && perf.groupMsgMax == expectedGroupMsgMax
                && perf.groupSpeechMin == expectedGroupSpeechMin
                && perf.groupSpeechMax == expectedGroupSpeechMax
                && perf.groupNarSegMin == expectedGroupNarSegMin
                && perf.groupNarSegMax == expectedGroupNarSegMax
                && perf.groupNarMin == expectedGroupNarMin
                && perf.groupNarMax == expectedGroupNarMax,
            "temp=\(perf.temperature) thinking=\(perf.deepseekThinking) history=\(perf.historyMessages) " +
            "diaMax=\(perf.dialogueMaxChars) narMax=\(perf.narrationMaxChars)"
        )

        let prompt = bridge.promptItem(type: "private", mode: "offline", module: "")
        check(
            "persisted.prompt-reset",
            !prompt.isCustom && prompt.currentText == prompt.defaultText,
            "isCustom=\(prompt.isCustom) len=\(prompt.currentText.count)"
        )

        let vision = bridge.visionStatus()
        check(
            "persisted.vision-configured",
            vision.mode == "independent" && vision.provider == "custom" && vision.configured,
            "mode=\(vision.mode) provider=\(vision.provider) configured=\(vision.configured) " +
            "engineConfigured=\(vision.engineConfigured) endpoint=\(vision.effectiveEndpoint) model=\(vision.effectiveModel)"
        )

        let summary = "settings selftest verify summary pass=\(passCount) fail=\(failCount) (cold-start persisted values)"
        SelfTestLog.add("=== settings selftest verify done pass=\(passCount) fail=\(failCount) ===")
        SelfTestLog.flushToDisk(summary: summary)
    }

    // MARK: - 恢复阶段

    private static func restore(bridge: AppGraphClient) {
        SelfTestLog.add("=== settings selftest restore start ===")
        let result = bridge.saveChatPerformance(
            temperature: defaultTemperature,
            deepseekThinking: defaultThinking,
            historyMessages: defaultHistory,
            dialogueMaxChars: defaultDialogueMax,
            narrationMaxChars: defaultNarrationMax,
            dialogueMinChars: defaultDialogueMin,
            narrationMinChars: defaultNarrationMin,
            dialogueSegMin: defaultDialogueSegMin,
            dialogueSegMax: defaultDialogueSegMax,
            narrationSegMin: defaultNarrationSegMin,
            narrationSegMax: defaultNarrationSegMax,
            groupMsgMin: defaultGroupMsgMin,
            groupMsgMax: defaultGroupMsgMax,
            groupSpeechMin: defaultGroupSpeechMin,
            groupSpeechMax: defaultGroupSpeechMax,
            groupNarSegMin: defaultGroupNarSegMin,
            groupNarSegMax: defaultGroupNarSegMax,
            groupNarMin: defaultGroupNarMin,
            groupNarMax: defaultGroupNarMax
        )
        bridge.resetPromptItem(type: "private", mode: "offline", module: "")
        bridge.resetPromptItem(type: "private", mode: "online", module: "behavior")
        let cleared = bridge.clearVisionConfiguration()
        let flushed = bridge.flushSettingsDefaults()
        let perf = bridge.chatPerformance()
        let vision = bridge.visionStatus()
        SelfTestLog.add(
            "settings restore result='\(result)' temp=\(perf.temperature) thinking=\(perf.deepseekThinking) " +
            "history=\(perf.historyMessages) diaMax=\(perf.dialogueMaxChars) narMax=\(perf.narrationMaxChars) " +
            "visionCleared=\(cleared) visionMode=\(vision.mode) visionConfigured=\(vision.configured) flush=\(flushed)"
        )
        SelfTestLog.add("=== settings selftest restore done ===")
        SelfTestLog.flushToDisk(summary: "settings selftest restore temp=\(perf.temperature) history=\(perf.historyMessages) visionMode=\(vision.mode)")
    }
}
