//
//  DebugLogsView.swift
//  T53 调试日志页（设置 → 调试日志）：
//  - 数据源：shared `IosDebugLogger` 真实文件日志（Application Support/logs/rhodes.log + 归档）；
//  - 列表：本地时间 / 级别 / 来源 tag / 正文（默认 2 行截断，点按展开）；新的在前；
//  - 筛选：级别（全部/DEBUG/INFO/WARN/ERROR）+ 关键词（正文/tag 子串，忽略大小写）；
//  - 导出：生成 `Application Support/exports/rhodes-log-<epoch>.log` 后用 `ShareLink` 分享；
//  - 清空：二次确认后清空主文件 + 全部归档（shared `clearAll`）。
//
//  截图/演示钩子：`--settings-extra-demo-logs`（写入一组演示级别日志，仅当次启动）。
//
//  敲敲视觉（P3）：隐藏系统导航栏 → `KnockHeader`（返回 + 刷新胶囊）；
//  日志阅读器 = 黑底面板 + 等宽字体 + 灰字（错误行危险红）+ 可选中文本；无毛玻璃、无渐变。
//

import SwiftUI

struct DebugLogsView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    enum LevelFilter: String, CaseIterable, Identifiable {
        case all = "全部"
        case debug = "DEBUG"
        case info = "INFO"
        case warn = "WARN"
        case error = "ERROR"

        var id: String { rawValue }
    }

    @State private var snapshot: DebugLogSnapshotItem?
    @State private var levelFilter: LevelFilter = .all
    @State private var keyword = ""
    @State private var expandedIDs: Set<Int> = []
    @State private var exportedURL: URL?
    @State private var showClearConfirm = false
    @State private var statusMessage = ""
    @State private var statusIsError = false
    @State private var didRunDemo = false
    @State private var scrollOffset: CGFloat = 0

    private var client: SettingsExtraClient { SettingsExtraClient.shared }

    private var filteredEntries: [DebugLogEntryItem] {
        guard let snapshot else { return [] }
        let trimmedKeyword = keyword.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return snapshot.entries.filter { entry in
            let levelOK: Bool
            switch levelFilter {
            case .all: levelOK = true
            case .debug: levelOK = entry.level == "DEBUG"
            case .info: levelOK = entry.level == "INFO"
            case .warn: levelOK = entry.levelIsWarn
            case .error: levelOK = entry.levelIsError
            }
            guard levelOK else { return false }
            guard !trimmedKeyword.isEmpty else { return true }
            return entry.message.lowercased().contains(trimmedKeyword)
                || entry.tag.lowercased().contains(trimmedKeyword)
                || entry.level.lowercased().contains(trimmedKeyword)
        }
    }

    var body: some View {
        ZStack {
            AmbientBackground(parallax: scrollOffset, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(title: "调试日志", showsBack: true, onBack: { dismiss() }, trailing: {
                    knockRefreshButton
                })

                ScrollView {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                        statusPanel
                        filterPanel
                        entriesSection
                    }
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.top, KnockTokens.spacingS)
                    .padding(.bottom, KnockTokens.spacingXL)
                }
                .scrollIndicators(.hidden)
                .knockScrollEdgeHidden()
                .refreshable { reload() }
                .onScrollGeometryChange(for: CGFloat.self) { geometry in
                    geometry.contentOffset.y
                } action: { _, newValue in
                    scrollOffset = newValue
                }
            }
        }
        .knockHiddenNavigationBar()
        .task {
            writeDemoEntriesIfNeeded()
            reload()
        }
        .knockConfirmDialog(
            isPresented: $showClearConfirm,
            title: "清空全部日志？",
            message: "将删除 Application Support/logs/ 下的 rhodes.log 与全部归档文件，操作不可恢复。",
            confirmTitle: "清空",
            isDestructive: true
        ) {
            clearAll()
        }
    }

    private var knockRefreshButton: some View {
        KnockCapsuleIconButton(
            systemImage: "arrow.clockwise",
            variant: .neutral,
            accessibilityLabel: "刷新日志"
        ) {
            reload()
        }
    }

    // MARK: 数据

    private func reload() {
        snapshot = client.debugLogSnapshot(limit: 500)
        exportedURL = nil
        if let snapshot {
            SelfTestLog.add(
                "debug-logs reload lines=\(snapshot.totalLines) shown=\(snapshot.entries.count) " +
                "bytes=\(snapshot.bytes) files=\(snapshot.files.count)"
            )
        }
    }

    private func exportNow() {
        if let url = client.debugLogExport() {
            exportedURL = url
            statusIsError = false
            statusMessage = "已导出：\(url.lastPathComponent)"
            client.debugLogWrite(level: "INFO", tag: "DebugLogs", message: "导出日志 \(url.lastPathComponent)")
            SelfTestLog.add("debug-logs export path=\(url.path)")
        } else {
            statusIsError = true
            statusMessage = "导出失败：当前日志为空或目录不可写"
            SelfTestLog.add("debug-logs export failed (empty log or unwritable dir)")
        }
    }

    private func clearAll() {
        let cleared = client.debugLogClear()
        exportedURL = nil
        statusIsError = !cleared
        statusMessage = cleared ? "已清空全部日志文件" : "清空失败（部分文件不可删除）"
        SelfTestLog.add("debug-logs clear cleared=\(cleared)")
        reload()
        client.debugLogWrite(level: "INFO", tag: "DebugLogs", message: "调试日志已清空（用户操作）")
        reload()
    }

    /// 演示钩子：`--settings-extra-demo-logs` 写入一组不同级别日志（仅当次启动；截图证据用）。
    private func writeDemoEntriesIfNeeded() {
        guard !didRunDemo, ProcessInfo.processInfo.arguments.contains("--settings-extra-demo-logs") else { return }
        didRunDemo = true
        let lines: [(String, String, String)] = [
            ("INFO", "App", "KnockKnock 启动完成，shared framework 已连接"),
            ("DEBUG", "Settings", "读取外观键 appearance=dark chat_background=dusk font=large"),
            ("INFO", "ChatEngine", "发送消息 session=session_zzz008 历史轮数=15 温度=0.70"),
            ("DEBUG", "Prompt", "提示词模板解析完成 type=private mode=online len=890"),
            ("WARN", "Provider", "供应商连接测试超时，已自动回退本地演示通道"),
            ("INFO", "Memory", "写入长期印象 memoryId=1024 operator=zzz008"),
            ("ERROR", "Notification", "通知授权被拒绝（status=denied），已引导用户前往系统设置"),
            ("INFO", "Diary", "代理人日记同步完成 count=3 未读=1"),
            ("DEBUG", "Knowledge", "知识库召回命中 2 条 chunk，topScore=0.83"),
            ("INFO", "DebugLogs", "演示日志写入完成（--settings-extra-demo-logs）"),
        ]
        for line in lines {
            client.debugLogWrite(level: line.0, tag: line.1, message: line.2)
        }
        SelfTestLog.add("debug-logs demo entries written=\(lines.count)")
    }

    // MARK: 状态面板

    private var statusPanel: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                HStack(spacing: KnockTokens.spacingS) {
                    Image(systemName: "doc.text.magnifyingglass")
                        .font(.system(size: 15, weight: .heavy))
                        .foregroundStyle(KnockTokens.textPrimary)
                        .accessibilityHidden(true)
                    KnockText("日志文件", role: .sectionTitle, lineLimit: 1)
                    Spacer(minLength: KnockTokens.spacingS)
                    if let snapshot {
                        KnockCountPill(title: "共", value: "\(snapshot.totalLines) 行")
                    }
                }

                if let snapshot {
                    infoLine("主文件", snapshot.path, monospaced: true)
                    infoLine("大小", "\(snapshot.bytesText) · 归档 \(max(0, snapshot.files.count - 1)) 个")
                    if snapshot.truncated {
                        KnockText("列表仅显示最近 500 行；导出文件包含完整主文件。", role: .caption, color: KnockTokens.textSecondary)
                    }
                } else {
                    KnockText("读取中…", role: .caption, color: KnockTokens.textSecondary)
                }

                if !statusMessage.isEmpty {
                    KnockText(
                        statusMessage,
                        role: .captionStrong,
                        color: statusIsError ? KnockTokens.dangerBright : KnockTokens.textPrimary
                    )
                }
            }
        }
    }

    private func infoLine(_ title: String, _ value: String, monospaced: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
            KnockText(title, role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
            Text(value)
                .font(.system(size: 11, weight: .medium, design: monospaced ? .monospaced : .default))
                .foregroundStyle(KnockTokens.textPrimary)
                .lineLimit(2)
                .truncationMode(.middle)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    // MARK: 筛选 / 操作面板

    private var filterPanel: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                KnockText("级别", role: .captionStrong, color: KnockTokens.textSecondary)
                KnockSegmentedBar(
                    items: LevelFilter.allCases.map {
                        KnockSegmentItem(value: $0, title: $0.rawValue)
                    },
                    selection: $levelFilter,
                    height: KnockTokens.segmentedSmallHeight,
                    showsIcons: false
                )
                .accessibilityLabel("日志级别筛选")

                KnockSearchBar(
                    placeholder: "搜索关键词（正文 / 来源）",
                    text: $keyword
                )

                KnockFlowLayout(spacing: KnockTokens.spacingS) {
                    Button {
                        exportNow()
                    } label: {
                        Label("生成导出文件", systemImage: "doc.badge.plus")
                            .font(.system(size: 13, weight: .semibold))
                    }
                    .buttonStyle(KnockSecondaryButtonStyle(fullWidth: false, height: 36))

                    if let exportedURL {
                        ShareLink(item: exportedURL) {
                            Label("分享", systemImage: "square.and.arrow.up")
                                .font(.system(size: 13, weight: .semibold))
                        }
                        .buttonStyle(KnockPrimaryButtonStyle(fullWidth: false, height: 36))
                    }

                    Button {
                        showClearConfirm = true
                    } label: {
                        Label("清空", systemImage: "trash")
                            .font(.system(size: 13, weight: .semibold))
                    }
                    .buttonStyle(KnockDangerButtonStyle(fullWidth: false, height: 36))
                }

                KnockText(
                    "匹配 \(filteredEntries.count) 条 · 新日志在前 · 点按条目展开/收起正文",
                    role: .caption,
                    color: KnockTokens.textSecondary
                )
            }
        }
    }

    // MARK: 日志阅读器

    private var entriesSection: some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
            KnockGroupTitle(title: "日志条目", systemImage: "list.bullet.rectangle")

            if filteredEntries.isEmpty {
                KnockEmptyState(
                    title: (snapshot?.totalLines ?? 0) == 0 ? "日志文件为空" : "没有匹配的日志",
                    message: (snapshot?.totalLines ?? 0) == 0
                        ? "使用 App 后会产生日志；导出功能可生成完整日志文件。"
                        : "试试切换级别或清空搜索关键词。"
                )
            } else {
                // 黑底等宽日志阅读器：面板内逐行渲染，可选中复制
                KnockPanel(padding: KnockTokens.spacingS, showsChecker: false) {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(filteredEntries.enumerated()), id: \.element.id) { index, entry in
                            entryRow(entry)
                            if index < filteredEntries.count - 1 {
                                Rectangle()
                                    .fill(KnockTokens.stroke)
                                    .frame(height: KnockTokens.hairline)
                                    .accessibilityHidden(true)
                            }
                        }
                    }
                }
            }
        }
    }

    private func entryRow(_ entry: DebugLogEntryItem) -> some View {
        let expanded = expandedIDs.contains(entry.id)
        return Button {
            if expanded {
                expandedIDs.remove(entry.id)
            } else {
                expandedIDs.insert(entry.id)
            }
        } label: {
            VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                HStack(spacing: KnockTokens.spacingS) {
                    Text(entry.timeText)
                        .font(.system(size: 10, weight: .medium, design: .monospaced))
                        .foregroundStyle(KnockTokens.textTertiary)
                    levelBadge(entry)
                    if !entry.tag.isEmpty {
                        Text("[\(entry.tag)]")
                            .font(.system(size: 10, weight: .medium, design: .monospaced))
                            .foregroundStyle(KnockTokens.textSecondary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 9, weight: .heavy))
                        .foregroundStyle(KnockTokens.textTertiary)
                        .accessibilityHidden(true)
                }
                Text(entry.message)
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundStyle(entry.levelIsError ? KnockTokens.dangerBright : KnockTokens.textSecondary)
                    .lineLimit(expanded ? nil : 2)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, KnockTokens.spacingS)
            .padding(.vertical, KnockTokens.spacingS)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .textSelection(.enabled)
    }

    private func levelBadge(_ entry: DebugLogEntryItem) -> some View {
        let color: Color = entry.levelIsError
            ? KnockTokens.dangerBright
            : (entry.levelIsWarn ? KnockTokens.textPrimary : KnockTokens.textSecondary)
        return Text(entry.level)
            .font(.system(size: 9, weight: .semibold, design: .monospaced))
            .foregroundStyle(color)
            .padding(.horizontal, 5)
            .padding(.vertical, 2)
            .background(Capsule(style: .continuous).fill(KnockTokens.rowIconWell))
            .accessibilityLabel("级别 \(entry.level)")
    }
}