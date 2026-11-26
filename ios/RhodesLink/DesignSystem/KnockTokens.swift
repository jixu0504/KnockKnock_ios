//
//  KnockTokens.swift
//  「敲敲」设计系统 token 总出口（P0 基座）。
//
//  设计依据：docs/敲敲UI改版计划书-v1.1.md
//  - 第 1.2 节图 B/C/D 实测色板与条形栏规格（电光黄 #F2FF00 / 黄按压 #D8E600 /
//    选中外圈 #8CC4FF / 聊天蓝 #3364E7 / 危险 #BF2207·#FF3B30）；
//  - 第 1.3 节浅色模式推导（底 #F4F4F2 / 面板 #FFFFFF / 行 #F7F7F5 / 描边 #E2E2DD·#DDDDD8）；
//  - 第 1.2 节「配色逻辑」面积纪律：黑 ≈85%、白 ≈10%、黄 ≤5%、蓝 ≤3%、灰 ≈5%、红 <1%。
//
//  纪律（不许发明 token）：
//  1. 页面层只引用 `KnockTokens` / `KnockTypography`，不得再写裸 `Color(hex:)`；
//  2. 黄色只做「选中 / 主 CTA / 品牌 / Tab 选中」；蓝色只做聊天语义（气泡/链接/进度）；
//     禁止用黄色画发送气泡、禁止用蓝色画 Tab 选中；
//  3. 禁止毛玻璃 / 半透明白 / 渐变光斑 —— 底色一律实色 + `KnockTexture` 程序化纹理；
//  4. 深浅双主题：颜色一律经 `Color.rhodesHex(light:dark:)` 自适应，App 保留深/浅/跟随系统。
//
//  可访问性：
//  - 增强对比度（colorSchemeContrast == .increased）时使用 `strokeStrong` / `strokeContrast`；
//  - 降低透明度（reduceTransparency）时纹理层整体关闭（见 KnockTexture）；
//  - 减弱动态效果（reduceMotion）时选中胶囊不做弹簧动画（见 KnockSegmentedBar）。
//
//  全局开关（设置页可写入，P0 先给 UserDefaults 键 + 环境值两段式出口）：
//  - 纹理：`knock_texture_enabled`（默认开）
//  - 字体描边加粗补偿：`knock_stroke_compensation`（默认开）
//

import SwiftUI

// MARK: - Token 总出口

enum KnockTokens {

    // MARK: 品牌强调色（黄 = App 交互语言，全屏面积 ≤5%）

    /// 电光黄 `#F2FF00`：仅用于「选中胶囊 / 主 CTA / 品牌 logo / Tab 选中」。
    static let electric = Color(hex: 0xF2FF00)
    /// 黄·按压 / 暗部 `#D8E600`。
    static let electricPressed = Color(hex: 0xD8E600)
    /// 黄底选中项外圈高光「浅蓝」`#8CC4FF`（计划书 1.2 关键细节：2–3px 外圈）。
    static let electricRing = Color(hex: 0x8CC4FF)
    /// 黄底上的文字/图标：黑（深浅主题均为黑，保证对比度 > 15:1）。
    static let onElectric = Color(hex: 0x0B0B0C)

    // MARK: 聊天语义色（蓝 = 聊天消息语言，面积 ≤3%）

    /// 聊天发送气泡蓝 `#1C55E2`（聊天页参考图实测 #1C55E2/#1C55E4）。
    static let chatBlue = Color(hex: 0x1C55E2)
    /// 聊天蓝按压态 / 浅色模式压深值 `#2F5FE0`（计划书 1.3：浅底上提高对比度）。
    static let chatBluePressed = Color(hex: 0x2F5FE0)
    /// 接收气泡：深色 `#E6E7EB` 浅灰白（聊天页参考图实测），浅色 `#FFFFFF`。
    static let bubbleIncoming = Color.rhodesHex(0xFFFFFF, 0xE6E7EB)
    /// 接收气泡文字 `#0B0B0C`。
    static let bubbleIncomingText = Color(hex: 0x0B0B0C)
    /// 发送气泡文字白。
    static let bubbleOutgoingText = Color(hex: 0xFFFFFF)
    /// 聊天顶栏浮动条底（参考图实测 `#080808` / 浅色白）。
    static let chatHeaderBar = Color.rhodesHex(0xFFFFFF, 0x080808)
    /// 聊天消息面板底（参考图实测 `#050505` / 浅色白）。
    static let chatPanel = Color.rhodesHex(0xFFFFFF, 0x050505)
    /// 输入栏「+」圆钮底（参考图实测 `#292929` / 浅色 `#E4E4E0`）。
    static let plusCircle = Color.rhodesHex(0xE4E4E0, 0x292929)
    /// 白色输入框在浅色主题下的分层描边。
    static let strokeSubtleWhite = Color.rhodesHex(0xDDDDD8, 0xFFFFFF, lightAlpha: 1, darkAlpha: 0)
    /// 品牌标识金（敲敲黄色手机 logo 实测 `#F8D328`）。
    static let brandGold = Color(hex: 0xF8D328)
    /// 时间分隔 / 系统胶囊底（实色灰阶，禁用半透明白）。
    static let bubbleSystem = Color.rhodesHex(0xECECE9, 0x1A1A1A)
    /// 时间分隔文字 `#8A8A8E`（图 A）。
    static let timestampText = Color.rhodesHex(0x8A8A8E, 0x8A8A8E)

    // MARK: 危险 / 关闭

    /// 危险底 `#BF2207`（关闭胶囊、删除底）。
    static let danger = Color(hex: 0xBF2207)
    /// 危险亮色（图标/文字）`#FF3B30`。
    static let dangerBright = Color(hex: 0xFF3B30)
    /// 危险底上的文字/图标：白。
    static let onDanger = Color(hex: 0xFFFFFF)

    // MARK: 底色 / 面板 / 行（黑 ≈85% 面积，浅色为暖白体系）

    /// 页面底：深 `#000000` / 浅 `#F4F4F2`（暖白）。
    static let canvas = Color.rhodesHex(0xF4F4F2, 0x000000)
    /// 内容面板：深 `#0A0A0A` / 浅 `#FFFFFF`。
    static let panel = Color.rhodesHex(0xFFFFFF, 0x0A0A0A)
    /// 面板内嵌层（面板内的分组底 / 弹窗内容区）。
    static let panelInset = Color.rhodesHex(0xFBFBF9, 0x121212)
    /// 列表行 / 输入框底：深 `#171717` / 浅 `#F7F7F5`。
    static let row = Color.rhodesHex(0xF7F7F5, 0x171717)
    /// 行按压态（略提亮/压暗，实色，非透明叠加）。
    static let rowPressed = Color.rhodesHex(0xEFEFEC, 0x1F1F1F)
    /// 行内图标圆底（比行再亮一档）。
    static let rowIconWell = Color.rhodesHex(0xFFFFFF, 0x222222)

    // MARK: 描边

    /// 主描边：深 `#2A2A2E` / 浅 `#E2E2DD`，1.5pt。
    static let stroke = Color.rhodesHex(0xE2E2DD, 0x2A2A2E)
    /// 次描边（行 / 虚线按钮）：深 `#3A3A3E` / 浅 `#DDDDD8`，1.5pt。
    static let strokeSecondary = Color.rhodesHex(0xDDDDD8, 0x3A3A3E)
    /// 虚线描边（比次描边再淡一档，保证虚线不抢视觉）。
    static let strokeDashed = Color.rhodesHex(0xC9C9C4, 0x3A3A3E)
    /// 增强对比度模式下的描边（iOS 辅助功能）。
    static let strokeContrast = Color.rhodesHex(0x3A3A3A, 0x6E6E73)

    // MARK: 文字

    /// 主文字：深 `#FFFFFF` / 浅 `#0B0B0C`。
    static let textPrimary = Color.rhodesHex(0x0B0B0C, 0xFFFFFF)
    /// 次文字：深 `#9A9A9A` / 浅 `#6E6E73`。
    static let textSecondary = Color.rhodesHex(0x6E6E73, 0x9A9A9A)
    /// 三级文字（占位符 / 极淡说明）。
    static let textTertiary = Color.rhodesHex(0x9A9A97, 0x6E6E73)
    /// 反色文字（黄底 / 蓝底 / 红底上的白字）——蓝底用白，黄底用 `onElectric`。
    static let textOnDark = Color(hex: 0xFFFFFF)

    // MARK: 搜索条

    /// 搜索条底：深 `#1C1C1C` / 浅 `#FFFFFF`。
    static let searchField = Color.rhodesHex(0xFFFFFF, 0x1C1C1C)
    /// 搜索条描边：深 `#303030` / 浅 `#E2E2DD`。
    static let searchStroke = Color.rhodesHex(0xE2E2DD, 0x303030)
    /// 搜索条内竖分隔线。
    static let searchDivider = Color.rhodesHex(0xE2E2DD, 0x3A3A3E)

    // MARK: 空态（E M P T Y）

    /// 空态 pill 底：深 `#060606` / 浅 `#EFEFEC`。
    static let emptyWell = Color.rhodesHex(0xEFEFEC, 0x060606)
    /// 空态 pill 文字：深 `#5A5A5A` / 浅 `#9A9A97`。
    static let emptyText = Color.rhodesHex(0x9A9A97, 0x5A5A5A)

    // MARK: 纹理（程序化 Canvas/CG 生成，见 KnockTexture.swift）

    /// 迷彩底基色：深 `#050505` / 浅 `#F4F4F2`。
    static let camoBase = Color.rhodesHex(0xF4F4F2, 0x050505)
    /// 迷彩块主色：深 `#0D0D0D` / 浅 `#ECECE9`（对比度 ≤3%）。
    static let camoBlock = Color.rhodesHex(0xECECE9, 0x0D0D0D)
    /// 迷彩块副色：深 `#111111` / 浅 `#E9E9E5`。
    static let camoBlockAlt = Color.rhodesHex(0xE9E9E5, 0x111111)
    /// 面板棋盘格墨色（极淡，alpha 由 KnockTexture 控制）。
    static let checkerInk = Color.rhodesHex(0x000000, 0xFFFFFF)

    // MARK: 语义旧名（玻璃 → 实色，供 GlassTokens 映射；新代码不要用）

    /// ⚠️ 旧玻璃高光描边 → 已映射为主描边（保留旧名仅为兼容调用面）。
    static let legacyGlassStroke = stroke
    /// ⚠️ 旧玻璃投影 → 已移除（实色面板不需要投影）。
    static let legacyGlassShadow = Color.clear

    // MARK: 尺寸（计划书 1.2 条形栏规格）

    /// 搜索条高 44pt。
    static let searchHeight: CGFloat = 44
    /// 一级分段栏（Tab 替代）高 44pt。
    static let segmentedHeight: CGFloat = 44
    /// 二级筛选栏高 36pt。
    static let segmentedSmallHeight: CGFloat = 36
    /// 选中黄胶囊相对容器内缩 4pt。
    static let segmentedInset: CGFloat = 4
    /// App 顶栏高 44pt（计划书 1.1 顶栏）。
    static let headerHeight: CGFloat = 44
    /// 胶囊行最小高 64pt（范围 64–72pt）。
    static let rowMinHeight: CGFloat = 64
    /// 胶囊行设计高 68pt（居中于 64–72 区间）。
    static let rowHeight: CGFloat = 68
    /// 行内圆形图标 / 头像位 44pt。
    static let rowIconSize: CGFloat = 44
    /// 聊天头像 36pt（图 A：直径 ≈36pt，无描边）。
    static let chatAvatarSize: CGFloat = 36

    // MARK: 圆角 / 描边宽度

    /// 面板圆角 20pt。
    static let panelRadius: CGFloat = 20
    /// 小面板 / 弹窗内嵌块圆角。
    static let panelRadiusSmall: CGFloat = 14
    /// 聊天气泡圆角 18pt（胶囊近半高；消息域由 T3 使用）。
    static let bubbleRadius: CGFloat = 18
    /// 标准描边 1.5pt。
    static let strokeWidth: CGFloat = 1.5
    /// 强调描边 2pt（增强对比度 / 外圈）。
    static let strokeWidthStrong: CGFloat = 2
    /// 发丝线 1pt（竖分隔、次分隔）。
    static let hairline: CGFloat = 1

    // MARK: 间距

    static let spacingXXS: CGFloat = 2
    static let spacingXS: CGFloat = 4
    static let spacingS: CGFloat = 8
    static let spacingM: CGFloat = 12
    static let spacingL: CGFloat = 16
    static let spacingXL: CGFloat = 20
    static let spacingXXL: CGFloat = 28
    /// 页面左右边距 16pt（与面板内边距一致，保证左右对齐）。
    static let screenPadding: CGFloat = 16
    /// 面板内边距 16pt。
    static let panelPadding: CGFloat = 16
    /// 列表行内边距（左 12 / 右 16：右侧留 chevron）。
    static let rowPaddingLeading: CGFloat = 12
    static let rowPaddingTrailing: CGFloat = 16

    // MARK: 控件尺寸

    /// 主/次按钮高 46pt。
    static let buttonHeight: CGFloat = 46
    /// 虚线按钮高 46pt。
    static let dashedButtonHeight: CGFloat = 46
    /// Chip 高 30pt。
    static let chipHeight: CGFloat = 30
    /// Badge 最小高 18pt。
    static let badgeHeight: CGFloat = 18
    /// 空态 pill 高 30pt。
    static let emptyPillHeight: CGFloat = 30
    /// 空态 pill 水平内边距。
    static let emptyPillPadding: CGFloat = 16

    // MARK: 面积纪律（计划书 1.2 配色逻辑，供画廊/验收自检引用）

    /// 黄色全屏面积上限 5%。
    static let maxAccentAreaRatio: Double = 0.05
    /// 蓝色全屏面积上限 3%。
    static let maxChatBlueAreaRatio: Double = 0.03
    /// 浅色模式纹理对比度上限（≤3%，计划书风险 7）。
    static let maxLightTextureContrast: Double = 0.03

    // MARK: 画廊色板（KnockGalleryView 截图验收用；一处改全站生效）

    /// 色板条目（名字 / 十六进制 / 实际 Color / 用途说明）。
    static let swatches: [KnockSwatch] = [
        KnockSwatch(name: "电光黄", lightHex: "F2FF00", darkHex: "F2FF00", color: electric,
                    usage: "选中胶囊 / 主 CTA / 品牌 logo（≤5%）"),
        KnockSwatch(name: "黄按压", lightHex: "D8E600", darkHex: "D8E600", color: electricPressed,
                    usage: "黄色按压/暗部"),
        KnockSwatch(name: "选中外圈", lightHex: "8CC4FF", darkHex: "8CC4FF", color: electricRing,
                    usage: "黄胶囊外圈高光（1.5pt）"),
        KnockSwatch(name: "聊天蓝", lightHex: "3364E7", darkHex: "3364E7", color: chatBlue,
                    usage: "仅聊天：发送气泡 / 链接 / 进度（≤3%）"),
        KnockSwatch(name: "危险", lightHex: "BF2207", darkHex: "BF2207", color: danger,
                    usage: "关闭 / 删除 / 错误底"),
        KnockSwatch(name: "危险亮", lightHex: "FF3B30", darkHex: "FF3B30", color: dangerBright,
                    usage: "危险图标 / 文字"),
        KnockSwatch(name: "页面底", lightHex: "F4F4F2", darkHex: "000000", color: canvas,
                    usage: "页面底 + 迷彩纹理底"),
        KnockSwatch(name: "面板", lightHex: "FFFFFF", darkHex: "0A0A0A", color: panel,
                    usage: "内容面板 / 分组容器"),
        KnockSwatch(name: "行底", lightHex: "F7F7F5", darkHex: "171717", color: row,
                    usage: "胶囊行 / 输入框"),
        KnockSwatch(name: "主描边", lightHex: "E2E2DD", darkHex: "2A2A2E", color: stroke,
                    usage: "面板 / 分段栏描边 1.5pt"),
        KnockSwatch(name: "次描边", lightHex: "DDDDD8", darkHex: "3A3A3E", color: strokeSecondary,
                    usage: "行 / 虚线按钮描边 1.5pt"),
        KnockSwatch(name: "主文字", lightHex: "0B0B0C", darkHex: "FFFFFF", color: textPrimary,
                    usage: "标题 / 选中外文字"),
        KnockSwatch(name: "次文字", lightHex: "6E6E73", darkHex: "9A9A9A", color: textSecondary,
                    usage: "副标题 / 占位符"),
        KnockSwatch(name: "搜索底", lightHex: "FFFFFF", darkHex: "1C1C1C", color: searchField,
                    usage: "搜索条底"),
        KnockSwatch(name: "搜索描边", lightHex: "E2E2DD", darkHex: "303030", color: searchStroke,
                    usage: "搜索条描边 1.5pt"),
        KnockSwatch(name: "空态底", lightHex: "EFEFEC", darkHex: "060606", color: emptyWell,
                    usage: "E M P T Y pill 底"),
    ]
}

// MARK: - 色板条目（画廊 / 验收复用）

/// 一条 token 色板：名字 + 深浅十六进制 + 实际颜色 + 用途。
struct KnockSwatch: Identifiable {
    let name: String
    let lightHex: String
    let darkHex: String
    let color: Color
    let usage: String

    var id: String { name }

    /// 深浅同值时只显示一个 hex（如品牌黄/聊天蓝）。
    var hexLabel: String {
        lightHex == darkHex ? "#\(lightHex)" : "#\(lightHex) / #\(darkHex)"
    }
}

// MARK: - 全局开关（纹理 / 字体描边补偿）

/// 「敲敲」全局视觉开关：默认值落 UserDefaults，设置页与自检可读写。
/// - 纹理：`knock_texture_enabled`（默认 true，关闭后全站纯色底）；
/// - 字体描边补偿：`knock_stroke_compensation`（默认 true，PingFang 无 Black 的加粗补偿）。
enum KnockPreferences {
    static let textureKey = "knock_texture_enabled"
    static let strokeCompensationKey = "knock_stroke_compensation"

    static var textureEnabled: Bool {
        storedBool(textureKey, fallback: true)
    }

    static var strokeCompensationEnabled: Bool {
        storedBool(strokeCompensationKey, fallback: true)
    }

    static func setTextureEnabled(_ enabled: Bool) {
        UserDefaults.standard.set(enabled, forKey: textureKey)
    }

    static func setStrokeCompensationEnabled(_ enabled: Bool) {
        UserDefaults.standard.set(enabled, forKey: strokeCompensationKey)
    }

    private static func storedBool(_ key: String, fallback: Bool) -> Bool {
        guard UserDefaults.standard.object(forKey: key) != nil else { return fallback }
        return UserDefaults.standard.bool(forKey: key)
    }
}

// MARK: - 环境值（画廊可局部覆盖开关；设置页写入 UserDefaults 后由根容器注入）

private struct KnockTextureEnabledKey: EnvironmentKey {
    static let defaultValue: Bool = KnockPreferences.textureEnabled
}

private struct KnockStrokeCompensationKey: EnvironmentKey {
    static let defaultValue: Bool = KnockPreferences.strokeCompensationEnabled
}

extension EnvironmentValues {
    /// 是否绘制程序化纹理（降低透明度模式下内部还会强制关闭）。
    var knockTextureEnabled: Bool {
        get { self[KnockTextureEnabledKey.self] }
        set { self[KnockTextureEnabledKey.self] = newValue }
    }

    /// 是否启用字体描边加粗补偿（≥16pt 文字，`KnockText` 生效）。
    var knockStrokeCompensation: Bool {
        get { self[KnockStrokeCompensationKey.self] }
        set { self[KnockStrokeCompensationKey.self] = newValue }
    }
}