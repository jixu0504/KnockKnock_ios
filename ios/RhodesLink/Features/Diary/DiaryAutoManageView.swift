//
//  DiaryAutoManageView.swift
//  T61 日记生成管理（从干员日记页工具栏进入）：
//  - 顶部总开关（settings diary_auto_enabled，默认 false = Android oracle 无自动日记调度）；
//  - 147 位干员列表：头像 / 名字 / 最近日记时间 / 单干员自动生成 Toggle / 「立即生成」按钮 /
//    生成中与失败状态；「全部开启 / 全部关闭」「补齐今日」快捷；
//  - 数据全部来自 shared `DiaryAutoGlue`（真实 diaries 表 + settings + 真实模型；未配置供应商时
//    shared 侧生成确定性演示内容并在页头提示「演示模式」）；无 mock DB；
//  - `--diary-auto-selftest`：自动打开本页并跑 UI 链路自检（列表/开关/生成后），写
//    Documents/diary-auto-selftest.log，页面顶部展示结论。
//
//  敲敲视觉（P3）：隐藏系统导航栏 → `KnockHeader`（返回 + 刷新胶囊）；
//  搜索 = 胶囊搜索条；开关 = 黄色 tint 的系统 Toggle（选中态语义）；
//  干员行 = 胶囊卡片（头像 + 名称 + 今日角标 + 开关 + 立即生成）；主操作「补齐今日」= 黄胶囊。
//

import SwiftUI

@MainActor
struct DiaryAutoManageView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    @State private var status: DiaryAutoStatusItem?
    @State private var isLoading = false
    @State private var errorText: String?
    @State private var generatingIds: Set<String> = []
    @State private var rowErrors: [String: String] = [:]
    @State private var banner = ""
    @State private var isTicking = false
    @State private var automationResult = ""
    @State private var searchText = ""

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(title: "日记生成管理", showsBack: true, onBack: { dismiss() }, trailing: {
                    KnockCapsuleIconButton(
                        systemImage: "arrow.clockwise",
                        variant: .neutral,
                        accessibilityLabel: "刷新日记生成状态"
                    ) {
                        Task { await loadStatus() }
                    }
                })

                ScrollView {
                    LazyVStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                        searchBar
                        headerPanel
                        shortcutPanel
                        if DiaryAutoAutomation.isEnabled {
                            automationPanel
                        }
                        if let errorText {
                            KnockErrorPanel(title: "读取失败", message: errorText) {
                                Task { await loadStatus() }
                            }
                        }
                        if !banner.isEmpty {
                            bannerPanel(banner)
                        }
                        if isLoading && status == nil {
                            KnockLoadingPanel("正在读取代理人日记生成状态…")
                        } else if let status, status.entries.isEmpty {
                            emptyPanel
                        } else if let status {
                            ForEach(filteredEntries(status)) { entry in
                                operatorRow(entry)
                            }
                        }
                    }
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.top, KnockTokens.spacingS)
                    .padding(.bottom, KnockTokens.spacingXL)
                }
                .scrollIndicators(.hidden)
                .knockScrollEdgeHidden()
                .refreshable { await loadStatus() }
            }
        }
        .knockHiddenNavigationBar()
        .task {
            await loadStatus()
            await runAutomationIfNeeded()
        }
    }

    private var searchBar: some View {
        KnockSearchBar(placeholder: "搜索代理人", text: $searchText)
    }

    // MARK: 头部 / 快捷操作

    private var headerPanel: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                HStack(spacing: KnockTokens.spacingM) {
                    KnockRowGlyph(systemImage: "sparkles.rectangle.stack.fill")
                    VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                        KnockText("代理人日记自动生成", role: .rowTitle, lineLimit: 1)
                        KnockText(subtitleText, role: .caption, color: KnockTokens.textSecondary, lineSpacing: 2)
                    }
                    Spacer(minLength: KnockTokens.spacingS)
                }

                KnockDivider()

                HStack(spacing: KnockTokens.spacingM) {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                        KnockText("总开关", role: .rowTitle, lineLimit: 1)
                        KnockText("关闭后所有代理人都不会自动写日记", role: .caption, color: KnockTokens.textSecondary, lineLimit: 2)
                    }
                    Spacer(minLength: KnockTokens.spacingS)
                    Toggle("", isOn: Binding(
                        get: { status?.masterEnabled ?? false },
                        set: { newValue in Task { await setMaster(newValue) } }
                    ))
                    .labelsHidden()
                    .tint(KnockTokens.electric)
                    .accessibilityLabel("日记自动生成总开关")
                }

                if status?.demoMode == true {
                    HStack(alignment: .top, spacing: KnockTokens.spacingS) {
                        Image(systemName: "info.circle.fill")
                            .font(.system(size: 11, weight: .heavy))
                            .foregroundStyle(KnockTokens.textSecondary)
                            .accessibilityHidden(true)
                        KnockText(
                            "当前未配置模型供应商：生成内容为本地演示内容，配置后自动使用真实模型。",
                            role: .caption,
                            color: KnockTokens.textSecondary,
                            lineSpacing: 2
                        )
                    }
                }
            }
        }
    }

    private var subtitleText: String {
        guard let status else { return "读取中…" }
        return "已开启 \(status.enabledCount)/\(status.totalCount) 位 · 今日已生成 \(status.todayGeneratedCount) 位 · 每轮最多补齐 \(status.dailyLimit) 位"
    }

    private var shortcutPanel: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                HStack(spacing: KnockTokens.spacingS) {
                    Button {
                        Task { await setAll(true) }
                    } label: {
                        Label("全部开启", systemImage: "checkmark.circle")
                            .font(.system(size: 13, weight: .semibold))
                    }
                    .buttonStyle(KnockSecondaryButtonStyle())

                    Button {
                        Task { await setAll(false) }
                    } label: {
                        Label("全部关闭", systemImage: "xmark.circle")
                            .font(.system(size: 13, weight: .semibold))
                    }
                    .buttonStyle(KnockSecondaryButtonStyle())
                }

                Button {
                    Task { await tickNow() }
                } label: {
                    HStack(spacing: KnockTokens.spacingS) {
                        if isTicking {
                            ProgressView().controlSize(.small).tint(KnockTokens.onElectric)
                        } else {
                            Image(systemName: "wand.and.stars")
                                .font(.system(size: 13, weight: .heavy))
                        }
                        Text(isTicking ? "正在补齐今日日记…" : "补齐今日日记（本轮最多 \(status?.dailyLimit ?? 3) 位）")
                    }
                }
                .buttonStyle(KnockPrimaryButtonStyle())
                .disabled(isTicking || status == nil)
                .opacity(isTicking || status == nil ? 0.35 : 1)
            }
        }
    }

    private var automationPanel: some View {
        KnockPanel(padding: KnockTokens.spacingM) {
            VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                KnockText("T61 自检", role: .captionStrong, lineLimit: 1)
                KnockText(
                    automationResult.isEmpty
                        ? (DiaryAutoAutomation.resultText.isEmpty ? "等待自检…" : DiaryAutoAutomation.resultText)
                        : automationResult,
                    role: .caption,
                    color: KnockTokens.textSecondary,
                    lineSpacing: 2
                )
            }
        }
    }

    private func bannerPanel(_ message: String) -> some View {
        KnockPanel(padding: KnockTokens.spacingM) {
            HStack(alignment: .top, spacing: KnockTokens.spacingS) {
                Image(systemName: "text.bubble.fill")
                    .font(.system(size: 12, weight: .heavy))
                    .foregroundStyle(KnockTokens.textSecondary)
                    .accessibilityHidden(true)
                KnockText(message, role: .caption, color: KnockTokens.textPrimary, lineSpacing: 3)
            }
        }
    }

    private var emptyPanel: some View {
        KnockEmptyState(
            title: "还没有代理人",
            message: "代理人列表为空，先到「联系人」页添加代理人后再开启日记自动生成。"
        )
    }

    // MARK: 干员行

    private func filteredEntries(_ status: DiaryAutoStatusItem) -> [DiaryOperatorAutoItem] {
        let keyword = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !keyword.isEmpty else { return status.entries }
        return status.entries.filter { $0.operatorName.contains(keyword) || $0.operatorId.localizedCaseInsensitiveContains(keyword) }
    }

    private func operatorRow(_ entry: DiaryOperatorAutoItem) -> some View {
        let isGenerating = generatingIds.contains(entry.operatorId)
        return KnockCapsuleCard {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                HStack(spacing: KnockTokens.spacingM) {
                    KnockAvatar(avatarId: entry.avatarId, name: entry.operatorName, size: 42)

                    VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                        HStack(spacing: KnockTokens.spacingXS) {
                            KnockText(entry.operatorName, role: .rowTitle, lineLimit: 1)
                            if entry.todayGenerated {
                                KnockBadge(text: "今日已生成", variant: .neutral)
                            }
                        }
                        KnockText("最近日记：\(entry.lastDiaryText)", role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
                    }

                    Spacer(minLength: KnockTokens.spacingXS)

                    VStack(alignment: .trailing, spacing: KnockTokens.spacingXS) {
                        Toggle("", isOn: Binding(
                            get: { entry.autoEnabled },
                            set: { newValue in Task { await toggle(entry, newValue) } }
                        ))
                        .labelsHidden()
                        .tint(KnockTokens.electric)
                        .accessibilityLabel("\(entry.operatorName) 自动生成")

                        Button {
                            Task { await generate(entry) }
                        } label: {
                            HStack(spacing: 4) {
                                if isGenerating {
                                    ProgressView().controlSize(.mini)
                                }
                                Text(isGenerating ? "生成中…" : "立即生成")
                                    .font(.system(size: 11, weight: .semibold))
                            }
                        }
                        .buttonStyle(KnockSecondaryButtonStyle(fullWidth: false, height: 28))
                        .disabled(isGenerating)
                        .opacity(isGenerating ? 0.35 : 1)
                        .accessibilityLabel("立即生成 \(entry.operatorName) 的日记")
                    }
                }

                if let error = rowErrors[entry.operatorId] {
                    HStack(alignment: .top, spacing: KnockTokens.spacingXS) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .font(.system(size: 10, weight: .heavy))
                            .foregroundStyle(KnockTokens.dangerBright)
                            .accessibilityHidden(true)
                        KnockText(error, role: .caption, color: KnockTokens.dangerBright, lineSpacing: 2, lineLimit: 2)
                    }
                }
            }
        }
    }

    // MARK: 数据操作

    private func loadStatus() async {
        if status == nil { isLoading = true }
        let result = await environment.bridge.loadDiaryAutoStatus()
        switch result {
        case .success(let value):
            status = value
            errorText = nil
        case .failure(let error):
            errorText = error.message
            DiaryAutoAutomation.add("FAIL ui.load \(error.message)")
        }
        isLoading = false
    }

    private func setMaster(_ enabled: Bool) async {
        let result = await environment.bridge.setDiaryAutoMaster(enabled: enabled)
        switch result {
        case .success(let count):
            banner = enabled ? "总开关已开启（\(count) 位代理人开启自动生成）" : "总开关已关闭，所有代理人都不会自动写日记"
            DiaryAutoAutomation.add("ui.master.enabled=\(enabled) count=\(count)")
        case .failure(let error):
            banner = "总开关保存失败：\(error.message)"
            DiaryAutoAutomation.add("FAIL ui.master \(error.message)")
        }
        await loadStatus()
    }

    private func toggle(_ entry: DiaryOperatorAutoItem, _ enabled: Bool) async {
        let result = await environment.bridge.setDiaryOperatorAuto(operatorId: entry.operatorId, enabled: enabled)
        switch result {
        case .success(let effective):
            banner = "\(entry.operatorName)：自动生成已\(enabled ? "开启" : "关闭")"
            if enabled && !effective {
                banner = "\(entry.operatorName)：已开启，但总开关关闭中，不会自动生成"
            }
        case .failure(let error):
            rowErrors[entry.operatorId] = error.message
            banner = "\(entry.operatorName)：开关保存失败"
        }
        await loadStatus()
    }

    private func setAll(_ enabled: Bool) async {
        let result = await environment.bridge.setAllDiaryAuto(enabled: enabled)
        switch result {
        case .success(let count):
            banner = enabled ? "已全部开启（\(count) 位代理人）" : "已全部关闭"
        case .failure(let error):
            banner = "批量开关失败：\(error.message)"
        }
        await loadStatus()
    }

    private func generate(_ entry: DiaryOperatorAutoItem) async {
        guard !generatingIds.contains(entry.operatorId) else { return }
        generatingIds.insert(entry.operatorId)
        rowErrors.removeValue(forKey: entry.operatorId)
        let result = await environment.bridge.generateDiaryNow(operatorId: entry.operatorId, force: false)
        generatingIds.remove(entry.operatorId)
        switch result {
        case .success(let item):
            if item.created {
                banner = "\(item.operatorName)：已生成第 \(item.version) 篇（\(item.contentLength) 字，\(item.date)）"
            } else {
                banner = "\(item.operatorName)：今天已经生成过，未重复生成"
            }
            if !item.message.isEmpty {
                banner += " · \(item.message)"
            }
            DiaryAutoAutomation.add("ui.generate operator=\(item.operatorId) id=\(item.id) created=\(item.created) len=\(item.contentLength) version=\(item.version)")
        case .failure(let error):
            rowErrors[entry.operatorId] = error.message
            banner = "\(entry.operatorName)：生成失败"
            DiaryAutoAutomation.add("FAIL ui.generate operator=\(entry.operatorId) \(error.message)")
        }
        await loadStatus()
    }

    private func tickNow() async {
        guard !isTicking else { return }
        isTicking = true
        let result = await environment.bridge.runDiaryAutoTick(limit: 0)
        isTicking = false
        switch result {
        case .success(let item):
            banner = item.message
            DiaryAutoAutomation.add("ui.tick generated=\(item.generated) skipped=\(item.skippedExisting) failed=\(item.failed) limit=\(item.limit)")
        case .failure(let error):
            banner = "补齐失败：\(error.message)"
            DiaryAutoAutomation.add("FAIL ui.tick \(error.message)")
        }
        await loadStatus()
    }

    // MARK: `--diary-auto-selftest` UI 链路

    private func runAutomationIfNeeded() async {
        guard DiaryAutoAutomation.beginIfNeeded() else { return }

        // 等待 Kotlin 侧 debugRunSelfTest（IosAppGraph 启动钩子）完成 mock 生成与开关预置。
        try? await Task.sleep(nanoseconds: 4_000_000_000)
        await loadStatus()

        guard let initial = status else {
            DiaryAutoAutomation.add("FAIL ui.status nil")
            DiaryAutoAutomation.finish(summary: "读取状态失败")
            return
        }
        let initialEnabledIds = initial.entries.filter { $0.autoEnabled }.map { $0.operatorId }
        DiaryAutoAutomation.add("ui.status total=\(initial.totalCount) enabled=\(initial.enabledCount) today=\(initial.todayGeneratedCount) master=\(initial.masterEnabled) demo=\(initial.demoMode)")
        DiaryAutoAutomation.add(initial.totalCount == 147 ? "PASS ui.list.count=147" : "FAIL ui.list.count=\(initial.totalCount)")
        DiaryAutoAutomation.add(initial.enabledCount >= 2 ? "PASS ui.enabled.preset=\(initial.enabledCount)" : "FAIL ui.enabled.preset=\(initial.enabledCount)")
        DiaryAutoAutomation.add(initial.todayGeneratedCount >= 2 ? "PASS ui.today.generated=\(initial.todayGeneratedCount)" : "FAIL ui.today.generated=\(initial.todayGeneratedCount)")
        DiaryAutoAutomation.add(initial.masterEnabled ? "PASS ui.master.on" : "FAIL ui.master.on")
        let generatedRows = initial.entries.filter { $0.todayGenerated }
        DiaryAutoAutomation.add(generatedRows.isEmpty ? "FAIL ui.row.today" : "PASS ui.row.today count=\(generatedRows.count) first=\(generatedRows.first?.operatorId ?? "-")")
        automationResult = DiaryAutoAutomation.resultText

        // 截图窗口 1：列表 + 单干员开关 + 今日已生成徽标。
        try? await Task.sleep(nanoseconds: 9_000_000_000)

        // 阶段 2：全部开启（UI 按钮同一桥方法），截图窗口 2。
        let all = await environment.bridge.setAllDiaryAuto(enabled: true)
        if case .success(let count) = all {
            DiaryAutoAutomation.add("ui.allOn count=\(count)")
        } else {
            DiaryAutoAutomation.add("FAIL ui.allOn")
        }
        await loadStatus()
        DiaryAutoAutomation.add((status?.enabledCount ?? 0) >= 147 ? "PASS ui.allOn.enabled=\(status?.enabledCount ?? 0)" : "FAIL ui.allOn.enabled=\(status?.enabledCount ?? 0)")
        automationResult = DiaryAutoAutomation.resultText
        try? await Task.sleep(nanoseconds: 9_000_000_000)

        // 阶段 3：恢复初始的 2 位开启（避免影响用户设置），对第一位「立即生成」验证生成后状态，截图窗口 3。
        _ = await environment.bridge.setAllDiaryAuto(enabled: false)
        // 恢复总开关（全部关闭会同时把总开关写成 false）。
        _ = await environment.bridge.setDiaryAutoMaster(enabled: true)
        for id in initialEnabledIds {
            _ = await environment.bridge.setDiaryOperatorAuto(operatorId: id, enabled: true)
        }
        await loadStatus()
        if let firstId = initialEnabledIds.first ?? status?.entries.first?.operatorId {
            let result = await environment.bridge.generateDiaryNow(operatorId: firstId, force: true)
            switch result {
            case .success(let item):
                DiaryAutoAutomation.add(item.id > 0 ? "PASS ui.generateAfter id=\(item.id) len=\(item.contentLength) version=\(item.version)" : "FAIL ui.generateAfter")
            case .failure(let error):
                DiaryAutoAutomation.add("FAIL ui.generateAfter \(error.message)")
            }
        }
        await loadStatus()
        automationResult = DiaryAutoAutomation.resultText
        try? await Task.sleep(nanoseconds: 8_000_000_000)

        let finalStatus = status
        DiaryAutoAutomation.add(finalStatus?.masterEnabled == true ? "PASS ui.master.restored" : "FAIL ui.master.restored")
        DiaryAutoAutomation.add((finalStatus?.enabledCount ?? 0) >= 2 ? "PASS ui.enabled.restored=\(finalStatus?.enabledCount ?? 0)" : "FAIL ui.enabled.restored=\(finalStatus?.enabledCount ?? 0)")
        DiaryAutoAutomation.finish(summary: "干员 \(finalStatus?.totalCount ?? 0) 位 / 已开启 \(finalStatus?.enabledCount ?? 0) 位 / 今日已生成 \(finalStatus?.todayGeneratedCount ?? 0) 位 / 全部开启与立即生成 UI 链路已走通")
        automationResult = DiaryAutoAutomation.resultText
    }
}