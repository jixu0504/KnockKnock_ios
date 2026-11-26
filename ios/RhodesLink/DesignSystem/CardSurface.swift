//
//  CardSurface.swift
//  「敲敲」非玻璃内容层：面板卡片 / 分组标题 / E M P T Y 空态（文件名保留，内部重写）。
//
//  背景：T33 时期本文件是「半透明白卡 + SectionTitle + EmptyStateView」；敲敲改版
//  （计划书 1.2 / 2.2 / 7.2-6）要求：
//  - 卡片 → 面板实色（浅 `#FFFFFF` / 深 `#0A0A0A`）+ 1.5pt 描边 + 圆角 20pt + 极淡棋盘格；
//  - `SectionTitle` → 分组标题 14pt heavy（白/黑自适应），不再小写灰字；
//  - `EmptyStateView` → E M P T Y 宽字距 pill + NoData 行（不是系统大图标空态）。
//
//  调用面兼容：`cardSurface(cornerRadius:strong:)` / `SectionTitle(text:systemImage:)` /
//  `EmptyStateView(title:message:systemImage:actionTitle:action:)` 签名保持不变，
//  因此 30 个存量页面无需改动即可获得敲敲视觉。
//
//  可访问性：增强对比度 → 描边加深；Dynamic Type → 全部经 `KnockText` 语义字号；
//  空态操作按钮 46pt 高、黄底黑字（对比度 > 15:1）。
//

import SwiftUI

// MARK: - 实色面板卡片

/// 非玻璃卡片背景（旧 API）：敲敲面板 = 实色底 + 1.5pt 描边 + 棋盘格。
struct CardSurface: ViewModifier {
    var cornerRadius: CGFloat = KnockTokens.panelRadius
    /// 旧「强调卡」：描边改为次描边（深一档），不再改变透明度。
    var strong: Bool = false

    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    private var strokeColor: Color {
        if colorSchemeContrast == .increased { return KnockTokens.strokeContrast }
        return strong ? KnockTokens.strokeSecondary : KnockTokens.stroke
    }

    func body(content: Content) -> some View {
        content
            .background {
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(KnockTokens.panel)
                    .overlay {
                        KnockCheckerOverlay(cornerRadius: cornerRadius)
                    }
            }
            .overlay {
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(strokeColor, lineWidth: KnockTokens.strokeWidth)
            }
    }
}

extension View {
    /// 非玻璃卡片（内容层默认风格）：敲敲面板视觉。
    func cardSurface(cornerRadius: CGFloat = KnockTokens.panelRadius, strong: Bool = false) -> some View {
        modifier(CardSurface(cornerRadius: cornerRadius, strong: strong))
    }
}

// MARK: - 分组标题

/// 分组标题（旧 API）：14pt heavy 主文字 + 可选 SF Symbol；左侧留给列表对齐。
struct SectionTitle: View {
    var text: String
    var systemImage: String?

    var body: some View {
        HStack(spacing: KnockTokens.spacingS) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.system(size: 13, weight: .heavy))
                    .foregroundStyle(KnockTokens.textSecondary)
                    .accessibilityHidden(true)
            }
            KnockText(text, role: .sectionTitle, lineLimit: 1)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityAddTraits(.isHeader)
    }
}

// MARK: - 空态

/// 空状态占位（旧 API）：E M P T Y 宽字距 pill + NoData 行 + 标题/说明/操作。
struct EmptyStateView: View {
    var title: String
    var message: String
    var systemImage: String
    var actionTitle: String?
    var action: (() -> Void)?

    var body: some View {
        KnockEmptyState(
            title: title,
            message: message,
            systemImage: systemImage,
            actionTitle: actionTitle,
            action: action
        )
    }
}