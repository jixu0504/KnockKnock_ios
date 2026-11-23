//
//  DiaryDetailView.swift
//  干员日记详情（T51）：全文阅读 + 打开即标记已读（settings diary_read_at_<operatorId> =
//  该干员最新 createdAt，对齐 Android DiaryScreen 的 markDiaryRead 语义）。
//  数据来自 shared `DiaryBridge.detail(id)` 真实 SQLite。
//

import SwiftUI

@MainActor
struct DiaryDetailView: View {
    let entry: DiaryEntryItem
    /// 阅读完成（标记已读）后回调，列表据此刷新未读点。
    var onRead: () -> Void

    @EnvironmentObject private var environment: AppEnvironment

    @State private var detail: DiaryDetailItem?
    @State private var isLoading = true
    @State private var errorText: String?
    @State private var markedRead = false

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    header

                    if let errorText {
                        errorCard(errorText)
                    } else if isLoading && detail == nil {
                        loadingCard
                    } else if let detail {
                        contentCard(detail)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
            }
        }
        .navigationTitle("干员日记")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private var header: some View {
        HStack(spacing: 12) {
            RoleAvatar(avatarId: detail?.avatarId ?? entry.avatarId,
                       name: detail?.operatorName ?? entry.operatorName,
                       size: 54)
            VStack(alignment: .leading, spacing: 3) {
                Text(detail?.operatorName ?? entry.operatorName)
                    .font(.headline)
                Text(detail?.title ?? entry.title)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                HStack(spacing: 6) {
                    Text("第 \(detail?.version ?? entry.version) 篇")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                    if markedRead {
                        Label("已读", systemImage: "checkmark.circle.fill")
                            .font(.caption2)
                            .foregroundStyle(.green)
                    }
                }
            }
            Spacer(minLength: 4)
        }
        .padding(14)
        .cardSurface(cornerRadius: 20, strong: true)
    }

    private func contentCard(_ detail: DiaryDetailItem) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(detail.content.isEmpty ? "（本篇内容为空）" : detail.content)
                .font(.body)
                .lineSpacing(6)
                .foregroundStyle(.primary)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)

            Divider()

            HStack(spacing: 6) {
                Image(systemName: "lock.fill")
                    .font(.caption2)
                Text("日记仅保存在本机；打开即标记已读（干员维度未读）")
                    .font(.caption2)
            }
            .foregroundStyle(.tertiary)
        }
        .padding(16)
        .cardSurface(cornerRadius: 20)
    }

    private var loadingCard: some View {
        HStack(spacing: 10) {
            ProgressView()
            Text("正在读取日记全文…")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .cardSurface(cornerRadius: 18)
    }

    private func errorCard(_ message: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("日记读取失败", systemImage: "exclamationmark.triangle.fill")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.red)
            Text(message)
                .font(.caption)
                .foregroundStyle(.secondary)
            Button("重试") {
                Task { await load() }
            }
            .buttonStyle(.bordered)
            .tint(GlassTokens.brandOrange)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .cardSurface(cornerRadius: 18)
    }

    private func load() async {
        isLoading = true
        let result = await environment.bridge.loadDiaryDetail(id: entry.id)
        switch result {
        case .success(let item):
            detail = item
            errorText = nil
            await markRead()
        case .failure(let error):
            errorText = error.message
            DiaryAutomation.add("FAIL detail.load id=\(entry.id) \(error.message)")
        }
        isLoading = false
    }

    /// 打开即标记该干员日记已读（oracle markDiaryRead：settings diary_read_at_<operatorId> = 最新 createdAt）。
    private func markRead() async {
        let result = await environment.bridge.markDiaryRead(operatorId: entry.operatorId)
        switch result {
        case .success(let remaining):
            markedRead = true
            DiaryAutomation.add("PASS detail.markRead operator=\(entry.operatorId) remainingUnreadOperators=\(remaining)")
            onRead()
        case .failure(let error):
            DiaryAutomation.add("FAIL detail.markRead operator=\(entry.operatorId) \(error.message)")
        }
    }
}
