//
//  NewKnowledgeView.swift
//  新建知识库（粘贴文本 / 文件导入），T48 知识库 UI。
//
//  - 粘贴文本：KnowledgeBaseBridge.createText（同步切块入库，local 模式自动后台索引）
//  - 文件导入：DocumentPicker（TXT / MD，UTF-8）→ 桥 importFileFromPath → importFile(bytes)
//  两条路径都走真实 shared KnowledgeBaseService，与 Android 导入语义一致。
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

    var body: some View {
        NavigationStack {
            Form {
                Section("方式") {
                    Picker("方式", selection: $mode) {
                        ForEach(Mode.allCases) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                }

                Section("名称") {
                    TextField("知识库名称", text: $name)
                        .textInputAutocapitalization(.never)
                }

                if mode == .text {
                    Section {
                        TextEditor(text: $content)
                            .frame(minHeight: 220)
                            .font(.callout)
                    } header: {
                        Text("正文")
                    } footer: {
                        Text("支持 TXT / Markdown 文本；会自动按章节与段落切块（最多 1,800 块）。local 向量模式下导入后自动后台索引。")
                    }
                } else {
                    Section {
                        Button {
                            showFileImporter = true
                        } label: {
                            Label("选择 TXT / MD 文件", systemImage: "doc.badge.plus")
                        }
                        .disabled(isWorking)
                    } footer: {
                        Text("文件需为 UTF-8 文本（≤ 5 MB / 60 万字）。导入后名称默认取文件名，可稍后重命名。")
                    }
                }

                if let errorText {
                    Section {
                        Text(errorText)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }

                Section {
                    if isWorking {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text("处理中…（切块/入库）")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    } else if mode == .text {
                        Button("创建知识库") { Task { await createFromText() } }
                            .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                      || content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
            .navigationTitle("新建知识库")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
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
