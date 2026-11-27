//
//  ProviderAutomation.swift
//  T37 真实供应商端到端自动化验证（仅 `--provider-selftest` 启动参数启用，对用户路径零影响）。
//
//  流程（全部经真实 Shared.framework + 真实 DB + 真实 Ktor Darwin 网络栈）：
//   1. 无配置回退演示模式：清 Key → 发一句话 → 断言 demo=true、回复含「演示模式」；
//   2. 真实流式：写自定义端点（mock SSE）→ 连接测试 → 发一句话 → 断言 demo=false、回复为 mock 内容；
//   3. 错误映射 E2E：401 / 429 / 500 各发一轮 → 断言 ChatResultDto.error 含对应中文提示；
//   4. 取消：正常流式发送，收到首个 delta 后立即 cancel → 记录取消耗时；
//   5. 连接测试矩阵：正常/慢首 token/整轮超时/断连/空响应/流内错误/非 SSE JSON；
//   6. DB 落库核对：分页读取最新消息（真实 chat_messages）。
//
//  证据：NSLog + `--selftest` 时落盘 Documents/ios-shell-selftest.log（simctl get_app_container 取出）。
//

import Foundation

@MainActor
enum ProviderAutomation {
    static let isEnabled = ProcessInfo.processInfo.arguments.contains("--provider-selftest")
    private static var didRun = false

    private static func configureFromFile(environment: AppEnvironment) async {
        let bridge = environment.bridge
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("provider-configure.json")
        guard let data = try? Data(contentsOf: url),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let base = object["base"] as? String,
              let model = object["model"] as? String,
              let key = object["key"] as? String
        else {
            SelfTestLog.add("provider configure FAILED: missing provider-configure.json")
            return
        }
        let save = bridge.saveProviderConfig(
            providerId: "custom", model: model, customUrl: base, apiKey: key, temperature: 0.95
        )
        let status = bridge.providerStatus()
        SelfTestLog.add("provider configure save='\(save)' mode=\(status.mode) keyConfigured=\(status.keyConfigured) summary=\(status.summary)")
        try? FileManager.default.removeItem(at: url)
    }

    private static func argValue(_ flag: String, default fallback: String) -> String {
        let args = ProcessInfo.processInfo.arguments
        guard let index = args.firstIndex(of: flag), index + 1 < args.count else { return fallback }
        return args[index + 1]
    }

    static func runIfNeeded(environment: AppEnvironment, router: AppRouter) async {
        // 测试专用：从 App 沙盒 Documents/provider-configure.json 读取供应商配置并保存，
        // 避免把 Key 写进仓库或命令行参数。普通用户路径不可达。
        if ProcessInfo.processInfo.arguments.contains("--provider-configure-file") {
            guard !didRun else { return }
            didRun = true
            await configureFromFile(environment: environment)
            return
        }
        guard isEnabled, !didRun else { return }
        didRun = true
        SelfTestLog.add("=== provider selftest start ===")

        let bridge = environment.bridge
        let base = argValue("--provider-base", default: "http://127.0.0.1:18766/v1/chat/completions")
        let model = argValue("--provider-model", default: "mock-model")
        let apiKey = argValue("--provider-key", default: "sk-mock-test-key")
        let fast = ProcessInfo.processInfo.arguments.contains("--provider-fast-timeouts")
        if fast {
            bridge.overrideProviderProbeTimeouts(firstTokenMs: 1_500, totalMs: 4_000)
        }
        SelfTestLog.add("provider args base=\(base) model=\(model) fastTimeouts=\(fast)")
        SelfTestLog.add("provider keystore diagnostics: \(bridge.providerKeyStoreDiagnostics())")
        SelfTestLog.add("provider probe timeouts: \(bridge.providerProbeTimeoutsSummary())")

        // 0. 初始状态 + 会话
        var status = bridge.providerStatus()
        SelfTestLog.add("provider status initial mode=\(status.mode) summary=\(status.summary)")
        var sessionId = ""
        if case .success(let sessions) = await bridge.loadSessions(), let first = sessions.first {
            sessionId = first.id
        }
        SelfTestLog.add("provider session=\(sessionId.isEmpty ? "-" : sessionId)")
        guard !sessionId.isEmpty else {
            SelfTestLog.add("provider ABORT no session")
            flush(status)
            return
        }

        // 1. 无配置 → 演示模式回退
        _ = bridge.clearProviderApiKey(providerId: "custom")
        let blankSave = bridge.saveProviderConfig(
            providerId: "custom", model: model, customUrl: base, apiKey: "", temperature: 0.95
        )
        status = bridge.providerStatus()
        SelfTestLog.add("provider demo-setup save='\(blankSave)' mode=\(status.mode) reason=\(status.reason)")
        let demoRound = await sendAndWait(bridge: bridge, text: "自检：无配置时应该走演示模式", sessionId: sessionId)
        SelfTestLog.add(
            "provider DEMO round demo=\(demoRound.isDemo) ok=\(demoRound.ok) " +
            "text=\(demoRound.text.prefix(60)) error=\(demoRound.error)"
        )
        let demoPassed = demoRound.isDemo && demoRound.ok && demoRound.text.contains("演示模式")

        // 2. 真实供应商：保存 Key（Keychain）+ 连接测试 + 发一句话
        let saveResult = bridge.saveProviderConfig(
            providerId: "custom", model: model, customUrl: base, apiKey: apiKey, temperature: 0.95
        )
        status = bridge.providerStatus()
        SelfTestLog.add("provider real-setup save='\(saveResult)' mode=\(status.mode) keyConfigured=\(status.keyConfigured)")
        let keyInSettings = bridge.settingsString(key: "api_key")
        SelfTestLog.add("provider plaintext-api_key-in-settings='\(keyInSettings)' (期望为空：Key 只进 Keychain)")

        let testNormal = await testConnection(
            bridge: bridge, providerId: "custom", model: model, customUrl: base, apiKey: ""
        )
        logTest("normal", testNormal)
        let realRound = await sendAndWait(bridge: bridge, text: "自检：真实模型流式回复", sessionId: sessionId)
        SelfTestLog.add(
            "provider REAL round demo=\(realRound.isDemo) ok=\(realRound.ok) aiId=\(realRound.aiMessageId) " +
            "text=\(realRound.text.prefix(80)) error=\(realRound.error)"
        )
        let realPassed = !realRound.isDemo && realRound.ok && !realRound.text.isEmpty

        // 3. 连接测试矩阵（整轮超时/首 token 超时/断连/空响应/流内错误/非 SSE）
        // 说明：上一请求「完成」（非取消）后立即发 dribble，才能量到「首 token 后整轮超时」。
        let matrix: [(String, String)] = [
            // 顺序说明：dribble（整轮超时）放在最前——Ktor Darwin 在上一请求被取消后
            // 短时间内会让下一个请求等待，放最前才能量到「首 token 后整轮超时」。
            ("dribble", "\(base)/dribble"),
            ("slow-first", "\(base)/slow-first"),
            ("disconnect", "\(base)/disconnect"),
            ("empty", "\(base)/empty"),
            ("error-event", "\(base)/error-event"),
            ("non-stream", "\(base)/non-stream"),
        ]
        var matrixResults: [String: Bool] = [:]
        for (name, url) in matrix {
            let result = await testConnection(
                bridge: bridge, providerId: "custom", model: model, customUrl: url, apiKey: ""
            )
            logTest(name, result)
            matrixResults[name] = classifyMatrix(name: name, result: result)
        }

        // 4. 错误映射 E2E（真实引擎 → ChatResultDto.error）
        let errorScenarios: [(String, String, String)] = [
            ("unauthorized", "401", "鉴权失败"),
            ("ratelimited", "429", "请求过于频繁"),
            ("server-error", "500", "故障"),
        ]
        var errorResults: [String: Bool] = [:]
        for (suffix, code, hint) in errorScenarios {
            let url = "\(base)/\(suffix)"
            let saved = bridge.saveProviderConfig(
                providerId: "custom", model: model, customUrl: url, apiKey: apiKey, temperature: 0.95
            )
            let round = await sendAndWait(bridge: bridge, text: "自检：错误映射 \(code)", sessionId: sessionId)
            let matched = round.error.contains("API error \(code)") && round.error.contains(hint)
            errorResults[suffix] = matched
            SelfTestLog.add(
                "provider ERROR-E2E \(suffix) saved='\(saved)' ok=\(round.ok) matched=\(matched) error=\(round.error.prefix(160))"
            )
        }

        // 5. 取消：真实网络在飞取消（dribble 长流：首 token 后每 3s 一个；放在最后避免影响矩阵计时）
        // 注意：取消路径桥不承诺 onDone 回调（ChatEngineBridge 取消时不回调），因此不 await，
        // 用「发出取消的耗时 + 服务器侧 BrokenPipe（mock stats/log）」作为证据。
        _ = bridge.saveProviderConfig(
            providerId: "custom", model: model, customUrl: "\(base)/dribble", apiKey: apiKey, temperature: 0.95
        )
        let cancelStart = Date()
        let cancelResultBox = RoundResultBox()
        let cancelBox = SendHandleBox()
        cancelBox.handle = bridge.sendChat(
            sessionId: sessionId,
            text: "自检：真实网络取消",
            onDelta: { delta in
                SelfTestLog.add("provider CANCEL delta received=\(delta.prefix(12))")
            },
            onDone: { result in
                cancelResultBox.result = result
            }
        )
        try? await Task.sleep(nanoseconds: 700_000_000)
        cancelBox.handle?.cancel()
        let cancelIssuedMs = Int(Date().timeIntervalSince(cancelStart) * 1000)
        try? await Task.sleep(nanoseconds: 1_500_000_000)
        SelfTestLog.add(
            "provider CANCEL network issuedAt=\(cancelIssuedMs)ms doneCallback=\(cancelResultBox.result != nil) " +
            "ok=\(cancelResultBox.result?.ok.description ?? "-") error=\(cancelResultBox.result?.error ?? "-")"
        )

        // 6. DB 落库核对（真实 chat_messages）
        if case .success(let page) = await bridge.loadMessagePage(sessionId: sessionId, beforeId: 0, limit: 6) {
            let lines = page.items.prefix(6).map { "\($0.id):isMe=\($0.isMe):\($0.text.prefix(24))" }
            SelfTestLog.add("provider DB newest=\(lines)")
            let hasRealAi = page.items.contains { !$0.isMe && $0.text.contains("mock") }
            SelfTestLog.add("provider DB hasRealMockReply=\(hasRealAi)")
            matrixResults["db-row"] = hasRealAi
        }

        // 7. 恢复可用的正常配置，便于随后 UI 截图/手动对话
        _ = bridge.saveProviderConfig(providerId: "custom", model: model, customUrl: base, apiKey: apiKey, temperature: 0.95)
        status = bridge.providerStatus()
        SelfTestLog.add("provider final status mode=\(status.mode) summary=\(status.summary)")

        // 8. UI 证据：自动导航到「模型供应商」页
        if ProcessInfo.processInfo.arguments.contains("--provider-open-settings") {
            router.selectedTab = .settings
            router.settingsPath.append(Route.settingsProviders)
            SelfTestLog.add("provider UI navigated to settingsProviders")
        }

        let summary: String = [
            "demoFallback=\(demoPassed)",
            "realStream=\(realPassed)",
            "errorMapping=\(errorResults)",
            "cancelIssuedMs=\(cancelIssuedMs)",
            "matrix=\(matrixResults)",
            "finalMode=\(status.mode)",
        ].joined(separator: "\n")
        SelfTestLog.add("provider selftest summary\n\(summary)")
        SelfTestLog.flushToDisk(summary: "provider selftest\n\(summary)")
    }

    // MARK: - helpers

    private static func sendAndWait(
        bridge: AppGraphClient,
        text: String,
        sessionId: String
    ) async -> ChatRoundResult {
        await withCheckedContinuation { continuation in
            _ = bridge.sendChat(
                sessionId: sessionId,
                text: text,
                onDelta: { _ in },
                onDone: { result in continuation.resume(returning: result) }
            )
        }
    }

    private static func sendAndCancelAfterFirstDelta(
        bridge: AppGraphClient,
        text: String,
        sessionId: String
    ) async -> ChatRoundResult {
        let box = SendHandleBox()
        return await withCheckedContinuation { continuation in
            let handle = bridge.sendChat(
                sessionId: sessionId,
                text: text,
                onDelta: { delta in
                    if !box.cancelled && !delta.isEmpty {
                        box.cancelled = true
                        box.handle?.cancel()
                        SelfTestLog.add("provider CANCEL triggered after first delta='\(delta.prefix(20))'")
                    }
                },
                onDone: { result in continuation.resume(returning: result) }
            )
            box.handle = handle
        }
    }

    private static func testConnection(
        bridge: AppGraphClient,
        providerId: String,
        model: String,
        customUrl: String,
        apiKey: String
    ) async -> ProviderTestResultItem {
        await withCheckedContinuation { continuation in
            _ = bridge.testProviderConnection(
                providerId: providerId,
                model: model,
                customUrl: customUrl,
                apiKey: apiKey,
                temperature: 0.0,
                onDone: { result in continuation.resume(returning: result) }
            )
        }
    }

    private static func logTest(_ name: String, _ result: ProviderTestResultItem) {
        SelfTestLog.add(
            "provider TEST \(name) ok=\(result.ok) latency=\(result.latencyMs)ms " +
            "message=\(result.message.prefix(160)) preview=\(result.replyPreview.prefix(40))"
        )
    }

    /// 每个场景的期望：返回是否通过。
    private static func classifyMatrix(name: String, result: ProviderTestResultItem) -> Bool {
        let message = result.message
        switch name {
        case "slow-first", "dribble":
            return !result.ok && (message.contains("超时") || message.lowercased().contains("timed out"))
        case "disconnect":
            return !result.ok && (message.contains("空内容") || message.contains("网络"))
        case "empty":
            return !result.ok && message.contains("空内容")
        case "error-event":
            return !result.ok && message.contains("mock stream error")
        case "non-stream":
            return result.ok && result.replyPreview.contains("非流式")
        default:
            return result.ok
        }
    }

    private static func flush(_ status: ProviderStatusItem) {
        SelfTestLog.flushToDisk(summary: "provider selftest aborted, finalMode=\(status.mode)")
    }

    private final class SendHandleBox {
        var handle: ChatSendHandle?
        var cancelled = false
    }

    private final class RoundResultBox {
        var result: ChatRoundResult?
    }
}
