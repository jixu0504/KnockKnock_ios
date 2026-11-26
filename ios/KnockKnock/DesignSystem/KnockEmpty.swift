//
//  KnockEmpty.swift
//  「敲敲」空态组件：E M P T Y 宽字距 pill / NoData 行 / 空态组合。
//
//  设计依据：docs/敲敲UI改版计划书-v1.1.md 第 1.2 节「空态」：
//  - 宽字距灰字 pill「E M P T Y」（底 `#060606`，字 `#5A5A5A`，字距 8pt）；
//  - 面板顶部「NoData」行；
//  - 第 7.2-6 验收：空态必须是 E M P T Y 宽字距 pill（不是系统 SF Symbol 大图标）。
//
//  可访问性：
//  - 视觉用宽字距英文 pill，但 VoiceOver 朗读中文语义（"暂无内容"）；
//  - 空态标题/说明支持 Dynamic Type；按钮最小高 46pt。
//

import SwiftUI

// MARK: - E M P T Y pill

/// 空态 pill：宽字距灰字（`KnockTypeRole.emptyPill` 自带 8pt 字距）。
struct KnockEmptyPill: View {
    var text: String

    init(text: String = "EMPTY") {
        self.text = text
    }

    var body: some View {
        KnockText(text, role: .emptyPill, color: KnockTokens.emptyText, lineLimit: 1)
            .padding(.horizontal, KnockTokens.emptyPillPadding)
            .frame(minHeight: KnockTokens.emptyPillHeight)
            .background(Capsule(style: .continuous).fill(KnockTokens.emptyWell))
            .accessibilityLabel("暂无内容")
    }
}

// MARK: - NoData 行

/// 面板顶部「NoData」行：次文字 13pt heavy + 底部 1pt 分隔线。
struct KnockNoDataRow: View {
    var text: String

    init(text: String = "NoData") {
        self.text = text
    }

    var body: some View {
        HStack(spacing: KnockTokens.spacingS) {
            KnockText(text, role: .captionStrong, color: KnockTokens.textSecondary, lineLimit: 1)
            Spacer(minLength: KnockTokens.spacingS)
        }
        .padding(.bottom, KnockTokens.spacingS)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(KnockTokens.stroke)
                .frame(height: KnockTokens.hairline)
                .accessibilityHidden(true)
        }
        .accessibilityLabel("暂无数据")
    }
}

// MARK: - 空态组合

/// 空态组合：面板 + NoData 行 + E M P T Y pill + 标题/说明 + 可选主操作。
/// 与旧 `EmptyStateView(title:message:systemImage:actionTitle:action:)` 语义一致（调用面可平移）。
struct KnockEmptyState: View {
    var title: String
    var message: String
    var systemImage: String?
    var actionTitle: String?
    var action: (() -> Void)?
    var pillText: String

    init(
        title: String,
        message: String,
        systemImage: String? = nil,
        actionTitle: String? = nil,
        action: (() -> Void)? = nil,
        pillText: String = "EMPTY"
    ) {
        self.title = title
        self.message = message
        self.systemImage = systemImage
        self.actionTitle = actionTitle
        self.action = action
        self.pillText = pillText
    }

    var body: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingL) {
                KnockNoDataRow()

                VStack(spacing: KnockTokens.spacingM) {
                    if let systemImage {
                        Image(systemName: systemImage)
                            .font(.system(size: 34, weight: .heavy))
                            .foregroundStyle(KnockTokens.textTertiary)
                            .accessibilityHidden(true)
                    }
                    KnockText(title, role: .pageTitle, alignment: .center)
                        .frame(maxWidth: .infinity)
                    KnockText(
                        message,
                        role: .caption,
                        color: KnockTokens.textSecondary,
                        lineSpacing: 3,
                        alignment: .center
                    )
                    .frame(maxWidth: .infinity)
                    KnockEmptyPill(text: pillText)
                        .padding(.top, KnockTokens.spacingXS)
                    if let actionTitle, let action {
                        Button(actionTitle, action: action)
                            .buttonStyle(KnockPrimaryButtonStyle(fullWidth: false))
                            .padding(.top, KnockTokens.spacingXS)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, KnockTokens.spacingS)
            }
        }
    }
}