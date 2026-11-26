//
//  DiaryDetailView.swift
//  干员日记详情（T51）：全文阅读 + 打开即标记已读（settings diary_read_at_<operatorId> =
//  该干员最新 createdAt，对齐 Android DiaryScreen 的 markDiaryRead 语义）。
//  数据来自 shared `DiaryBridge.detail(id)` 真实 SQLite。
//
//  敲敲视觉（P3）：隐藏系统导航栏 → `KnockHeader`（返回 + 标题）；
//  作者卡 = 面板 + 蓝圈头像 + 名称/篇数/已读角标；正文 = 阅读器面板（KnockTypography body + 6pt 行距，可选中）。
//

import SwiftUI

@MainActor
struct DiaryDetailView: View {
    let entry: DiaryEntryItem
    /// 阅读完成（标记已读）后回调，列表据此刷新未读点。
    var onRead: () -> Void

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    @State private var detail: DiaryDetailItem?
    @State private var isLoading = true
    @State private var errorText: String?
    @State private var markedRead = false

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(
                    title: "代理人日记",
                    subtitle: detail?.operatorName ?? entry.operatorName,
                    showsBack: true,
                    onBack: { dismiss() }
                )

                ScrollView {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                        headerPanel

                        if let errorText {
                            KnockErrorPanel(title: "日记读取失败", message: errorText) {
                                Task { await load() }
                            }
                        } else if isLoading && detail == nil {
                            KnockLoadingPanel("正在读取日记全文…")
                        } else if let detail {
                            contentPanel(detail)
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
        .task { await load() }
    }

    private var headerPanel: some View {
        KnockPanel {
            HStack(spacing: KnockTokens.spacingM) {
                KnockAvatar(
                    avatarId: detail?.avatarId ?? entry.avatarId,
                    name: detail?.operatorName ?? entry.operatorName,
                    size: 54
                )
                VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                    KnockText(detail?.operatorName ?? entry.operatorName, role: .rowTitle, lineLimit: 1)
                    KnockText(detail?.title ?? entry.title, role: .caption, color: KnockTokens.textSecondary, lineLimit: 2)
                    HStack(spacing: KnockTokens.spacingXS) {
                        KnockText("第 \(detail?.version ?? entry.version) 篇", role: .caption, color: KnockTokens.textTertiary, lineLimit: 1)
                        if markedRead {
                            KnockBadge(text: "已读", variant: .neutral)
                        }
                    }
                }
                Spacer(minLength: KnockTokens.spacingXS)
            }
        }
    }

    private func contentPanel(_ detail: DiaryDetailItem) -> some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                // 正文排版：KnockTypography body（17pt）+ 6pt 行距，可选中复制
                Text(detail.content.isEmpty ? "（本篇内容为空）" : detail.content)
                    .knockFont(.body)
                    .foregroundStyle(KnockTokens.textPrimary)
                    .lineSpacing(6)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)

                KnockDivider()

                HStack(spacing: KnockTokens.spacingXS) {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 10, weight: .heavy))
                        .foregroundStyle(KnockTokens.textTertiary)
                        .accessibilityHidden(true)
                    KnockText("日记仅保存在本机；打开即标记已读（代理人维度未读）", role: .caption, color: KnockTokens.textTertiary, lineSpacing: 2)
                }
            }
        }
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