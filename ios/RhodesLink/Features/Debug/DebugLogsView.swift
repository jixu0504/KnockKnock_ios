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

import SwiftUI

struct DebugLogsView: View {
    @EnvironmentObject private var environment: AppEnvironment

    enum LevelFilter: String, CaseIterable, Identifiable {
        case all = "全部"
        case debug = "DEBUG"
        case info = "INFO"
        case warn = "WARN"
        case error = "ERROR"

        var id: String { rawValue }

        var color: Color {
            switch self {
            case .all: return .secondary
            case .debug: return .secondary
            case .info: return .blue
            case .warn: return .orange
            case .error: return .red
            }
        }
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

            ScrollView {
                VStack(spacing: 14) {
                    statusCard
                    filterCard
                    entriesSection
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
            .refreshable { reload() }
            .onScrollGeometryChange(for: CGFloat.self) { geometry in
                geometry.contentOffset.y
            } action: { _, newValue in
                scrollOffset = newValue
            }
        }
        .navigationTitle("调试日志")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    reload()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .accessibilityLabel("刷新日志")
            }
        }
        .task {
            writeDemoEntriesIfNeeded()
            reload()
        }
        .confirmationDialog("清空全部日志？", isPresented: $showClearConfirm, titleVisibility: .visible) {
            Button("清空主文件与全部归档", role: .destructive) { clearAll() }
            Button("取消", role: .cancel) {}
        } message: {
            Text("将删除 Application Support/logs/ 下的 rhodes.log 与全部归档文件，操作不可恢复。")
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
            ("INFO", "App", "RhodesLink 启动完成，shared framework 已连接"),
            ("DEBUG", "Settings", "读取外观键 appearance=dark chat_background=dusk font=large"),
            ("INFO", "ChatEngine", "发送消息 session=session_amiya 历史轮数=15 温度=0.70"),
            ("DEBUG", "Prompt", "提示词模板解析完成 type=private mode=online len=890"),
            ("WARN", "Provider", "供应商连接测试超时，已自动回退本地演示通道"),
            ("INFO", "Memory", "写入长期印象 memoryId=1024 operator=amiya"),
            ("ERROR", "Notification", "通知授权被拒绝（status=denied），已引导用户前往系统设置"),
            ("INFO", "Diary", "干员日记同步完成 count=3 未读=1"),
            ("DEBUG", "Knowledge", "知识库召回命中 2 条 chunk，topScore=0.83"),
            ("INFO", "DebugLogs", "演示日志写入完成（--settings-extra-demo-logs）"),
        ]
        for line in lines {
            client.debugLogWrite(level: line.0, tag: line.1, message: line.2)
        }
        SelfTestLog.add("debug-logs demo entries written=\(lines.count)")
    }

    // MARK: 状态卡

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "doc.text.magnifyingglass")
                    .foregroundStyle(GlassTokens.brandOrange)
                Text("日志文件")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                if let snapshot {
                    Text("共 \(snapshot.totalLines) 行")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            if let snapshot {
                infoLine("主文件", snapshot.path, monospaced: true)
                infoLine("大小", "\(snapshot.bytesText) · 归档 \(max(0, snapshot.files.count - 1)) 个")
                if snapshot.truncated {
                    Text("列表仅显示最近 500 行；导出文件包含完整主文件。")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            } else {
                Text("读取中…")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if !statusMessage.isEmpty {
                Text(statusMessage)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(statusIsError ? .red : .green)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 22)
    }

    private func infoLine(_ title: String, _ value: String, monospaced: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.system(size: 10))
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

    // MARK: 筛选 / 操作卡

    private var filterCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Picker("级别", selection: $levelFilter) {
                ForEach(LevelFilter.allCases) { level in
                    Text(level.rawValue).tag(level)
                }
            }
            .pickerStyle(.segmented)
            .accessibilityLabel("日志级别筛选")

            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)
                TextField("搜索关键词（正文 / 来源）", text: $keyword)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.footnote)
                if !keyword.isEmpty {
                    Button {
                        keyword = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.tertiary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 9)
            .background(GlassTokens.bubbleSystem, in: RoundedRectangle(cornerRadius: 10, style: .continuous))

            HStack(spacing: 10) {
                Button {
                    exportNow()
                } label: {
                    Label("生成导出文件", systemImage: "doc.badge.plus")
                        .font(.footnote.weight(.semibold))
                }
                .buttonStyle(.bordered)
                .tint(GlassTokens.brandOrange)

                if let exportedURL {
                    ShareLink(item: exportedURL) {
                        Label("分享", systemImage: "square.and.arrow.up")
                            .font(.footnote.weight(.semibold))
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(GlassTokens.brandOrange)
                }

                Spacer()

                Button(role: .destructive) {
                    showClearConfirm = true
                } label: {
                    Label("清空", systemImage: "trash")
                        .font(.footnote.weight(.semibold))
                }
                .buttonStyle(.bordered)
                .tint(.red)
            }
            .font(.footnote)

            Text("匹配 \(filteredEntries.count) 条 · 新日志在前 · 点按条目展开/收起正文")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 22)
    }

    // MARK: 列表

    private var entriesSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionTitle(text: "日志条目", systemImage: "list.bullet.rectangle")

            if filteredEntries.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "text.magnifyingglass")
                        .font(.system(size: 28, weight: .light))
                        .foregroundStyle(.tertiary)
                    Text((snapshot?.totalLines ?? 0) == 0 ? "日志文件为空——使用 App 后会产生日志" : "没有匹配的日志条目")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 28)
                .cardSurface(cornerRadius: 18)
            } else {
                LazyVStack(spacing: 8) {
                    ForEach(filteredEntries) { entry in
                        entryRow(entry)
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
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Text(entry.timeText)
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                    levelBadge(entry)
                    if !entry.tag.isEmpty {
                        Text("[\(entry.tag)]")
                            .font(.caption2.monospaced())
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 9, weight: .semibold))
                        .foregroundStyle(.tertiary)
                }
                Text(entry.message)
                    .font(.caption.monospaced())
                    .foregroundStyle(.primary)
                    .lineLimit(expanded ? nil : 2)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .cardSurface(cornerRadius: 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .textSelection(.enabled)
    }

    private func levelBadge(_ entry: DebugLogEntryItem) -> some View {
        let color: Color = entry.levelIsError ? .red : (entry.levelIsWarn ? .orange : (entry.level == "DEBUG" ? .secondary : .blue))
        return Text(entry.level)
            .font(.system(size: 9, weight: .bold, design: .monospaced))
            .foregroundStyle(color)
            .padding(.horizontal, 5)
            .padding(.vertical, 2)
            .background(color.opacity(0.12), in: Capsule())
    }
}
