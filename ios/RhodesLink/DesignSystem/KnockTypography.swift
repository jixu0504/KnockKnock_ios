//
//  KnockTypography.swift
//  「敲敲」字体层级 + 大标题微补偿 + Dynamic Type 接入（P0 基座）。
//
//  设计依据：docs/敲敲UI改版计划书-v1.1.md
//  - 第 1.4 节「字体现实」：PingFang SC 最粗只有 Semibold(600)，**没有 Black/Heavy**；
//    早期方案请求 `.heavy` 让系统回退最粗字面 + 描边加粗（≥16pt 四向偏移叠加）来近似参考图，
//    但真机验证发现：请求超出字面能力的字重会触发最粗字面/合成加粗，叠加四向偏移后边缘发虚、
//    多行重影（真机问题 4）。现改为**只请求真实存在字面**：标题 Semibold / 正文 Regular /
//    小字 Medium，字号梯度保留敲敲的厚重观感；假加粗整体移除，仅大标题（≥20pt）保留
//    ≤0.3pt 的同色微 shadow 做亚像素级厚度补偿（可全局关闭）。
//  - 第 2.1 节：所有文字经 `KnockTypography` 出口，未来换 MiSans Heavy 只改一处。
//
//  实现说明：
//  - `Font.system(size:weight:)` 不随 Dynamic Type 缩放；这里用 `UIFontMetrics` 按
//    `@Environment(\.dynamicTypeSize)` 手工缩放，既保住实测字号（20/18/15/13…），
//    又能响应系统字号 + 设置页「小/标准/大」三档（RootTabView 的 `.dynamicTypeSize`）；
//  - 缩放结果按「字号 + 文本样式 + 档位」缓存，避免列表滚动时反复计算；
//  - 微补偿只在「整段 text 可复制」的 `KnockText` 内实现（任意 View 无法多向重绘），
//    页面若保留裸 `Text`，用 `.knockFont(_:)` 只取字号/字重（无补偿）。
//
//  可访问性：
//  - Dynamic Type 全档位（xSmall…accessibility5）参与缩放，不做固定高度裁切（行高均用 minHeight）；
//  - `AccessibilityReduceTransparency` 与字体无关；系统「粗体文字」开关由 SwiftUI 字体自身响应；
//  - 微补偿可用 `knockStrokeCompensation` 环境值 / 设置键整体关闭（弱视/审美偏好）。
//

import SwiftUI
import UIKit

// MARK: - 字体层级

/// 「敲敲」字体层级（计划书字号）；`KnockTypography` / `KnockText` / `.knockFont` 三处共用。
enum KnockTypeRole: String, CaseIterable, Identifiable {
    /// 画廊/首屏大标题 34pt semibold。
    case display
    /// 品牌字 22pt semibold（顶栏 logo 右侧）。
    case brand
    /// 页面标题 20pt semibold（图 A 顶栏 / 弹窗标题）。
    case pageTitle
    /// 分组标题 14pt semibold（「绳网」/「+ 新建」）。
    case sectionTitle
    /// 列表行主标题 15pt semibold（图 B 胶囊行）。
    case rowTitle
    /// 正文 17pt regular（设置项说明 / 正文）。
    case body
    /// 聊天气泡 18pt medium（图 A；正文强调，不用 heavy）。
    case bubble
    /// 辅助说明 13pt medium（时间戳 / 副标题）。
    case caption
    /// 辅助强调 13pt semibold（Badge 文案 / 次级按钮）。
    case captionStrong
    /// 分段栏 / Tab 文案 13pt semibold。
    case tabLabel
    /// 角标 11pt medium。
    case badge
    /// E M P T Y 宽字距 15pt medium（字距 8pt）。
    case emptyPill
    /// 等宽诊断 12pt（自检日志 / hex 标签）。
    case mono

    var id: String { rawValue }

    /// 中文可读名（画廊展示用）。
    var displayName: String {
        switch self {
        case .display: return "Display 大标题"
        case .brand: return "Brand 品牌字"
        case .pageTitle: return "Page Title 页面标题"
        case .sectionTitle: return "Section 分组标题"
        case .rowTitle: return "Row 行标题"
        case .body: return "Body 正文"
        case .bubble: return "Bubble 气泡"
        case .caption: return "Caption 辅助"
        case .captionStrong: return "Caption Strong 辅助强调"
        case .tabLabel: return "Tab 分段栏"
        case .badge: return "Badge 角标"
        case .emptyPill: return "Empty 空态宽字距"
        case .mono: return "Mono 等宽诊断"
        }
    }

    /// 基准字号（pt，@ large / 标准档；随 Dynamic Type 缩放）。
    var baseSize: CGFloat {
        switch self {
        case .display: return 34
        case .brand: return 22
        case .pageTitle: return 20
        case .sectionTitle: return 14
        case .rowTitle: return 15
        case .body: return 17
        case .bubble: return 18
        case .caption: return 13
        case .captionStrong: return 13
        case .tabLabel: return 13
        case .badge: return 11
        case .emptyPill: return 15
        case .mono: return 12
        }
    }

    /// 设计字重（**只用 PingFang 真实存在的字面**）：
    /// - 大字标题（display / brand / pageTitle）→ Semibold(600)，即 PingFang 最粗真实字面；
    /// - 分组/行标题 → Semibold，靠字号与颜色分层，不再叠加假加粗；
    /// - 正文 → Regular；气泡 → Medium；13pt 及以下小字 → Medium/Semibold（**13pt 及以下绝不用 .heavy**）。
    ///
    /// 为什么不请求 `.bold`/`.heavy`：PingFang SC 没有 Bold/Heavy 字面，请求超出字面能力的字重
    /// 会走系统权重回退（挑最粗字面甚至合成加粗），真机边缘发虚、发糊（真机问题 4 根因）。
    var weight: Font.Weight {
        switch self {
        case .display, .brand, .pageTitle: return .semibold
        case .sectionTitle, .rowTitle: return .semibold
        case .captionStrong, .tabLabel: return .semibold
        case .body: return .regular
        case .bubble, .caption, .badge, .emptyPill, .mono: return .medium
        }
    }

    var design: Font.Design {
        self == .mono ? .monospaced : .default
    }

    /// 字重可读名（画廊展示 / 自检日志）：与 `weight` 的语义真值一一对应。
    var weightName: String {
        switch self {
        case .body: return "regular"
        case .bubble, .caption, .badge, .emptyPill, .mono: return "medium"
        case .display, .brand, .pageTitle, .sectionTitle, .rowTitle, .captionStrong, .tabLabel:
            return "semibold"
        }
    }

    /// 默认字距（pt）：E M P T Y 宽字距 8pt，其它按小节微调。
    var tracking: CGFloat {
        switch self {
        case .emptyPill: return 8
        case .badge: return 0.5
        case .tabLabel: return 0.3
        case .sectionTitle: return 0.4
        default: return 0
        }
    }

    /// 对应 UIKit 文本样式：用于 `UIFontMetrics` 缩放（保证与系统 Dynamic Type 曲线一致）。
    var uiTextStyle: UIFont.TextStyle {
        switch self {
        case .display: return .largeTitle
        case .brand: return .title2
        case .pageTitle: return .title3
        case .sectionTitle: return .subheadline
        case .rowTitle: return .subheadline
        case .body, .bubble: return .body
        case .caption, .captionStrong: return .footnote
        case .tabLabel: return .footnote
        case .badge: return .caption2
        case .emptyPill: return .subheadline
        case .mono: return .caption1
        }
    }

    /// 大标题微补偿只对 ≥20pt 的标题类生效（display 34 / brand 22 / pageTitle 20）。
    /// 正文、气泡、13pt 及以下小字**一律真字重渲染**，不叠加任何假加粗：
    /// 真机问题 4 的根因就是「四向偏移 + 微 shadow」在正文/多行文字上被放大成灰边与重影。
    var supportsStrokeCompensation: Bool {
        switch self {
        case .display, .brand, .pageTitle: return true
        default: return false
        }
    }

    /// 同色微 shadow 半径（pt）：≤0.35 的亚像素级厚度补偿，避免把字形边缘糊出灰边。
    var strokeShadowRadius: CGFloat { 0.30 }

    /// 同色微 shadow 不透明度：只补一点厚度，不产生可见投影。
    var strokeShadowOpacity: Double { 0.30 }
}

// MARK: - 字体出口

enum KnockTypography {

    // MARK: 字号缩放（Dynamic Type + 设置页三档）

    /// 按 Dynamic Type 档位缩放基准字号（结果缓存；`UIFontMetrics` 曲线与系统一致）。
    static func scaledSize(
        _ base: CGFloat,
        role: KnockTypeRole,
        dynamicTypeSize: DynamicTypeSize
    ) -> CGFloat {
        let key = "\(role.uiTextStyle.rawValue)|\(dynamicTypeSize)|\(base)"
        lock.lock()
        if let cached = sizeCache[key] {
            lock.unlock()
            return cached
        }
        lock.unlock()

        let traits = UITraitCollection(
            preferredContentSizeCategory: contentSizeCategory(for: dynamicTypeSize)
        )
        let scaled = UIFontMetrics(forTextStyle: role.uiTextStyle)
            .scaledValue(for: base, compatibleWith: traits)

        lock.lock()
        sizeCache[key] = scaled
        lock.unlock()
        return scaled
    }

    /// 角色字体（指定 Dynamic Type 档位；`KnockText` / 自定义组件内部使用）。
    static func font(_ role: KnockTypeRole, dynamicTypeSize: DynamicTypeSize) -> Font {
        let size = scaledSize(role.baseSize, role: role, dynamicTypeSize: dynamicTypeSize)
        return .system(size: size, weight: role.weight, design: role.design)
    }

    /// 角色字体（无环境上下文的兜底：按 `.large` 标准档，调用方应尽量显式传档位）。
    static func font(_ role: KnockTypeRole) -> Font {
        font(role, dynamicTypeSize: .large)
    }

    /// Dynamic Type 档位 → UIContentSizeCategory（用于 UIFontMetrics）。
    static func contentSizeCategory(for size: DynamicTypeSize) -> UIContentSizeCategory {
        switch size {
        case .xSmall: return .extraSmall
        case .small: return .small
        case .medium: return .medium
        case .large: return .large
        case .xLarge: return .extraLarge
        case .xxLarge: return .extraExtraLarge
        case .xxxLarge: return .extraExtraExtraLarge
        case .accessibility1: return .accessibilityMedium
        case .accessibility2: return .accessibilityLarge
        case .accessibility3: return .accessibilityExtraLarge
        case .accessibility4: return .accessibilityExtraExtraLarge
        case .accessibility5: return .accessibilityExtraExtraExtraLarge
        default: return .large
        }
    }

    // MARK: 缓存

    private static let lock = NSLock()
    private static var sizeCache: [String: CGFloat] = [:]
}

// MARK: - KnockText（统一文字组件 · 真字重渲染）

/// 设计系统统一文字组件：
/// - 字号/字重/字距全部来自 `KnockTypeRole`；**主体文字用真字重渲染**，不再做四向偏移叠加
///   （真机问题 4：多份偏移副本会在字形边缘叠出灰边 / 多行重影，正文与气泡尤其明显）；
/// - 只有 ≥20pt 的大标题（display / brand / pageTitle）在开关打开时保留 ≤0.3pt 的同色微 shadow，
///   作为「PingFang 无 Black」的亚像素厚度补偿；可用环境值 `knockStrokeCompensation`
///   （默认读 UserDefaults 全局开关）整体关闭；
/// - 单份 `Text` 渲染：多行换行 / 行距 / 居中对齐与真字重完全一致，无错位风险。
///
/// 用法：
/// ```swift
/// KnockText("私聊", role: .pageTitle)
/// KnockText("副标题", role: .caption, color: KnockTokens.textSecondary)
/// KnockText("EMPTY", role: .emptyPill)
/// ```
struct KnockText: View {
    let text: String
    var role: KnockTypeRole
    var color: Color
    /// 覆盖角色默认字距；传 `nil` 用 `role.tracking`。
    var tracking: CGFloat?
    var lineSpacing: CGFloat
    var lineLimit: Int?
    var alignment: TextAlignment
    var minimumScaleFactor: CGFloat

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.knockStrokeCompensation) private var strokeCompensation

    init(
        _ text: String,
        role: KnockTypeRole = .body,
        color: Color = KnockTokens.textPrimary,
        tracking: CGFloat? = nil,
        lineSpacing: CGFloat = 0,
        lineLimit: Int? = nil,
        alignment: TextAlignment = .leading,
        minimumScaleFactor: CGFloat = 1
    ) {
        self.text = text
        self.role = role
        self.color = color
        self.tracking = tracking
        self.lineSpacing = lineSpacing
        self.lineLimit = lineLimit
        self.alignment = alignment
        self.minimumScaleFactor = minimumScaleFactor
    }

    var body: some View {
        let compensationOn = strokeCompensation && role.supportsStrokeCompensation
        Group {
            if compensationOn {
                // 仅大标题：同色、≤0.3pt 的亚像素微 shadow，补一点字面厚度，不产生可见投影/灰边。
                styledBase
                    .shadow(
                        color: color.opacity(role.strokeShadowOpacity),
                        radius: role.strokeShadowRadius,
                        x: 0,
                        y: 0
                    )
            } else {
                styledBase
            }
        }
        .foregroundStyle(color)
    }

    /// 基础文本（含行距 / 对齐 / 行数限制 / 缩放下限）：
    /// 每个 overlay 副本都复用同一份样式，保证单行补偿不会改变换行与对齐。
    private var styledBase: some View {
        baseText
            .lineSpacing(lineSpacing)
            .multilineTextAlignment(alignment)
            .modifier(KnockLineLimit(lineLimit: lineLimit))
            .minimumScaleFactor(minimumScaleFactor)
    }

    /// 基础 Text：角色字体 + 角色字距（overlay 复用同一个 Text 值，换行一致）。
    private var baseText: Text {
        var base = Text(text)
            .font(KnockTypography.font(role, dynamicTypeSize: dynamicTypeSize))
        let resolvedTracking = tracking ?? role.tracking
        if resolvedTracking != 0 {
            base = base.kerning(resolvedTracking)
        }
        return base
    }
}

/// 可选 lineLimit（nil = 不限制，交给单元格自然换行）。
private struct KnockLineLimit: ViewModifier {
    let lineLimit: Int?

    func body(content: Content) -> some View {
        if let lineLimit {
            content.lineLimit(lineLimit)
        } else {
            content
        }
    }
}

// MARK: - 裸 Text 迁移出口

/// 页面暂时保留 `Text(...)` 时，用 `.knockFont(.pageTitle)` 只接字号/字重（无描边补偿）。
struct KnockFontModifier: ViewModifier {
    let role: KnockTypeRole
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    func body(content: Content) -> some View {
        content.font(KnockTypography.font(role, dynamicTypeSize: dynamicTypeSize))
    }
}

extension View {
    /// 只替换字号/字重（保留调用方原有颜色与修饰符）；需要描边补偿请改用 `KnockText`。
    func knockFont(_ role: KnockTypeRole) -> some View {
        modifier(KnockFontModifier(role: role))
    }
}