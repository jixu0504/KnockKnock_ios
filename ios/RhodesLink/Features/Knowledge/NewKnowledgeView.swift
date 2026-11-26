//
//  NewKnowledgeView.swift
//  新建知识库（粘贴文本 / 文件导入），T48 知识库 UI。
//
//  - 粘贴文本：KnowledgeBaseBridge.createText（同步切块入库，local 模式自动后台索引）
//  - 文件导入：DocumentPicker（TXT / MD，UTF-8）→ 桥 importFileFromPath → importFile(bytes)
//  两条路径都走真实 shared KnowledgeBaseService，与 Android 导入语义一致。
//
//  敲敲视觉（P3）：隐藏系统导航栏 → `KnockHeader`（返回 + 关闭胶囊）；
//  方式 = 黄选中胶囊分段栏；名称/正文 = 面板 + 胶囊输入框；主操作「创建知识库」= 黄胶囊；
//  文件导入 = 虚线胶囊按钮；错误改页面内危险色文字（不再用系统 alert）。
//

import SwiftUI
import UniformTypeIdentifiers

struct NewKnowledgeView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var environment: AppEnvironment

    /// 创建成功回调（父页面刷新列表）
    var onCreated: (KnowledgeBookItem) -> Void

    @State private var mode: Mode = .text
    @State private var name = ""
    @State private var content = ""
    @State private var isWorking = false
    @State private var errorText: String?
    @State private var showFileImporter = false

    private enum Mode: String, CaseIterable, Identifiable {
        case text = "粘贴文本"
        case file = "文件导入"
        var id: String { rawValue }
    }

    private var canCreate: Bool {
        mode == .text
            && !isWorking
            && !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(title: "新建知识库", showsBack: false, trailing: {
                    KnockCloseButton(label: "取消") { dismiss() }
                })

                ScrollView {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                        KnockPanel {
                            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                                KnockGroupTitle(title: "方式", systemImage: "square.grid.2x2.fill")
                                KnockSegmentedBar(
                                    items: Mode.allCases.map {
                                        KnockSegmentItem(value: $0, title: $0.rawValue)
                                    },
                                    selection: $mode,
                                    height: KnockTokens.segmentedSmallHeight,
                                    showsIcons: false
                                )
                            }
                        }

                        KnockPanel {
                            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                                KnockGroupTitle(title: "名称", systemImage: "textformat")
                                KnockInputField(
                                    placeholder: "知识库名称",
                                    text: $name
                                )
                                .textInputAutocapitalization(.never)
                            }
                        }

                        if mode == .text {
                            KnockPanel {
                                VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                                    KnockGroupTitle(title: "正文", systemImage: "doc.text")
                                    textEditor
                                    KnockText(
                                        "支持 TXT / Markdown 文本；会自动按章节与段落切块（最多 1,800 块）。local 向量模式下导入后自动后台索引。",
                                        role: .caption,
                                        color: KnockTokens.textSecondary,
                                        lineSpacing: 3
                                    )
                                }
                            }
                        } else {
                            KnockPanel {
                                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                                    KnockGroupTitle(title: "文件", systemImage: "doc.badge.plus")
                                    KnockDashedButton(
                                        title: "选择 TXT / MD 文件",
                                        systemImage: "doc.badge.plus",
                                        emphasized: true
                                    ) {
                                        showFileImporter = true
                                    }
                                    .disabled(isWorking)
                                    KnockText(
                                        "文件需为 UTF-8 文本（≤ 5 MB / 60 万字）。导入后名称默认取文件名，可稍后重命名。",
                                        role: .caption,
                                        color: KnockTokens.textSecondary,
                                        lineSpacing: 3
                                    )
                                }
                            }
                        }

                        if let errorText {
                            KnockErrorPanel(title: "创建失败", message: errorText, retryTitle: nil)
                        }

                        if isWorking {
                            KnockLoadingPanel("处理中…（切块/入库）")
                        } else if mode == .text {
                            Button {
                                Task { await createFromText() }
                            } label: {
                                Text("创建知识库")
                            }
                            .buttonStyle(KnockPrimaryButtonStyle())
                            .disabled(!canCreate)
                            .opacity(canCreate ? 1 : 0.35)
                        }
                    }
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.top, KnockTokens.spacingS)
                    .padding(.bottom, KnockTokens.spacingXL)
                }
                .scrollIndicators(.hidden)
                .knockScrollEdgeHidden()
            }
        }
        .knockHiddenNavigationBar()
        .fileImporter(
            isPresented: $showFileImporter,
            allowedContentTypes: Self.allowedTypes,
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                guard let url = urls.first else { return }
                Task { await importFile(url: url) }
            case .failure(let error):
                errorText = "选择文件失败：\(error.localizedDescription)"
            }
        }
    }

    /// 正文编辑区：实色行底 + 描边圆角 + 正文排版（KnockTypography body）。
    private var textEditor: some View {
        TextEditor(text: $content)
            .knockFont(.body)
            .scrollContentBackground(.hidden)
            .foregroundStyle(KnockTokens.textPrimary)
            .tint(KnockTokens.electric)
            .frame(minHeight: 220)
            .padding(KnockTokens.spacingS)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(KnockTokens.row)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
            }
            .overlay(alignment: .topLeading) {
                if content.isEmpty {
                    KnockText("粘贴设定集、世界观或资料正文…", role: .body, color: KnockTokens.textTertiary)
                        .padding(.horizontal, KnockTokens.spacingM)
                        .padding(.vertical, KnockTokens.spacingM)
                        .allowsHitTesting(false)
                }
            }
            .accessibilityLabel("知识库正文")
    }

    // MARK: - 动作

    private func createFromText() async {
        guard !isWorking else { return }
        isWorking = true
        errorText = nil
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedContent = content.trimmingCharacters(in: .whitespacesAndNewlines)
        let result = await environment.bridge.createKnowledgeText(name: trimmedName, content: trimmedContent)
        isWorking = false
        switch result {
        case .success(let book):
            onCreated(book)
            dismiss()
        case .failure(let error):
            errorText = error.message
        }
    }

    private func importFile(url: URL) async {
        guard !isWorking else { return }
        isWorking = true
        errorText = nil
        let fileName = url.lastPathComponent.isEmpty ? "导入知识库.txt" : url.lastPathComponent
        let result = await environment.bridge.importKnowledgeFile(fileName: fileName, fileURL: url)
        isWorking = false
        switch result {
        case .success(let book):
            onCreated(book)
            dismiss()
        case .failure(let error):
            errorText = error.message
        }
    }

    private static var allowedTypes: [UTType] {
        var types: [UTType] = [.plainText, .text]
        if let markdown = UTType(filenameExtension: "md") { types.append(markdown) }
        if let md = UTType(filenameExtension: "markdown") { types.append(md) }
        return types
    }
}