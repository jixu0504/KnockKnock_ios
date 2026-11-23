//
//  ProviderSettingsView.swift
//  T37「模型供应商」设置页：11 家供应商列表 + 自定义端点 + API Key（Keychain）+ 连接测试。
//
//  数据全部走 Shared.ProviderBridge（真实 SettingsRepository + Keychain）：
//  - 保存后写入 provider / model_name / custom_url / ai_temperature，Key 只进 Keychain；
//  - 聊天客户端每次发送前重新解析配置 → 本页保存后**下一次发送立即生效**，无需重启；
//  - 未配置时聊天回退演示模式，本页与聊天页都会显示状态提示。
//
//  验证钩子：`--provider-selftest`（见 ProviderAutomation.swift）。
//

import SwiftUI

struct ProviderSettingsView: View {
    @EnvironmentObject private var environment: AppEnvironment

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

            ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 14) {
                    statusCard
                    providerListCard
                    if !editingId.isEmpty {
                        editorCard
                    }
                    visionCard
                        .id("vision-card")
                    helpCard
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
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
        .navigationTitle("模型供应商")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            reload()
            reloadVision()
        }
    }

    // MARK: - 状态卡

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: status?.isDemo == false ? "bolt.horizontal.circle.fill" : "play.circle.fill")
                    .foregroundStyle(status?.isDemo == false ? Color.green : GlassTokens.brandOrange)
                Text(status?.isDemo == false ? "真实模型已启用" : "当前为演示模式")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                if let status {
                    Text(status.providerName)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            if let status {
                Text(status.summary)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                infoLine("模型", status.model)
                infoLine("端点", status.endpoint, monospaced: true)
                if status.isDemo {
                    Text("未配置时聊天使用本地演示回复；填写任一供应商的 API Key 并保存后，下一句话即走真实模型。")
                        .font(.caption2)
                        .foregroundStyle(GlassTokens.brandOrange)
                        .fixedSize(horizontal: false, vertical: true)
                }
            } else {
                ProgressView().controlSize(.small)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 22)
    }

    // MARK: - 11 家供应商列表

    private var providerListCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionTitle(text: "供应商（11 家）", systemImage: "server.rack")
                .padding(.horizontal, 14)
                .padding(.top, 14)
                .padding(.bottom, 8)

            ForEach(providers) { provider in
                Button {
                    beginEditing(provider)
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: provider.selected ? "largecircle.fill.circle" : "circle")
                            .foregroundStyle(provider.selected ? GlassTokens.brandOrange : Color.secondary)
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 6) {
                                Text(provider.name)
                                    .font(.body)
                                    .foregroundStyle(.primary)
                                if provider.configured {
                                    Text("已配置")
                                        .font(.system(size: 10, weight: .semibold))
                                        .foregroundStyle(.green)
                                        .padding(.horizontal, 6)
                                        .padding(.vertical, 2)
                                        .background(Color.green.opacity(0.12), in: Capsule())
                                }
                            }
                            Text(provider.requiresCustomUrl ? "自定义端点（OpenAI 兼容）" : provider.endpointHint)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 11)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                if provider.id != providers.last?.id {
                    Divider().padding(.leading, 44).opacity(0.4)
                }
            }
            .padding(.bottom, 6)
        }
        .cardSurface(cornerRadius: 20)
    }

    // MARK: - 编辑卡

    private var editorCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "slider.horizontal.3")
                    .foregroundStyle(GlassTokens.brandOrange)
                Text("配置 · \(editingName)")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                if keyConfigured {
                    Text(keyPreview)
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                }
            }

            // 模型
            VStack(alignment: .leading, spacing: 5) {
                Text("模型").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                TextField("模型名，如 deepseek-v4-flash", text: $model)
                    .font(.callout.monospaced())
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(10)
                    .background(Color.secondary.opacity(0.10), in: RoundedRectangle(cornerRadius: 10))
                if !modelSuggestions.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(modelSuggestions, id: \.self) { suggestion in
                                Button(suggestion) { model = suggestion }
                                    .font(.caption2)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 4)
                                    .background(
                                        model == suggestion ? GlassTokens.brandOrange.opacity(0.18) : Color.secondary.opacity(0.10),
                                        in: Capsule()
                                    )
                                    .buttonStyle(.plain)
                            }
                        }
                    }
                }
            }

            // 自定义端点
            if requiresCustomUrl {
                VStack(alignment: .leading, spacing: 5) {
                    Text("API 地址（OpenAI 兼容）").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    TextField("https://your-host/v1", text: $customUrl)
                        .font(.callout.monospaced())
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .padding(10)
                        .background(Color.secondary.opacity(0.10), in: RoundedRectangle(cornerRadius: 10))
                    Text("填到 /v1 即可，客户端会自动补 /chat/completions；也可直接填完整 endpoint。")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
            }

            // Key
            VStack(alignment: .leading, spacing: 5) {
                Text("API Key").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                SecureField(keyConfigured ? "已保存 \(keyPreview)，留空则保持不变" : "粘贴 API Key（保存到系统 Keychain）", text: $apiKey)
                    .font(.callout.monospaced())
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(10)
                    .background(Color.secondary.opacity(0.10), in: RoundedRectangle(cornerRadius: 10))
                Text("Key 只写入 Keychain，不落 NSUserDefaults、不进备份包。")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }

            // 温度
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text("温度").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    Spacer()
                    Text(String(format: "%.2f", temperature))
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                }
                Slider(value: $temperature, in: 0...2, step: 0.05)
                    .tint(GlassTokens.brandOrange)
            }

            // 操作
            HStack(spacing: 10) {
                Button {
                    save()
                } label: {
                    Label("保存", systemImage: "checkmark.circle.fill")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(GlassTokens.brandOrange.opacity(0.18), in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)

                Button {
                    runTest()
                } label: {
                    HStack(spacing: 6) {
                        if testing { ProgressView().controlSize(.small) }
                        else { Image(systemName: "network") }
                        Text(testing ? "测试中…" : "连接测试")
                    }
                    .font(.subheadline.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .disabled(testing || model.trimmingCharacters(in: .whitespaces).isEmpty)
            }

            if keyConfigured {
                Button(role: .destructive) {
                    let ok = environment.bridge.clearProviderApiKey(providerId: editingId)
                    saveMessage = ok ? "已清除 Key" : "清除 Key 失败"
                    saveOK = ok
                    reload()
                } label: {
                    Text("清除已保存的 API Key")
                        .font(.caption)
                        .foregroundStyle(.red)
                }
                .buttonStyle(.plain)
            }

            if !saveMessage.isEmpty {
                Text(saveMessage)
                    .font(.caption)
                    .foregroundStyle(saveOK ? .green : .red)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if let testResult {
                testResultCard(testResult)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
    }

    private func testResultCard(_ result: ProviderTestResultItem) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 6) {
                Image(systemName: result.ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                    .foregroundStyle(result.ok ? .green : .red)
                Text(result.ok ? "连接成功" : "连接失败")
                    .font(.caption.weight(.semibold))
                if result.latencyMs > 0 {
                    Text("首 token \(result.latencyMs)ms")
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                }
            }
            if !result.replyPreview.isEmpty {
                Text("回复预览：\(result.replyPreview)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            if !result.message.isEmpty && result.message != "连接成功" {
                Text(result.message)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .textSelection(.enabled)
            }
            Text("\(result.providerName) · \(result.model)")
                .font(.system(size: 10))
                .foregroundStyle(.tertiary)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background((result.ok ? Color.green : Color.red).opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - 识图模型（T46：图片聊天真实识图）

    private var visionCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: visionStatus?.engineConfigured == true ? "eye.circle.fill" : "eye.slash.circle")
                    .foregroundStyle(visionStatus?.engineConfigured == true ? .green : GlassTokens.brandOrange)
                Text("识图模型（图片聊天）")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                if let visionStatus {
                    Text(visionStatus.mode == "independent" ? "独立配置" : "自动")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(visionStatus.engineConfigured ? .green : .secondary)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background((visionStatus.engineConfigured ? Color.green : Color.secondary).opacity(0.12), in: Capsule())
                }
            }

            if let visionStatus {
                Text(visionStatus.summary)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                HStack(spacing: 8) {
                    Text("configured=\(visionStatus.configured ? "true" : "false")")
                    Text("engine=\(visionStatus.engineConfigured ? "true" : "false")")
                    Text("key=\(visionStatus.keyConfigured ? "已保存" : "无")")
                    Text("backend=\(visionStatus.keyStoreBackend)")
                }
                .font(.system(size: 10, design: .monospaced))
                .foregroundStyle(.tertiary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            }

            Divider().opacity(0.4)

            VStack(alignment: .leading, spacing: 5) {
                Text("供应商").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                Picker("供应商", selection: $visionProvider) {
                    ForEach(providers) { provider in
                        Text(provider.name).tag(provider.id)
                    }
                }
                .pickerStyle(.menu)
                .tint(GlassTokens.brandOrange)
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

            VStack(alignment: .leading, spacing: 5) {
                Text("识图端点").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                TextField("https://host/v1/chat/completions", text: $visionEndpoint)
                    .font(.callout.monospaced())
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .padding(10)
                    .background(Color.secondary.opacity(0.10), in: RoundedRectangle(cornerRadius: 10))
                Text("OpenAI 兼容 chat/completions 端点；阿里云原生 dashscope 多模态端点亦可（不含 /compatible-mode/ 时走 Qwen-VL 协议）。")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            VStack(alignment: .leading, spacing: 5) {
                Text("识图模型名").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                TextField("qwen3-vl-plus / gpt-4o-mini …", text: $visionModel)
                    .font(.callout.monospaced())
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(10)
                    .background(Color.secondary.opacity(0.10), in: RoundedRectangle(cornerRadius: 10))
            }

            VStack(alignment: .leading, spacing: 5) {
                Text("识图 API Key").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                SecureField(
                    visionStatus?.keyConfigured == true ? "已保存（留空保持不变，空串复用聊天 Key）" : "粘贴识图 API Key（保存到 Keychain）",
                    text: $visionApiKey
                )
                .font(.callout.monospaced())
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(10)
                .background(Color.secondary.opacity(0.10), in: RoundedRectangle(cornerRadius: 10))
                Text("Key 只写 Keychain（模拟器未签名时回退沙盒私密文件，同供应商 Key 策略）；保存后图片聊天立即使用真实识图。")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            HStack(spacing: 10) {
                Button(action: saveVision) {
                    Label("保存识图配置", systemImage: "checkmark.circle.fill")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(GlassTokens.brandOrange.opacity(0.18), in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .disabled(visionEndpoint.trimmingCharacters(in: .whitespaces).isEmpty || visionModel.trimmingCharacters(in: .whitespaces).isEmpty)

                Button {
                    clearVision()
                } label: {
                    Label("清空（回到自动）", systemImage: "trash")
                        .font(.caption.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
            }

            if !visionSaveMessage.isEmpty {
                Text(visionSaveMessage)
                    .font(.caption2)
                    .foregroundStyle(visionSaveOK ? .green : .red)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
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

    private var helpCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionTitle(text: "说明", systemImage: "questionmark.circle")
            Text("• 保存后无需重启：下一次发送消息即使用新配置。")
            Text("• 连接测试会发一条最小请求（1 次补全），用于验证 Key / 端点 / 模型名。")
            Text("• 未配置或无 Key 时聊天回退「演示模式」，聊天页顶部会显示提示。")
            Text("• Google / Anthropic 使用官方 OpenAI 兼容端点；其余 9 家为原生 OpenAI 兼容。")
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .cardSurface(cornerRadius: 18)
    }

    // MARK: - 行为

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

    private func infoLine(_ title: String, _ value: String, monospaced: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(value)
                .font(monospaced ? .caption2.monospaced() : .caption2)
                .foregroundStyle(.primary)
                .lineLimit(2)
                .truncationMode(.middle)
                .textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
