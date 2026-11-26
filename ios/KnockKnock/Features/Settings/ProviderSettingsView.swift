//
//  ProviderSettingsView.swift
//  T37「模型供应商」设置页（11 家供应商 + 自定义端点 + API Key（Keychain）+ 连接测试），
//  P4 敲敲视觉改造：胶囊行供应商列表 / 描边等宽表单 / 自绘滑杆 / 连接测试状态（成功黄描边、失败红描边）。
//
//  数据全部走 Shared.ProviderBridge（真实 SettingsRepository + Keychain），业务逻辑零改动：
//  - 保存后写入 provider / model_name / custom_url / ai_temperature，Key 只进 Keychain；
//  - 聊天客户端每次发送前重新解析配置 → 本页保存后**下一次发送立即生效**，无需重启；
//  - 未配置时聊天回退演示模式，本页与聊天页都会显示状态提示。
//
//  验证钩子：`--provider-selftest`（见 ProviderAutomation.swift）；截图钩子：`--settings-vision`。
//

import SwiftUI

struct ProviderSettingsView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    @State private var providers: [ProviderItem] = []
    @State private var status: ProviderStatusItem?
    @State private var selection: ProviderSelectionItem?

    // 编辑态
    @State private var editingId: String = ""
    @State private var editingName: String = ""
    @State private var model: String = ""
    @State private var customUrl: String = ""
    @State private var apiKey: String = ""
    @State private var temperature: Double = 0.95
    @State private var requiresCustomUrl = false
    @State private var modelSuggestions: [String] = []
    @State private var keyConfigured = false
    @State private var keyPreview = ""

    // 反馈
    @State private var saveMessage = ""
    @State private var saveOK = false
    @State private var testing = false
    @State private var testResult: ProviderTestResultItem?
    @State private var testHandle: ProviderTestHandle?

    // T46 识图模型（图片聊天）
    @State private var visionStatus: VisionStatusItem?
    @State private var visionProvider: String = "ali"
    @State private var visionEndpoint: String = ""
    @State private var visionModel: String = ""
    @State private var visionApiKey: String = ""
    @State private var visionSaveMessage = ""
    @State private var visionSaveOK = false

    @State private var scrollOffset: CGFloat = 0

    var body: some View {
        ZStack {
            AmbientBackground(parallax: scrollOffset, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(title: "模型供应商", showsBack: true, onBack: { dismiss() })

                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(spacing: KnockTokens.spacingXL) {
                            statusGroup
                            providerListGroup
                            if !editingId.isEmpty {
                                editorGroup
                            }
                            visionGroup
                                .id("vision-card")
                            helpGroup
                        }
                        .padding(.horizontal, KnockTokens.screenPadding)
                        .padding(.top, KnockTokens.spacingM)
                        .padding(.bottom, KnockTokens.spacingXXL)
                    }
                    .scrollIndicators(.hidden)
                    .settingsKnockScrollEdgeHidden()
                    .onScrollGeometryChange(for: CGFloat.self) { geometry in
                        geometry.contentOffset.y
                    } action: { _, newValue in
                        scrollOffset = newValue
                    }
                    .task {
                        // 截图证据钩子：`--settings-vision` 自动滚到「识图模型」section。
                        guard ProcessInfo.processInfo.arguments.contains("--settings-vision") else { return }
                        try? await Task.sleep(nanoseconds: 900_000_000)
                        withAnimation(.none) { proxy.scrollTo("vision-card", anchor: .top) }
                    }
                }
            }
        }
        .navigationTitle("模型供应商")
        .settingsKnockNavigationChrome()
        .task {
            reload()
            reloadVision()
        }
    }

    // MARK: - 状态

    private var statusGroup: some View {
        SettingsKnockGroup("当前状态", systemImage: "bolt.horizontal.circle") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    HStack(spacing: KnockTokens.spacingM) {
                        KnockRowGlyph(
                            systemImage: status?.isDemo == false ? "bolt.horizontal.circle.fill" : "play.circle.fill",
                            tint: status?.isDemo == false ? KnockTokens.electric : KnockTokens.textSecondary,
                            size: 36
                        )
                        VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                            KnockText(
                                status?.isDemo == false ? "真实模型已启用" : "当前为演示模式",
                                role: .rowTitle,
                                lineLimit: 1
                            )
                            KnockText(
                                status?.providerName ?? "读取中…",
                                role: .caption,
                                color: KnockTokens.textSecondary,
                                lineLimit: 1
                            )
                        }
                        Spacer(minLength: KnockTokens.spacingS)
                        KnockBadge(
                            text: status?.isDemo == false ? "真实" : "演示",
                            variant: status?.isDemo == false ? .accent : .neutral
                        )
                    }

                    if let status {
                        KnockText(
                            status.summary,
                            role: .caption,
                            color: KnockTokens.textSecondary,
                            lineSpacing: 2
                        )
                        SettingsKnockInfoLine("模型", status.model, monospaced: true)
                        SettingsKnockInfoLine("端点", status.endpoint, monospaced: true)
                        if status.isDemo {
                            SettingsKnockStatusBanner(
                                kind: .info,
                                title: "未配置时聊天使用本地演示回复",
                                detail: "填写任一供应商的 API Key 并保存后，下一句话即走真实模型。"
                            )
                        }
                    } else {
                        HStack(spacing: KnockTokens.spacingS) {
                            ProgressView().controlSize(.small)
                            KnockText("读取供应商状态…", role: .caption, color: KnockTokens.textSecondary)
                        }
                    }
                }
            }
        }
    }

    // MARK: - 供应商列表（胶囊行）

    private var providerListGroup: some View {
        SettingsKnockGroup("供应商（11 家）", systemImage: "server.rack") {
            VStack(spacing: KnockTokens.spacingS) {
                ForEach(providers) { provider in
                    Button {
                        beginEditing(provider)
                    } label: {
                        KnockListRow(
                            title: provider.name,
                            subtitle: provider.requiresCustomUrl
                                ? "自定义端点（OpenAI 兼容）"
                                : provider.endpointHint
                        ) {
                            KnockRowGlyph(
                                systemImage: provider.selected ? "largecircle.fill.circle" : "circle",
                                tint: provider.selected ? KnockTokens.electric : KnockTokens.textSecondary
                            )
                        } trailing: {
                            HStack(spacing: KnockTokens.spacingS) {
                                if provider.selected {
                                    KnockBadge(text: "当前", variant: .accent)
                                } else if provider.configured {
                                    KnockBadge(text: "已配置", variant: .neutral)
                                }
                                KnockChevron()
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: - 配置编辑

    private var editorGroup: some View {
        SettingsKnockGroup("配置 · \(editingName)", systemImage: "slider.horizontal.3") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    HStack(spacing: KnockTokens.spacingS) {
                        if keyConfigured {
                            KnockBadge(text: "Key \(keyPreview)", variant: .neutral)
                        } else {
                            KnockBadge(text: "未配置 Key", variant: .danger)
                        }
                        Spacer(minLength: 0)
                    }

                    SettingsKnockField(
                        "模型",
                        text: $model,
                        placeholder: "模型名，如 deepseek-v4-flash",
                        footnote: nil
                    )

                    if !modelSuggestions.isEmpty {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: KnockTokens.spacingS) {
                                ForEach(modelSuggestions, id: \.self) { suggestion in
                                    KnockChip(
                                        title: suggestion,
                                        isSelected: model == suggestion,
                                        action: { model = suggestion }
                                    )
                                }
                            }
                            .padding(.vertical, 1)
                        }
                    }

                    if requiresCustomUrl {
                        SettingsKnockField(
                            "API 地址（OpenAI 兼容）",
                            text: $customUrl,
                            placeholder: "https://your-host/v1",
                            footnote: "填到 /v1 即可，客户端会自动补 /chat/completions；也可直接填完整 endpoint。",
                            keyboardType: .URL
                        )
                    }

                    SettingsKnockField(
                        "API Key",
                        text: $apiKey,
                        placeholder: keyConfigured ? "已保存 \(keyPreview)，留空则保持不变" : "粘贴 API Key（保存到系统 Keychain）",
                        isSecure: true,
                        footnote: "Key 只写入 Keychain，不落 NSUserDefaults、不进备份包。"
                    )

                    SettingsKnockSliderRow(
                        title: "温度",
                        valueText: String(format: "%.2f", temperature),
                        value: $temperature,
                        range: 0...2,
                        step: 0.05,
                        hint: nil
                    )

                    HStack(spacing: KnockTokens.spacingM) {
                        Button {
                            save()
                        } label: {
                            HStack(spacing: KnockTokens.spacingS) {
                                Image(systemName: "checkmark.circle.fill")
                                    .font(.system(size: 14, weight: .heavy))
                                KnockText("保存", role: .rowTitle, color: KnockTokens.onElectric, lineLimit: 1)
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(KnockPrimaryButtonStyle())

                        Button {
                            runTest()
                        } label: {
                            HStack(spacing: 6) {
                                if testing {
                                    ProgressView().controlSize(.small)
                                } else {
                                    Image(systemName: "network")
                                        .font(.system(size: 13, weight: .heavy))
                                }
                                KnockText(testing ? "测试中…" : "连接测试", role: .rowTitle, lineLimit: 1)
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(KnockSecondaryButtonStyle())
                        .disabled(testing || model.trimmingCharacters(in: .whitespaces).isEmpty)
                    }

                    if testing {
                        SettingsKnockProgressBar()
                    }

                    if keyConfigured {
                        Button(role: .destructive) {
                            let ok = environment.bridge.clearProviderApiKey(providerId: editingId)
                            saveMessage = ok ? "已清除 Key" : "清除 Key 失败"
                            saveOK = ok
                            reload()
                        } label: {
                            HStack(spacing: KnockTokens.spacingS) {
                                Image(systemName: "trash")
                                    .font(.system(size: 12, weight: .heavy))
                                KnockText("清除已保存的 API Key", role: .captionStrong, color: KnockTokens.dangerBright, lineLimit: 1)
                                Spacer(minLength: 0)
                            }
                        }
                        .buttonStyle(.plain)
                    }

                    if !saveMessage.isEmpty {
                        SettingsKnockStatusBanner(
                            kind: saveOK ? .success : .failure,
                            title: saveMessage
                        )
                    }

                    if let testResult {
                        testResultCard(testResult)
                    }
                }
            }
        }
    }

    /// 连接测试结果：成功 = 黄描边、失败 = 红描边（计划书配色纪律）。
    private func testResultCard(_ result: ProviderTestResultItem) -> some View {
        var detail: [String] = []
        if result.latencyMs > 0 {
            detail.append("首 token \(result.latencyMs)ms")
        }
        if !result.replyPreview.isEmpty {
            detail.append("回复预览：\(result.replyPreview)")
        }
        if !result.message.isEmpty && result.message != "连接成功" {
            detail.append(result.message)
        }
        detail.append("\(result.providerName) · \(result.model)")
        return SettingsKnockStatusBanner(
            kind: result.ok ? .success : .failure,
            title: result.ok ? "连接成功" : "连接失败",
            detail: detail.joined(separator: "\n")
        )
    }

    // MARK: - 识图模型（T46：图片聊天真实识图）

    private var visionGroup: some View {
        SettingsKnockGroup("识图模型（图片聊天）", systemImage: "eye.circle") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    HStack(spacing: KnockTokens.spacingM) {
                        KnockRowGlyph(
                            systemImage: visionStatus?.engineConfigured == true ? "eye.circle.fill" : "eye.slash.circle",
                            tint: visionStatus?.engineConfigured == true ? KnockTokens.electric : KnockTokens.textSecondary,
                            size: 36
                        )
                        VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                            KnockText(
                                visionStatus?.engineConfigured == true ? "识图已就绪" : "识图未配置",
                                role: .rowTitle,
                                lineLimit: 1
                            )
                            if let visionStatus {
                                KnockText(visionStatus.summary, role: .caption, color: KnockTokens.textSecondary, lineSpacing: 2)
                            }
                        }
                        Spacer(minLength: KnockTokens.spacingS)
                        if let visionStatus {
                            KnockBadge(
                                text: visionStatus.mode == "independent" ? "独立配置" : "自动",
                                variant: visionStatus.engineConfigured ? .accent : .neutral
                            )
                        }
                    }

                    if let visionStatus {
                        HStack(spacing: KnockTokens.spacingS) {
                            Text("configured=\(visionStatus.configured ? "true" : "false")")
                            Text("engine=\(visionStatus.engineConfigured ? "true" : "false")")
                            Text("key=\(visionStatus.keyConfigured ? "已保存" : "无")")
                            Text("backend=\(visionStatus.keyStoreBackend)")
                        }
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundStyle(KnockTokens.textTertiary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    }

                    KnockDivider()

                    VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                        KnockText("供应商", role: .captionStrong, color: KnockTokens.textSecondary, lineLimit: 1)
                        KnockDropdown(
                            items: providers.map { KnockSegmentItem(value: $0.id, title: $0.name, systemImage: nil) },
                            selection: $visionProvider
                        )
                        .onChange(of: visionProvider) { _, newValue in
                            guard let provider = providers.first(where: { $0.id == newValue }) else { return }
                            guard !provider.requiresCustomUrl else {
                                // 自定义端点：保留用户已填的端点/模型；「自填」只是聊天供应商的候选占位，不能当识图模型名。
                                if visionEndpoint.isEmpty {
                                    visionEndpoint = "https://your-host/v1/chat/completions"
                                }
                                return
                            }
                            visionEndpoint = provider.endpointHint
                            if visionModel.isEmpty {
                                visionModel = provider.defaultModel
                            }
                        }
                    }

                    SettingsKnockField(
                        "识图端点",
                        text: $visionEndpoint,
                        placeholder: "https://host/v1/chat/completions",
                        footnote: "OpenAI 兼容 chat/completions 端点；阿里云原生 dashscope 多模态端点亦可（不含 /compatible-mode/ 时走 Qwen-VL 协议）。",
                        keyboardType: .URL
                    )

                    SettingsKnockField(
                        "识图模型名",
                        text: $visionModel,
                        placeholder: "qwen3-vl-plus / gpt-4o-mini …"
                    )

                    SettingsKnockField(
                        "识图 API Key",
                        text: $visionApiKey,
                        placeholder: visionStatus?.keyConfigured == true
                            ? "已保存（留空保持不变，空串复用聊天 Key）"
                            : "粘贴识图 API Key（保存到 Keychain）",
                        isSecure: true,
                        footnote: "Key 只写 Keychain（模拟器未签名时回退沙盒私密文件，同供应商 Key 策略）；保存后图片聊天立即使用真实识图。"
                    )

                    HStack(spacing: KnockTokens.spacingM) {
                        Button(action: saveVision) {
                            HStack(spacing: KnockTokens.spacingS) {
                                Image(systemName: "checkmark.circle.fill")
                                    .font(.system(size: 14, weight: .heavy))
                                KnockText("保存识图配置", role: .rowTitle, color: KnockTokens.onElectric, lineLimit: 1)
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(KnockPrimaryButtonStyle())
                        .disabled(visionEndpoint.trimmingCharacters(in: .whitespaces).isEmpty || visionModel.trimmingCharacters(in: .whitespaces).isEmpty)

                        Button {
                            clearVision()
                        } label: {
                            HStack(spacing: KnockTokens.spacingS) {
                                Image(systemName: "trash")
                                    .font(.system(size: 13, weight: .heavy))
                                KnockText("清空（回到自动）", role: .rowTitle, lineLimit: 2)
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(KnockSecondaryButtonStyle())
                    }

                    if !visionSaveMessage.isEmpty {
                        SettingsKnockStatusBanner(
                            kind: visionSaveOK ? .success : .failure,
                            title: visionSaveMessage
                        )
                    }
                }
            }
        }
    }

    private func reloadVision() {
        let status = environment.bridge.visionStatus()
        visionStatus = status
        visionProvider = status.provider
        visionEndpoint = status.endpoint
        visionModel = status.model
        visionApiKey = ""
        SelfTestLog.add(
            "provider vision reload mode=\(status.mode) provider=\(status.provider) endpoint=\(status.endpoint) " +
            "model=\(status.model) keyConfigured=\(status.keyConfigured) configured=\(status.configured) " +
            "engineConfigured=\(status.engineConfigured) backend=\(status.keyStoreBackend)"
        )
    }

    private func saveVision() {
        let result = environment.bridge.saveVisionConfiguration(
            provider: visionProvider,
            endpoint: visionEndpoint,
            model: visionModel,
            apiKey: visionApiKey
        )
        visionApiKey = ""
        let status = environment.bridge.visionStatus()
        visionSaveOK = result.isEmpty && status.configured
        if result.isEmpty {
            visionSaveMessage = status.configured
                ? "已保存：图片聊天将使用真实识图（configured=true）"
                : "已保存，但 effectiveVisionConfiguration.configured=false（请填写 Key 或选择与聊天相同的供应商）"
        } else {
            visionSaveMessage = result
        }
        SelfTestLog.add("provider vision save ok=\(visionSaveOK) result='\(result)' configured=\(status.configured) engineConfigured=\(status.engineConfigured)")
        reloadVision()
    }

    private func clearVision() {
        let cleared = environment.bridge.clearVisionConfiguration()
        visionSaveOK = cleared
        visionSaveMessage = cleared ? "已清空识图配置（vision_mode=auto）" : "清空失败（Keychain 删除失败）"
        SelfTestLog.add("provider vision clear ok=\(cleared)")
        reloadVision()
    }

    private var helpGroup: some View {
        SettingsKnockGroup("说明", systemImage: "questionmark.circle") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                    SettingsKnockBullet("保存后无需重启：下一次发送消息即使用新配置。")
                    SettingsKnockBullet("连接测试会发一条最小请求（1 次补全），用于验证 Key / 端点 / 模型名。")
                    SettingsKnockBullet("未配置或无 Key 时聊天回退「演示模式」，聊天页顶部会显示提示。")
                    SettingsKnockBullet("Google / Anthropic 使用官方 OpenAI 兼容端点；其余 9 家为原生 OpenAI 兼容。")
                }
            }
        }
    }

    // MARK: - 行为（逻辑零改动）

    private func reload() {
        providers = environment.bridge.loadProviders()
        status = environment.bridge.providerStatus()
        selection = environment.bridge.providerSelection()
        if editingId.isEmpty, let current = providers.first(where: { $0.selected }) {
            beginEditing(current)
        } else if let editing = providers.first(where: { $0.id == editingId }) {
            // 保留编辑态，仅刷新 Key 状态与状态卡
            keyConfigured = editing.configured
            if let selection, selection.providerId == editingId {
                keyPreview = selection.keyPreview
            }
        }
        SelfTestLog.add(
            "provider page reload providers=\(providers.count) selected=\(providers.first(where: { $0.selected })?.id ?? "-") " +
            "mode=\(status?.mode ?? "-") summary=\(status?.summary ?? "-")"
        )
    }

    private func beginEditing(_ provider: ProviderItem) {
        editingId = provider.id
        editingName = provider.name
        requiresCustomUrl = provider.requiresCustomUrl
        modelSuggestions = provider.models
        let selection = environment.bridge.providerSelection()
        if selection.providerId == provider.id {
            model = selection.model
            customUrl = selection.customUrl
            temperature = selection.temperature
            keyConfigured = selection.keyConfigured
            keyPreview = selection.keyPreview
        } else {
            model = provider.defaultModel
            customUrl = provider.requiresCustomUrl ? provider.endpointHint : ""
            temperature = 0.95
            keyConfigured = provider.configured
            keyPreview = ""
        }
        apiKey = ""
        saveMessage = ""
        saveOK = false
        testResult = nil
        SelfTestLog.add("provider edit id=\(provider.id) name=\(provider.name) model=\(model) keyConfigured=\(keyConfigured)")
    }

    private func save() {
        let result = environment.bridge.saveProviderConfig(
            providerId: editingId,
            model: model,
            customUrl: customUrl,
            apiKey: apiKey,
            temperature: temperature
        )
        saveOK = result.isEmpty
        saveMessage = result.isEmpty ? "已保存：\(editingName) · \(model)（下一轮发送生效）" : result
        apiKey = ""
        SelfTestLog.add("provider save id=\(editingId) model=\(model) ok=\(saveOK) message=\(saveMessage)")
        reload()
    }

    private func runTest() {
        testHandle?.cancel()
        testing = true
        testResult = nil
        SelfTestLog.add("provider test start id=\(editingId) model=\(model) url=\(customUrl)")
        testHandle = environment.bridge.testProviderConnection(
            providerId: editingId,
            model: model,
            customUrl: customUrl,
            apiKey: apiKey,
            temperature: temperature,
            onDone: { result in
                testing = false
                testResult = result
                SelfTestLog.add(
                    "provider test done ok=\(result.ok) latency=\(result.latencyMs)ms " +
                    "message=\(result.message) preview=\(result.replyPreview)"
                )
            }
        )
    }
}