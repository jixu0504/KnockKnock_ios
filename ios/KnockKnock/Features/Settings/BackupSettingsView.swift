//
//  BackupSettingsView.swift
//  T38 设置 →「数据与备份」子页（「可用软件」里程碑 2），P4 敲敲视觉改造：
//  面板分组 / 胶囊行 / 黄色进度条 / 危险操作红色按钮 / 自绘 KncokDialog 确认。
//
//  四块能力（全部接真实 shared 域层，不 mock）：
//  1. 完整备份：导出 rhodes.db 快照 → fileExporter 存到「文件」App；导入 → 先确认覆盖风险 →
//     暂存 `.restore-pending`（两阶段恢复，重启后换入）→ 可撤销；展示文件大小/SHA-256/SQLite 自检；
//  2. 恢复后自检：冷启动换入后自动比对「暂存时期望表计数 vs 现网计数」并展示报告（BackupSelfCheck）；
//  3. 角色卡：选角色 → 导出 rhodes-operator v1（fileExporter）；导入 → 预览 → 确认 → 新建角色（含头像/关系）；
//  4. 聊天记录：选会话 → 导出 rhodes-chat v1（fileExporter）；导入 → 预览 → 确认 → 合并（去重/ID 重分配）结果展示。
//  5. Markdown 聊天记录（T58）：选 .md/.markdown/.txt → 解析预览（标题/角色/条数/首尾样例）→
//     选择目标角色（147 位）→ 导入真实 DB（新增/去重条数 + 跳转会话）；重复导入零新增给警告。
//
//  错误路径（坏包 / 格式不符 / 文件不存在 / 磁盘或权限失败）统一以红色描边提示条展示，不崩溃。
//  自动化验证：`--backup-selftest`（见 BackupAutomation.swift），本页展示自检矩阵结果。
//

import SwiftUI
import UniformTypeIdentifiers
import UIKit

// MARK: - 导出文档（fileExporter 用）

struct BackupExportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }

    var data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

// MARK: - 待导入（确认弹窗前的预览）

private enum PendingImport {
    case fullBackup(path: String, inspection: BackupSQLiteInspection)
    case roleCard(path: String, preview: OperatorCardPreview)
    case chat(path: String, preview: ChatExportPreview)
}

// MARK: - 视图

struct BackupSettingsView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @EnvironmentObject private var router: AppRouter
    @ObservedObject private var automation = BackupAutomationState.shared
    @Environment(\.dismiss) private var dismiss

    @State private var sessions: [SessionItem] = []
    @State private var roles: [RoleItem] = []
    @State private var selectedSessionId: String = ""
    @State private var selectedRoleId: String = ""

    @State private var busy = false
    @State private var statusText = ""
    @State private var errorText = ""

    @State private var lastFullBackup: FullBackupFileInfo?
    @State private var lastOperatorExport: OperatorCardExportInfo?
    @State private var lastOperatorImport: OperatorCardImportInfo?
    @State private var lastChatExport: ChatExportInfo?
    @State private var lastChatImport: ChatImportOutcome?
    @State private var stagedRestore: PendingRestoreState?
    @State private var lastRestoreReport: RestoreSelfCheckReport?
    @State private var importProgressText = ""

    // T58：Markdown 聊天记录导入
    @State private var markdownDocument: MarkdownParsedDocument?
    @State private var showMarkdownRoleSheet = false
    @State private var markdownTargetRoleId = ""
    @State private var markdownFilter = ""
    @State private var lastMarkdownImport: MarkdownChatImportInfo?

    @State private var pendingImport: PendingImport?
    @State private var showImporter = false
    @State private var importerKind: ImportKind = .fullBackup
    @State private var showExporter = false
    @State private var exportDocument: BackupExportDocument?
    @State private var exportFilename = ""

    private enum ImportKind { case fullBackup, roleCard, chat, markdownChat }

    private var bridge: AppGraphClient { environment.bridge }
    private let selftestEnabled = ProcessInfo.processInfo.arguments.contains("--backup-selftest")

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(title: "数据与备份", showsBack: true, onBack: { dismiss() })

                ScrollView {
                    VStack(spacing: KnockTokens.spacingXL) {
                        headerGroup
                        markdownChatSection
                        fullBackupSection
                        restoreCheckSection
                        roleCardSection
                        chatSection
                        if selftestEnabled { automationCard }
                        if !errorText.isEmpty { errorCard }
                    }
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.top, KnockTokens.spacingM)
                    .padding(.bottom, KnockTokens.spacingXXL)
                }
                .scrollIndicators(.hidden)
                .settingsKnockScrollEdgeHidden()
            }
        }
        .navigationTitle("数据与备份")
        .settingsKnockNavigationChrome()
        .task {
            await loadPickers()
            // 先跑自动化（--backup-selftest 时：第二轮会自行消费 staging 标记并产出报告），
            // 再读自检报告；顺序反了会让页面抢先消费标记、导致第二轮被误判为第一轮。
            await BackupAutomation.runIfNeeded(environment: environment)
            if let report = bridge.evaluateRestoreSelfCheck() {
                lastRestoreReport = report
            } else {
                lastRestoreReport = BackupSelfCheck.loadLastReport()
            }
            refreshPendingState()
            await runMarkdownDemoIfNeeded()
        }
        .fileImporter(
            isPresented: $showImporter,
            allowedContentTypes: importContentTypes
        ) { result in
            handleImportResult(result)
        }
        .fileExporter(
            isPresented: $showExporter,
            document: exportDocument,
            contentType: .data,
            defaultFilename: exportFilename
        ) { result in
            switch result {
            case .success(let url):
                statusText = "已保存到「文件」：\(url.lastPathComponent)"
            case .failure(let error):
                errorText = "保存到「文件」失败：\(error.localizedDescription)"
            }
            exportDocument = nil
        }
        .sheet(isPresented: $showMarkdownRoleSheet) {
            markdownRoleSheet
        }
        .overlay {
            importConfirmOverlay
        }
    }

    /// 弹窗文案（Identifiable 包装，避免 alert(item:) 需要 PendingImport 实现 Identifiable）。
    private struct ImportPrompt: Identifiable {
        let id = UUID()
        let title: String
        let message: String
        let confirmTitle: String

        init(_ pending: PendingImport) {
            switch pending {
            case .fullBackup(_, let inspection):
                title = "恢复完整备份？"
                message = "将用所选备份覆盖当前全部数据（角色/会话/聊天/记忆/设置等）。\n\n文件自检：SQLite 头 \(inspection.headerValid ? "有效" : "无效") · integrity_check = \(inspection.integrityResult) · \(inspection.countsText)\n\n恢复写入 .restore-pending，需重启 App 后才会换入；暂存本身不修改当前数据，可随时撤销。"
                confirmTitle = "暂存并重启后恢复"
            case .roleCard(_, let preview):
                title = "导入角色卡？"
                message = "将新建角色「\(preview.operatorName)」\(preview.hasAvatar ? "（含头像）" : "")，关系 \(preview.relationshipCount) 条（目标端不存在的角色关系会自动跳过）。不会覆盖现有角色。"
                confirmTitle = "导入角色"
            case .chat(_, let preview):
                title = "导入聊天记录？"
                message = "会话「\(preview.sessionTitle.isEmpty ? preview.sessionId : preview.sessionTitle)」：\(preview.messageCount) 条消息、\(preview.mediaCount) 个媒体。\n导入为合并语义：重复消息自动去重、消息 ID 重新分配；失败时单事务回滚，不污染现有数据。"
                confirmTitle = "导入聊天记录"
            }
        }
    }

    /// 自绘确认弹窗（替代系统 `.alert`，避开 iOS 26 玻璃背景；恢复完整备份为危险确认）。
    @ViewBuilder
    private var importConfirmOverlay: some View {
        if let pending = pendingImport {
            let prompt = ImportPrompt(pending)
            ZStack {
                Color.black.opacity(0.62)
                    .ignoresSafeArea()
                    .onTapGesture { pendingImport = nil }
                    .accessibilityHidden(true)

                KnockConfirmDialog(
                    title: prompt.title,
                    message: prompt.message,
                    confirmTitle: prompt.confirmTitle,
                    cancelTitle: "取消",
                    isDestructive: isFullBackup(pending),
                    onConfirm: {
                        pendingImport = nil
                        Task { await confirmImport(pending) }
                    },
                    onCancel: { pendingImport = nil }
                )
                .padding(.horizontal, KnockTokens.spacingXXL)
                .accessibilityAddTraits(.isModal)
            }
        }
    }

    private func isFullBackup(_ pending: PendingImport) -> Bool {
        if case .fullBackup = pending { return true }
        return false
    }

    // MARK: 头部

    private var headerGroup: some View {
        SettingsKnockGroup("本地数据", systemImage: "externaldrive.badge.checkmark") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    HStack(spacing: KnockTokens.spacingM) {
                        KnockRowGlyph(systemImage: "externaldrive.badge.checkmark", tint: KnockTokens.electric, size: 36)
                        VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                            KnockText("数据与备份", role: .rowTitle, lineLimit: 1)
                            KnockText(
                                "导出/导入覆盖完整备份（数据库快照）、角色卡（rhodes-operator v1）与单会话聊天记录（rhodes-chat v1）。",
                                role: .caption,
                                color: KnockTokens.textSecondary,
                                lineSpacing: 2
                            )
                        }
                        Spacer(minLength: KnockTokens.spacingS)
                        if bridge.hasPendingRestore {
                            KnockBadge(text: "待恢复", variant: .accent)
                        }
                    }

                    SettingsKnockInfoLine("数据库", bridge.databasePath, monospaced: true)
                    SettingsKnockInfoLine("导出目录", "Documents/BackupExports")
                    if !statusText.isEmpty {
                        SettingsKnockInfoLine("状态", statusText)
                    }

                    if busy {
                        VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                            SettingsKnockProgressBar()
                            KnockText(
                                importProgressText.isEmpty ? "处理中…" : importProgressText,
                                role: .caption,
                                color: KnockTokens.textSecondary,
                                lineLimit: 2
                            )
                        }
                    }
                }
            }
        }
    }

    // MARK: 完整备份

    private var fullBackupSection: some View {
        SettingsKnockGroup("完整备份", systemImage: "arrow.up.arrow.down.circle") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    KnockText(
                        "导出当前数据库快照（导出前自动 WAL checkpoint，并做 SQLite 头 / integrity_check / 表计数自检）。",
                        role: .caption,
                        color: KnockTokens.textSecondary,
                        lineSpacing: 2
                    )

                    HStack(spacing: KnockTokens.spacingM) {
                        Button {
                            Task { await exportFullBackup() }
                        } label: {
                            HStack(spacing: KnockTokens.spacingS) {
                                Image(systemName: "square.and.arrow.up")
                                    .font(.system(size: 13, weight: .heavy))
                                KnockText("导出完整备份", role: .rowTitle, color: KnockTokens.onElectric, lineLimit: 2)
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(KnockPrimaryButtonStyle())
                        .disabled(busy)

                        Button {
                            importerKind = .fullBackup
                            showImporter = true
                        } label: {
                            HStack(spacing: KnockTokens.spacingS) {
                                Image(systemName: "square.and.arrow.down")
                                    .font(.system(size: 13, weight: .heavy))
                                KnockText("导入备份", role: .rowTitle, lineLimit: 2)
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(KnockSecondaryButtonStyle())
                        .disabled(busy)
                    }

                    if let info = lastFullBackup {
                        resultBlock(title: "最近导出", lines: [
                            "文件：\(URL(fileURLWithPath: info.path).lastPathComponent)",
                            "大小：\(info.sizeText) · SHA-256：\(info.sha256.prefix(24))…",
                            "自检：SQLite 头 \(info.inspection.headerValid ? "有效" : "无效") · integrity=\(info.inspection.integrityResult) · 表 \(info.inspection.tableCount)",
                            info.inspection.countsText,
                        ])
                    }

                    if let staged = stagedRestore {
                        resultBlock(title: "待恢复（重启后生效）", kind: .info, lines: [
                            "文件：\(URL(fileURLWithPath: staged.pendingPath).lastPathComponent)",
                            "自检：SQLite 头 \(staged.inspection.headerValid ? "有效" : "无效") · integrity=\(staged.inspection.integrityResult)",
                            staged.inspection.countsText,
                            "换入前现网计数：\(staged.currentCounts.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: " · "))",
                        ])
                        HStack(spacing: KnockTokens.spacingM) {
                            Button(role: .destructive) {
                                if bridge.cancelPendingRestore() {
                                    stagedRestore = nil
                                    statusText = "已撤销待恢复文件，当前数据未受影响"
                                } else {
                                    errorText = "撤销失败：待恢复文件删除失败"
                                }
                            } label: {
                                HStack(spacing: KnockTokens.spacingS) {
                                    Image(systemName: "trash")
                                        .font(.system(size: 13, weight: .heavy))
                                    KnockText("撤销待恢复", role: .rowTitle, color: KnockTokens.onDanger, lineLimit: 1)
                                }
                                .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(KnockDangerButtonStyle())

                            KnockText(
                                "重启 App 后自动换入；换入后本页显示恢复后自检结果",
                                role: .caption,
                                color: KnockTokens.textSecondary,
                                lineSpacing: 2
                            )
                        }
                    } else if bridge.hasPendingRestore {
                        resultBlock(title: "待恢复（重启后生效）", kind: .info, lines: [
                            "已存在待恢复文件：\(URL(fileURLWithPath: bridge.pendingRestorePath).lastPathComponent)",
                            "重启 App 后自动换入。",
                        ])
                    }
                }
            }
        }
    }

    // MARK: 恢复后自检

    private var restoreCheckSection: some View {
        SettingsKnockGroup("恢复后自检", systemImage: "checkmark.shield") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    if let report = lastRestoreReport {
                        SettingsKnockStatusBanner(
                            kind: report.passed ? .success : .failure,
                            title: report.passed ? "上次恢复：自检通过" : "上次恢复：自检未通过",
                            detail: report.summary
                        )
                        resultBlock(title: "自检明细", lines: [
                            "integrity_check = \(report.integrityResult)",
                            "期望：\(report.expectedCounts.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: " · "))",
                            "实际：\(report.actualCounts.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: " · "))",
                            "差异：\(report.mismatchesText)",
                        ])
                    } else if bridge.hasPendingRestore {
                        KnockText(
                            "已暂存待恢复文件。重启 App 后自动换入并执行自检（比对暂存时期望表计数 vs 现网计数）。",
                            role: .caption,
                            color: KnockTokens.textSecondary,
                            lineSpacing: 2
                        )
                    } else {
                        KnockText(
                            "暂无恢复记录。导入备份并重启后，这里会显示恢复后自检结果（integrity_check + 表计数比对）。",
                            role: .caption,
                            color: KnockTokens.textSecondary,
                            lineSpacing: 2
                        )
                    }
                }
            }
        }
    }

    // MARK: 角色卡

    private var roleCardSection: some View {
        SettingsKnockGroup("角色卡", systemImage: "person.crop.square") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    if roles.isEmpty {
                        KnockText("正在读取角色列表…", role: .caption, color: KnockTokens.textSecondary)
                    } else {
                        VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                            KnockText("选择角色", role: .captionStrong, color: KnockTokens.textSecondary, lineLimit: 1)
                            KnockDropdown(
                                items: roles.map {
                                    KnockSegmentItem(value: $0.id, title: "\($0.name)（\($0.id)）", systemImage: nil)
                                },
                                selection: $selectedRoleId
                            )
                        }
                    }

                    HStack(spacing: KnockTokens.spacingM) {
                        Button {
                            Task { await exportRoleCard() }
                        } label: {
                            HStack(spacing: KnockTokens.spacingS) {
                                Image(systemName: "square.and.arrow.up")
                                    .font(.system(size: 13, weight: .heavy))
                                KnockText("导出角色卡", role: .rowTitle, color: KnockTokens.onElectric, lineLimit: 2)
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(KnockPrimaryButtonStyle())
                        .disabled(busy || selectedRoleId.isEmpty)

                        Button {
                            importerKind = .roleCard
                            showImporter = true
                        } label: {
                            HStack(spacing: KnockTokens.spacingS) {
                                Image(systemName: "square.and.arrow.down")
                                    .font(.system(size: 13, weight: .heavy))
                                KnockText("导入角色卡", role: .rowTitle, lineLimit: 2)
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(KnockSecondaryButtonStyle())
                        .disabled(busy)
                    }

                    if let info = lastOperatorExport {
                        resultBlock(title: "最近导出（rhodes-operator v1）", lines: [
                            "角色：\(info.operatorName) · 关系 \(info.relationshipCount) 条 · 头像 \(info.hasAvatar ? "含" : "无")",
                            "大小：\(info.sizeText) · SHA-256：\(info.sha256.prefix(24))…",
                            "文件：\(URL(fileURLWithPath: info.path).lastPathComponent)",
                        ])
                    }

                    if let info = lastOperatorImport {
                        resultBlock(title: "最近导入", lines: [
                            "\(info.createdNew ? "已新建角色" : "已更新角色")「\(info.operatorName)」→ id=\(info.operatorId)",
                            "关系导入 \(info.importedRelationships) 条，跳过 \(info.skippedRelationships) 条（目标端不存在）",
                        ])
                    }
                }
            }
        }
    }

    // MARK: 聊天记录

    private var chatSection: some View {
        SettingsKnockGroup("聊天记录", systemImage: "bubble.left.and.text.bubble.right") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    if sessions.isEmpty {
                        KnockText("正在读取会话列表…", role: .caption, color: KnockTokens.textSecondary)
                    } else {
                        VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                            KnockText("选择会话", role: .captionStrong, color: KnockTokens.textSecondary, lineLimit: 1)
                            KnockDropdown(
                                items: sessions.map {
                                    KnockSegmentItem(value: $0.id, title: "\($0.title)（\($0.id)）", systemImage: nil)
                                },
                                selection: $selectedSessionId
                            )
                        }
                    }

                    HStack(spacing: KnockTokens.spacingM) {
                        Button {
                            Task { await exportChat() }
                        } label: {
                            HStack(spacing: KnockTokens.spacingS) {
                                Image(systemName: "square.and.arrow.up")
                                    .font(.system(size: 13, weight: .heavy))
                                KnockText("导出聊天记录", role: .rowTitle, color: KnockTokens.onElectric, lineLimit: 2)
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(KnockPrimaryButtonStyle())
                        .disabled(busy || selectedSessionId.isEmpty)

                        Button {
                            importerKind = .chat
                            showImporter = true
                        } label: {
                            HStack(spacing: KnockTokens.spacingS) {
                                Image(systemName: "square.and.arrow.down")
                                    .font(.system(size: 13, weight: .heavy))
                                KnockText("导入聊天记录", role: .rowTitle, lineLimit: 2)
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(KnockSecondaryButtonStyle())
                        .disabled(busy)
                    }

                    if let info = lastChatExport {
                        resultBlock(title: "最近导出（rhodes-chat v1）", lines: [
                            "会话：\(info.sessionTitle) · 消息 \(info.messageCount) · 媒体 \(info.mediaCount) · 存档 \(info.archiveCount)",
                            "大小：\(info.sizeText) · SHA-256：\(info.sha256.prefix(24))…",
                            "文件：\(URL(fileURLWithPath: info.path).lastPathComponent)",
                        ])
                    }

                    if let outcome = lastChatImport {
                        resultBlock(
                            title: "最近导入（合并语义）",
                            kind: outcome.success ? .success : .failure,
                            lines: [
                                outcome.summaryText,
                                "目标会话：\(outcome.sessionId)",
                                outcome.alerts.isEmpty ? "无告警" : "告警：\(outcome.alerts.joined(separator: " · "))",
                            ]
                        )
                    }
                }
            }
        }
    }

    // MARK: 自动化自检

    private var automationCard: some View {
        SettingsKnockGroup("备份自检矩阵（--backup-selftest）", systemImage: "checklist") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                    if automation.lines.isEmpty {
                        KnockText("等待自检开始…", role: .caption, color: KnockTokens.textSecondary)
                    } else {
                        ForEach(Array(automation.lines.enumerated()), id: \.offset) { _, line in
                            Text(line)
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundStyle(KnockTokens.textPrimary)
                                .fixedSize(horizontal: false, vertical: true)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
            }
        }
    }

    private var errorCard: some View {
        SettingsKnockGroup("操作未完成", systemImage: "exclamationmark.triangle.fill") {
            KnockPanel {
                HStack(alignment: .top, spacing: KnockTokens.spacingM) {
                    SettingsKnockStatusBanner(
                        kind: .failure,
                        title: "操作未完成（数据未受影响）",
                        detail: errorText
                    )
                    // 关闭/退出统一用敲敲红色 X 胶囊键（计划书图 B 顶栏样式）
                    KnockCloseButton(label: "关闭提示") {
                        errorText = ""
                    }
                }
            }
        }
    }

    // MARK: - 交互实现（逻辑零改动）

    private var importContentTypes: [UTType] {
        switch importerKind {
        case .fullBackup: return [.database, .data]
        case .roleCard, .chat: return [.zip, .data]
        case .markdownChat:
            var types: [UTType] = [.plainText, .text]
            if let markdownType = UTType(filenameExtension: "md") { types.insert(markdownType, at: 0) }
            if let markdownType = UTType(filenameExtension: "markdown") { types.insert(markdownType, at: 0) }
            return types
        }
    }

    private func loadPickers() async {
        if case .success(let items) = await bridge.loadSessions() {
            sessions = items
            if selectedSessionId.isEmpty { selectedSessionId = items.first?.id ?? "" }
        }
        if case .success(let items) = await bridge.loadRoles() {
            roles = items
            if selectedRoleId.isEmpty { selectedRoleId = items.first?.id ?? "" }
        }
    }

    private func refreshPendingState() {
        if bridge.hasPendingRestore { lastRestoreReport = BackupSelfCheck.loadLastReport() }
    }

    private func exportFullBackup() async {
        busy = true
        errorText = ""
        importProgressText = "正在导出数据库快照…"
        let url = BackupFiles.exportDirectory.appendingPathComponent("rhodes-backup-\(BackupFiles.timestamp()).db")
        let result = await bridge.exportFullBackup(destinationURL: url)
        busy = false
        importProgressText = ""
        switch result {
        case .success(let info):
            lastFullBackup = info
            statusText = "完整备份导出成功：\(info.sizeText)"
            presentExporter(path: info.path)
        case .failure(let error):
            errorText = "\(error.message)\n当前数据未受影响，可直接重试。"
        }
    }

    private func exportRoleCard() async {
        guard !selectedRoleId.isEmpty else { return }
        busy = true
        errorText = ""
        importProgressText = "正在导出角色卡…"
        let name = roles.first(where: { $0.id == selectedRoleId })?.name ?? selectedRoleId
        let url = BackupFiles.exportDirectory
            .appendingPathComponent("operator-\(name)-\(BackupFiles.timestamp()).rhodes-operator")
        let result = await bridge.exportOperatorCard(operatorId: selectedRoleId, to: url)
        busy = false
        importProgressText = ""
        switch result {
        case .success(let info):
            lastOperatorExport = info
            statusText = "角色卡导出成功：\(info.sizeText)"
            presentExporter(path: info.path)
        case .failure(let error):
            errorText = "\(error.message)\n当前数据未受影响。"
        }
    }

    private func exportChat() async {
        guard !selectedSessionId.isEmpty else { return }
        busy = true
        errorText = ""
        importProgressText = "正在导出聊天记录…"
        let url = BackupFiles.exportDirectory
            .appendingPathComponent("KnockKnockChatExport-\(BackupFiles.timestamp()).zip")
        let result = await bridge.exportChat(sessionId: selectedSessionId, to: url)
        busy = false
        importProgressText = ""
        switch result {
        case .success(let info):
            lastChatExport = info
            statusText = "聊天记录导出成功：\(info.sizeText)"
            presentExporter(path: info.path)
        case .failure(let error):
            errorText = "\(error.message)\n当前数据未受影响。"
        }
    }

    private func presentExporter(path: String) {
        guard let data = FileManager.default.contents(atPath: path) else {
            errorText = "导出文件读取失败：\(path)"
            return
        }
        exportDocument = BackupExportDocument(data: data)
        exportFilename = URL(fileURLWithPath: path).lastPathComponent
        showExporter = true
    }

    // MARK: 导入

    private func handleImportResult(_ result: Result<URL, Error>) {
        switch result {
        case .failure(let error):
            errorText = "选择文件失败：\(error.localizedDescription)"
        case .success(let url):
            Task { await prepareImport(from: url) }
        }
    }

    private func prepareImport(from url: URL) async {
        busy = true
        errorText = ""
        importProgressText = "正在校验所选文件…"
        defer {
            busy = false
            importProgressText = ""
        }
        let localURL: URL
        do {
            localURL = try BackupFiles.stageImportedFile(from: url)
        } catch {
            errorText = "读取所选文件失败：\(error.localizedDescription)\n文件可能不在本地或没有访问权限。"
            return
        }
        switch importerKind {
        case .fullBackup:
            let inspection = BackupSQLite.inspect(path: localURL.path, immutable: true)
            guard inspection.headerValid else {
                errorText = "这不是合法的完整备份文件：SQLite 文件头无效。\n请选择本 App「导出完整备份」生成的文件（.db）。"
                return
            }
            guard inspection.integrityResult.lowercased() == "ok" else {
                errorText = "备份文件自检未通过：integrity_check = \(inspection.integrityResult)\n文件可能已损坏，已拒绝恢复（当前数据未受影响）。"
                return
            }
            pendingImport = .fullBackup(path: localURL.path, inspection: inspection)
        case .roleCard:
            switch await bridge.inspectOperatorCard(sourcePath: localURL.path) {
            case .success(let preview):
                pendingImport = .roleCard(path: localURL.path, preview: preview)
            case .failure(let error):
                errorText = "\(error.message)\n文件不是有效的角色卡（rhodes-operator v1），当前数据未受影响。"
            }
        case .chat:
            switch await bridge.inspectChatExport(sourcePath: localURL.path) {
            case .success(let preview):
                pendingImport = .chat(path: localURL.path, preview: preview)
            case .failure(let error):
                errorText = "\(error.message)\n文件不是有效的聊天记录包（rhodes-chat v1），当前数据未受影响。"
            }
        case .markdownChat:
            switch await bridge.parseMarkdownFile(path: localURL.path) {
            case .success(let document):
                markdownDocument = document
                markdownFilter = ""
                markdownTargetRoleId = defaultMarkdownRoleId(for: document.preview.operatorName)
                showMarkdownRoleSheet = true
            case .failure(let error):
                errorText = "\(error.message)\n文件不是可解析的 Markdown 聊天记录，当前数据未受影响。"
            }
        }
    }

    private func confirmImport(_ pending: PendingImport) async {
        switch pending {
        case .fullBackup(let path, _):
            busy = true
            errorText = ""
            importProgressText = "正在写入待恢复文件…"
            let result = await bridge.stageFullRestore(sourcePath: path)
            busy = false
            importProgressText = ""
            switch result {
            case .success(let state):
                stagedRestore = state
                lastRestoreReport = nil
                statusText = "恢复已暂存，重启 App 后生效（可撤销）"
            case .failure(let error):
                errorText = "恢复未开始：\(error.message)\n两阶段恢复在暂存阶段失败，当前数据未被修改。"
            }
        case .roleCard(let path, _):
            busy = true
            errorText = ""
            importProgressText = "正在导入角色卡…"
            let result = await bridge.importOperatorCard(sourcePath: path, mode: "NEW")
            busy = false
            importProgressText = ""
            switch result {
            case .success(let info):
                lastOperatorImport = info
                statusText = "角色卡导入完成：\(info.operatorName)"
                await loadPickers()
            case .failure(let error):
                errorText = "\(error.message)\n单事务已回滚，当前角色数据未受影响。"
            }
        case .chat(let path, _):
            busy = true
            errorText = ""
            importProgressText = "正在导入聊天记录…"
            let result = await bridge.importChat(sourcePath: path) { stage, detail in
                Task { @MainActor in importProgressText = "\(stage)：\(detail)" }
            }
            busy = false
            importProgressText = ""
            switch result {
            case .success(let outcome):
                lastChatImport = outcome
                if outcome.success {
                    statusText = "聊天记录导入完成"
                } else {
                    errorText = "聊天记录导入失败：\(outcome.reason)\n单事务已回滚，现有聊天数据未受影响。"
                }
                await loadPickers()
            case .failure(let error):
                errorText = "聊天记录导入失败：\(error.message)\n单事务已回滚，现有聊天数据未受影响。"
            }
        }
    }

    // MARK: Markdown 聊天记录（T58）

    private var markdownChatSection: some View {
        SettingsKnockGroup("导入聊天记录（Markdown）", systemImage: "doc.text") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    KnockText(
                        "从 `.md` / `.markdown` / `.txt` 导入聊天记录：支持 KnockKnock Markdown 导出（`# 与<角色>的聊天记录` + `## 日期` + `称呼：内容`）及变体（`**称呼**：`、`称呼: `、`[yyyy-MM-dd HH:mm] 称呼：`），≤20MB。导入写真实数据库，不触发模型/记忆/未读。",
                        role: .caption,
                        color: KnockTokens.textSecondary,
                        lineSpacing: 2
                    )

                    HStack(spacing: KnockTokens.spacingM) {
                        Button {
                            importerKind = .markdownChat
                            showImporter = true
                        } label: {
                            HStack(spacing: KnockTokens.spacingS) {
                                Image(systemName: "square.and.arrow.down")
                                    .font(.system(size: 13, weight: .heavy))
                                // P5 黄色面积审计整改：本页主 CTA 是「导出完整备份」，
                                // Markdown 导入属辅助入口 → 由黄底主按钮降为描边次按钮，
                                // 页面黄色占比 6.24% → 目标 <2%（计划书 1.2：黄只做选中/主 CTA/品牌）。
                                KnockText("选择 Markdown 文件", role: .rowTitle, lineLimit: 2)
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(KnockSecondaryButtonStyle())
                        .disabled(busy)

                        if markdownDocument != nil {
                            Button {
                                markdownFilter = ""
                                showMarkdownRoleSheet = true
                            } label: {
                                HStack(spacing: KnockTokens.spacingS) {
                                    Image(systemName: "person.crop.circle")
                                        .font(.system(size: 13, weight: .heavy))
                                    KnockText("选择目标角色", role: .rowTitle, lineLimit: 2)
                                }
                                .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(KnockSecondaryButtonStyle())
                            .disabled(busy)
                        }
                    }

                    if let doc = markdownDocument {
                        resultBlock(title: "解析预览", lines: [
                            "标题：\(doc.preview.title)",
                            "角色：\(doc.preview.operatorName.isEmpty ? "未识别（请选择目标角色）" : doc.preview.operatorName) · 消息 \(doc.preview.messageCount) 条 · 日期段 \(doc.preview.dayCount)",
                            "格式：\(doc.preview.formatText)",
                            "首：「\(doc.preview.firstMessageText)」",
                            "尾：「\(doc.preview.lastMessageText)」",
                        ])
                    }

                    if let info = lastMarkdownImport {
                        resultBlock(
                            title: "最近导入（Markdown）",
                            kind: info.warning.isEmpty ? .success : .info,
                            lines: [
                                "目标角色：\(info.operatorName) · 新增 \(info.insertedMessages) 条 · 去重 \(info.skippedDuplicates) 条",
                                info.warning.isEmpty ? "重复判定：无重复" : "警告：\(info.warning)",
                                "目标会话：\(info.sessionId)",
                            ]
                        )
                        Button {
                            router.openChat(sessionId: info.sessionId)
                        } label: {
                            HStack(spacing: KnockTokens.spacingS) {
                                Image(systemName: "bubble.left")
                                    .font(.system(size: 13, weight: .heavy))
                                KnockText("打开该会话", role: .rowTitle, lineLimit: 1)
                            }
                        }
                        .buttonStyle(KnockSecondaryButtonStyle(fullWidth: false))
                        .disabled(busy)
                    }
                }
            }
        }
    }

    /// 解析成功后弹出的目标角色选择 Sheet（147 位角色，默认匹配解析出的角色名）。
    private var markdownRoleSheet: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            VStack(spacing: 0) {
                HStack(spacing: KnockTokens.spacingM) {
                    KnockCloseButton(label: "取消") {
                        showMarkdownRoleSheet = false
                        markdownDocument = nil
                    }

                    KnockText("选择目标角色", role: .pageTitle, lineLimit: 1)

                    Spacer(minLength: KnockTokens.spacingS)

                    Button("导入") {
                        Task { await confirmMarkdownImport() }
                    }
                    .buttonStyle(KnockPrimaryButtonStyle(fullWidth: false, height: 38))
                    .disabled(markdownTargetRoleId.isEmpty || busy)
                }
                .padding(.horizontal, KnockTokens.screenPadding)
                .padding(.top, KnockTokens.spacingM)
                .padding(.bottom, KnockTokens.spacingS)

                if let doc = markdownDocument {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                        KnockText(doc.preview.title, role: .rowTitle, lineLimit: 2)
                        KnockText(
                            "格式：\(doc.preview.formatText) · 角色：\(doc.preview.operatorName.isEmpty ? "未识别" : doc.preview.operatorName) · 消息 \(doc.preview.messageCount) 条 · 日期段 \(doc.preview.dayCount)",
                            role: .caption,
                            color: KnockTokens.textSecondary,
                            lineSpacing: 2
                        )
                        KnockText("首：「\(doc.preview.firstMessageText)」", role: .mono, color: KnockTokens.textTertiary, lineLimit: 1)
                        KnockText("尾：「\(doc.preview.lastMessageText)」", role: .mono, color: KnockTokens.textTertiary, lineLimit: 1)
                    }
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.bottom, KnockTokens.spacingS)
                }

                KnockSearchBar(placeholder: "搜索角色", text: $markdownFilter)
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.bottom, KnockTokens.spacingS)

                ScrollView {
                    LazyVStack(spacing: KnockTokens.spacingS) {
                        ForEach(filteredMarkdownRoles) { role in
                            Button {
                                markdownTargetRoleId = role.id
                            } label: {
                                KnockListRow(
                                    title: role.name,
                                    subtitle: "\(role.id) · \(role.title)",
                                    height: 60,
                                    action: nil
                                ) {
                                    KnockRowAvatar(avatarId: role.id, name: role.name)
                                } trailing: {
                                    HStack(spacing: KnockTokens.spacingS) {
                                        if role.id == markdownTargetRoleId {
                                            Image(systemName: "checkmark.circle.fill")
                                                .font(.system(size: 16, weight: .heavy))
                                                .foregroundStyle(KnockTokens.electric)
                                                .accessibilityHidden(true)
                                        }
                                    }
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.bottom, KnockTokens.spacingXXL)
                }
                .scrollIndicators(.hidden)
                .settingsKnockScrollEdgeHidden()

                if busy {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                        SettingsKnockProgressBar()
                        KnockText(
                            importProgressText.isEmpty ? "正在导入…" : importProgressText,
                            role: .caption,
                            color: KnockTokens.textSecondary,
                            lineLimit: 2
                        )
                    }
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.vertical, KnockTokens.spacingS)
                }
            }
        }
        .settingsKnockNavigationChrome()
        .presentationBackground(KnockTokens.canvas)
    }

    private var filteredMarkdownRoles: [RoleItem] {
        let query = markdownFilter.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return roles }
        return roles.filter {
            $0.name.localizedCaseInsensitiveContains(query) || $0.id.localizedCaseInsensitiveContains(query)
        }
    }

    private func defaultMarkdownRoleId(for operatorName: String) -> String {
        let target = operatorName.trimmingCharacters(in: .whitespacesAndNewlines)
        if !target.isEmpty, let matched = roles.first(where: { $0.name == target }) { return matched.id }
        if !target.isEmpty,
           let matched = roles.first(where: { $0.name.contains(target) || target.contains($0.name) }) {
            return matched.id
        }
        return roles.first?.id ?? ""
    }

    private func openMarkdownRoleSheet(for document: MarkdownParsedDocument) {
        markdownDocument = document
        markdownFilter = ""
        markdownTargetRoleId = defaultMarkdownRoleId(for: document.preview.operatorName)
        showMarkdownRoleSheet = true
    }

    private func confirmMarkdownImport() async {
        guard let document = markdownDocument, !markdownTargetRoleId.isEmpty else { return }
        busy = true
        errorText = ""
        importProgressText = "正在导入 Markdown 聊天记录…"
        let result = await bridge.importMarkdown(document: document, targetOperatorId: markdownTargetRoleId)
        busy = false
        importProgressText = ""
        switch result {
        case .success(let info):
            lastMarkdownImport = info
            showMarkdownRoleSheet = false
            markdownDocument = nil
            publishMarkdownImportFinished(info)
            statusText = info.warning.isEmpty
                ? "Markdown 导入完成：新增 \(info.insertedMessages) 条 → \(info.operatorName)"
                : "Markdown 导入完成：新增 \(info.insertedMessages) 条（\(info.warning)）"
            await loadPickers()
        case .failure(let error):
            errorText = "\(error.message)\n单事务已回滚，现有聊天数据未受影响。"
        }
    }

    /// 导入成功后广播「聊天数据外部变更」：
    /// 1. 已打开的聊天页（ChatViewModel 监听通知）立即刷新并载入导入块，不用退出重进；
    /// 2. 会话列表（ChatListView 监听通知）刷新预览/时间；
    /// 3. 落「待定位导入块」设置项：聊天页**下次进入**时消费（导入记录时间戳通常早于
    ///    首屏 50 条窗口——只刷新首屏在长会话里看不到导入内容，用户会以为导入失败）。
    private func publishMarkdownImportFinished(_ info: MarkdownChatImportInfo) {
        let firstImportedId = info.insertedIds.first ?? 0
        if info.insertedMessages > 0, firstImportedId > 0 {
            let key = ChatViewModel.pendingImportFocusKey(sessionId: info.sessionId)
            bridge.setSettingsString(key: key, value: "\(firstImportedId):\(info.insertedIds.count)")
        }
        var userInfo: [AnyHashable: Any] = [
            ChatExternalChangeKey.source: "markdown-import",
            ChatExternalChangeKey.sessionId: info.sessionId,
            ChatExternalChangeKey.operatorId: info.operatorId,
            ChatExternalChangeKey.importedCount: info.insertedMessages,
        ]
        if firstImportedId > 0 {
            userInfo[ChatExternalChangeKey.firstImportedId] = firstImportedId
        }
        NotificationCenter.default.post(
            name: .chatDataDidChangeExternally,
            object: nil,
            userInfo: userInfo
        )
        SelfTestLog.add(
            "settings markdown import broadcast session=\(info.sessionId) inserted=\(info.insertedMessages) " +
            "firstImportedId=\(firstImportedId) lastImportedId=\(info.insertedIds.last ?? 0)"
        )
    }

    /// `--markdown-demo`：自检/截图用——解析内置示例并弹出目标角色 Sheet（无参数时零副作用）。
    private func runMarkdownDemoIfNeeded() async {
        guard ProcessInfo.processInfo.arguments.contains("--markdown-demo"), markdownDocument == nil else { return }
        let demo = """
        # 与安比的聊天记录

        导出时间：2025-06-15 22:10

        ## 2025-06-14

        安比：绳匠，今天的任务辛苦了。

        绳匠：还好，你呢？

        ## 2025-06-15

        安比：我很好，谢谢绳匠关心。
        """
        switch await bridge.parseMarkdownText(text: demo) {
        case .success(let document):
            openMarkdownRoleSheet(for: document)
        case .failure(let error):
            errorText = error.message
        }
    }

    // MARK: 组件

    /// 结果块：内嵌面板底 + 1.5pt 描边 + 等宽多行（导出/导入/自检结果统一呈现）。
    private func resultBlock(
        title: String,
        kind: SettingsKnockStatusKind = .info,
        lines: [String]
    ) -> some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
            HStack(spacing: KnockTokens.spacingS) {
                Image(systemName: kind.icon)
                    .font(.system(size: 11, weight: .heavy))
                    .foregroundStyle(kind.iconColor)
                    .accessibilityHidden(true)
                KnockText(title, role: .captionStrong, color: kind == .info ? KnockTokens.textSecondary : kind.iconColor, lineLimit: 2)
                Spacer(minLength: 0)
            }
            ForEach(Array(lines.filter { !$0.isEmpty }.enumerated()), id: \.offset) { _, line in
                Text(line)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(KnockTokens.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
        }
        .padding(KnockTokens.spacingM)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous)
                .fill(KnockTokens.panelInset)
        }
        .overlay {
            RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous)
                .strokeBorder(
                    kind == .info ? KnockTokens.strokeSecondary : kind.stroke,
                    lineWidth: KnockTokens.strokeWidth
                )
        }
    }
}