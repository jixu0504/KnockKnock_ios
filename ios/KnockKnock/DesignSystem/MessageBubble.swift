//
//  MessageBubble.swift
//  「敲敲」消息气泡（P1 视觉重写）。
//
//  设计依据：docs/敲敲UI改版计划书-v1.1.md
//  - 第 1.1 节图 A：发送气泡 `#3364E7` 白字 / 接收气泡纯白 `#FFFFFF` 文字 `#0B0B0C`；
//    胶囊（圆角≈半高），字号 18pt heavy（`KnockTypeRole.bubble`），内边距 H10/V8，行距 4pt；
//    尾巴：角色左上 / 玩家右上，Path 绘制 8×6pt；**无描边、无阴影**；
//  - 第 1.2 节配色纪律：蓝 = 聊天消息语言（发送气泡 ≤3% 面积），黄只做交互；
//  - 第 2.2 节：旁白改漫画旁白（左 2pt 黄竖条 + 斜体灰白字）；系统消息改敲敲灰字居中；
//  - 第 7.1 节验收：白胶囊 + 左上尾巴 / 蓝胶囊 + 右上尾巴 / 胶囊圆角 H10 V8 /
//    系统消息不抢视觉 / Dynamic Type 不裁切。
//
//  能力保留（业务零改动）：
//  - `narration` 旧字段仍可渲染（T59 起旁白走独立 `NarrationBubble`，本字段仅为兼容旧调用点）；
//  - 撤回态（斜体 + 次文字色）、图片消息（含点击全屏回调）、流式光标（减弱动态效果时静止）；
//  - 群聊 `senderName`（接收方向显示在气泡上方）、Dynamic Type（`KnockText` 全档位缩放）；
//  - 长按菜单由 ChatView / GroupChatView 在外层 `.knockContextMenu(actions:)` 负责（本文件不接管；
//    自绘面板见 `KnockContextMenu.swift`，已替代系统 `.contextMenu` 的 iOS 26 液态玻璃）。
//
//  可访问性：
//  - 气泡文字全部经 `KnockText`（Dynamic Type + 描边加粗补偿）；
//  - 系统提示朗读「系统提示：…」；发送者名朗读「发送者：…」；图片消息「图片消息，点击全屏查看」；
//  - 头像为装饰元素（父视图负责 `.accessibilityHidden`），人名由气泡/顶栏朗读；
//  - 减弱动态效果：流式光标不闪烁，只保留静态光标。
//

import SwiftUI

/// 气泡方向（与 ChatModels.ChatMessage.Side 对应，独立定义避免 DesignSystem 依赖 Feature 层）
enum BubbleSide {
    case incoming
    case outgoing
    case system
}

// MARK: - 气泡形状（圆角矩形 + 左上 / 右上小尖角尾巴）

/// 敲敲气泡形状：圆角矩形（胶囊感）+ 8×6pt 小尖角尾巴。
/// - `leading`：接收气泡左上角小尖角（图 A 角色侧）；
/// - `trailing`：发送气泡右上角小尖角（图 A 玩家侧）；
/// - 尾巴与主体同一 Path 相加，**无描边**时视觉无接缝（符合「无描边无阴影」）。
struct KnockBubbleShape: Shape {
    enum Tail {
        case none
        case leading
        case trailing
    }

    var tail: Tail
    var cornerRadius: CGFloat

    init(tail: Tail = .none, cornerRadius: CGFloat = KnockTokens.bubbleRadius) {
        self.tail = tail
        self.cornerRadius = cornerRadius
    }

    func path(in rect: CGRect) -> Path {
        // 圆角 = min(18, 半高)：单行即胶囊，多行保持大圆角。
        let radius = max(0, min(cornerRadius, min(rect.width, rect.height) / 2))
        var path = Path(roundedRect: rect, cornerRadius: radius, style: .continuous)

        switch tail {
        case .none:
            break
        case .leading:
            // 左上小尖角：底点落在顶边 / 左边的直边段上，顶点突出到气泡左上方外侧（8×6pt 量级）。
            // 两个底点均在主体边界上，三角形与圆角矩形的重叠区由非零环绕规则填充（同向 = 逆时针）。
            let baseTopX = min(rect.minX + 19, rect.maxX - 2)
            let baseSideY = min(rect.minY + 18, rect.maxY - 2)
            var beak = Path()
            beak.move(to: CGPoint(x: rect.minX - 6, y: rect.minY + 4))
            beak.addLine(to: CGPoint(x: baseTopX, y: rect.minY))
            beak.addLine(to: CGPoint(x: rect.minX, y: baseSideY))
            beak.closeSubpath()
            path.addPath(beak)
        case .trailing:
            // 右上小尖角（镜像）。顶点顺序与圆角矩形同向（逆时针），避免非零环绕规则把重叠区镂空。
            let baseTopX = max(rect.maxX - 19, rect.minX + 2)
            let baseSideY = min(rect.minY + 18, rect.maxY - 2)
            var beak = Path()
            beak.move(to: CGPoint(x: rect.maxX + 6, y: rect.minY + 4))
            beak.addLine(to: CGPoint(x: rect.maxX, y: baseSideY))
            beak.addLine(to: CGPoint(x: baseTopX, y: rect.minY))
            beak.closeSubpath()
            path.addPath(beak)
        }
        return path
    }
}

// MARK: - 消息气泡

struct MessageBubble: View {
    var text: String
    var side: BubbleSide
    var isStreaming: Bool = false
    /// 旁白（**T59 起旧字段**：旁白已改为独立 `NarrationBubble`，聊天页不再传本参数；
    /// 保留以兼容群聊/历史调用点，避免渲染口径混用）
    var narration: String = ""
    /// ai_json 全部段落已撤回
    var isRecalled: Bool = false
    /// shared 侧格式化的展示时间（今天 HH:mm / 更早 M/d HH:mm；时间分隔由 ChatView 负责）
    var timeText: String = ""
    /// T42：图片消息的本机文件 URL（非图片为 nil）
    var imageURL: URL? = nil
    /// T42：是否图片消息（决定是否按图片渲染 + 点击全屏）
    var isImageMessage: Bool = false
    /// T42：点击图片（全屏查看）
    var onImageTap: (() -> Void)? = nil
    /// T43 群聊：多发言人气泡的发送者名（接收方向显示在气泡上方；私聊/系统消息为空不显示）
    var senderName: String = ""
    /// 表情消息的本地资源名；非空时按表情渲染（无相框、无全屏，尺寸固定）。
    var emojiAssetName: String = ""

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var caretVisible = true

    var body: some View {
        switch side {
        case .system:
            systemRow
        case .incoming, .outgoing:
            messageColumn
                .frame(
                    maxWidth: .infinity,
                    alignment: side == .outgoing ? .trailing : .leading
                )
        }
    }

    // MARK: 布局

    /// 发言者名（群聊，气泡外上方）+ 气泡本体。
    private var messageColumn: some View {
        VStack(alignment: side == .outgoing ? .trailing : .leading, spacing: 4) {
            if side == .incoming && !senderName.isEmpty {
                KnockText(senderName, role: .captionStrong, color: KnockTokens.textSecondary, lineLimit: 1)
                    .padding(.leading, 6)
                    .accessibilityLabel("发送者：\(senderName)")
            }
            bubbleBody
        }
    }

    @ViewBuilder
    private var bubbleBody: some View {
        if !emojiAssetName.isEmpty {
            // 表情不套气泡底色：直接是一张表情图，视觉上更接近聊天软件的表情消息。
            EmojiImage(assetName: emojiAssetName, size: 96)
                .accessibilityLabel("表情消息")
        } else {
            styledBubbleBody
        }
    }

    private var styledBubbleBody: some View {
        bubbleContent
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(bubbleShape.fill(bubbleFill))
            .overlay(alignment: .topLeading) {
                if isStreaming && caretVisible {
                    caret
                }
            }
            .frame(maxWidth: 320, alignment: side == .outgoing ? .trailing : .leading)
    }

    private var bubbleShape: KnockBubbleShape {
        switch side {
        case .outgoing:
            return KnockBubbleShape(tail: .trailing)
        case .incoming:
            return KnockBubbleShape(tail: .leading)
        case .system:
            return KnockBubbleShape(tail: .none)
        }
    }

    @ViewBuilder
    private var bubbleContent: some View {
        VStack(alignment: .leading, spacing: 4) {
            // 旧 narration 字段（兼容）：斜体小字放在正文上方，与正文同气泡。
            if !narration.isEmpty {
                Text(narration)
                    .knockFont(.caption)
                    .italic()
                    .foregroundStyle(secondaryTextColor)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if isImageMessage, let imageURL {
                // 参考图：图片消息带白色相框（5pt 白边 + 大圆角）+ 气泡尾巴
                LocalMessageImage(url: imageURL)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .padding(5)
                    .background(
                        RoundedRectangle(cornerRadius: 20, style: .continuous)
                            .fill(Color.white)
                    )
                    .onTapGesture { onImageTap?() }
                    .accessibilityLabel("图片消息，点击全屏查看")
                    .accessibilityAddTraits(.isButton)
            }

            if !captionText.isEmpty {
                bubbleText
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    /// 正文：18pt medium（`KnockTypography` 出口 + Dynamic Type），真字重渲染。
    /// 说明：旧实现用「单份 `Text` + 同色 0.55pt shadow」近似加粗，真机多行时边缘发虚；
    /// 现由 `KnockTypeRole.bubble` 的 Medium 真字重承担强调，气泡正文不再叠加任何 shadow。
    @ViewBuilder
    private var bubbleText: some View {
        Text(captionText)
            .knockFont(.bubble)
            .italic(isRecalled)
            .foregroundStyle(primaryTextColor)
            .lineSpacing(4)
            .multilineTextAlignment(.leading)
            .fixedSize(horizontal: false, vertical: true)
    }

    /// 图片消息的正文 = 附言（"[图片]"/"[图片发送失败]"/"（空消息）" 等占位不重复显示）
    private var captionText: String {
        guard isImageMessage else { return displayText }
        let placeholders = ["[图片]", "[图片发送失败]", "（空消息）", "（此消息已撤回）"]
        if placeholders.contains(text) { return "" }
        return displayText
    }

    private var displayText: String {
        if isStreaming && text.isEmpty { return "…" }
        return text
    }

    // MARK: 颜色（图 A：蓝气泡白字 / 白气泡 `#0B0B0C` 字）

    private var bubbleFill: Color {
        side == .outgoing ? KnockTokens.chatBlue : KnockTokens.bubbleIncoming
    }

    private var primaryTextColor: Color {
        side == .outgoing ? KnockTokens.bubbleOutgoingText : KnockTokens.bubbleIncomingText
    }

    /// 旁白/次要文字：蓝气泡内用 85% 白，白气泡内用次文字灰（实色，禁止半透明白铺底）。
    private var secondaryTextColor: Color {
        side == .outgoing
            ? Color.white.opacity(0.82)
            : KnockTokens.textSecondary
    }

    /// 流式光标：竖条（黄 = 生成中状态点），随气泡方向贴左上角。
    private var caret: some View {
        Capsule()
            .fill(side == .outgoing ? Color.white : KnockTokens.electric)
            .frame(width: 2.5, height: 15)
            .offset(x: 6, y: 8)
            .opacity(0.9)
            .accessibilityHidden(true)
    }

    // MARK: 系统消息（灰色小字居中，不加气泡）

    private var systemRow: some View {
        KnockText(
            text,
            role: .caption,
            color: KnockTokens.timestampText,
            lineSpacing: 2,
            alignment: .center
        )
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("系统提示：\(text)")
    }
}

// MARK: - 图片消息缩略图

/// 图片消息缩略图（T42）：本地文件走 UIImage（shared 媒体目录），远程 http(s) 走 AsyncImage；
/// 加载失败显示占位图标（不崩溃、不阻塞时间线）。视觉换敲敲行底 + 1.5pt 描边。
struct LocalMessageImage: View {
    let url: URL

    @State private var image: UIImage?
    @State private var failed = false

    private var isRemote: Bool { !url.isFileURL }

    var body: some View {
        Group {
            if isRemote {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let loaded):
                        loaded
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: 240, maxHeight: 220)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    case .failure:
                        placeholder
                    default:
                        loading
                    }
                }
            } else if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: 240, maxHeight: 220)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            } else if failed {
                placeholder
            } else {
                loading
            }
        }
        .contentShape(Rectangle())
        .task(id: url) {
            guard !isRemote else { return }
            let path = url.path
            let loaded = await Task.detached(priority: .userInitiated) {
                UIImage(contentsOfFile: path)
            }.value
            image = loaded
            failed = loaded == nil
        }
    }

    private var loading: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(KnockTokens.row)
            ProgressView()
                .controlSize(.small)
                .tint(KnockTokens.electric)
        }
        .frame(width: 180, height: 120)
        .overlay {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
        }
    }

    private var placeholder: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(KnockTokens.row)
            Image(systemName: "photo")
                .font(.system(size: 24, weight: .heavy))
                .foregroundStyle(KnockTokens.textTertiary)
        }
        .frame(width: 180, height: 120)
        .overlay {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
        }
        .accessibilityLabel("图片无法显示")
    }
}

// MARK: - 头像占位

/// 头像占位（旧 API 保留：RoleEditView 等仍按 `AvatarBadge(name:size:)` 调用）。
/// 视觉已迁移到敲敲体系 `KnockAvatar`：黑底 + 1.5pt 浅蓝圈 + 白色 heavy 首字（计划书决策 4），
/// 不再使用旧的品牌橙渐变。
struct AvatarBadge: View {
    var name: String
    var size: CGFloat = 44

    var body: some View {
        KnockAvatar(avatarId: "", name: name, size: size)
            .accessibilityHidden(true)
    }
}

// MARK: - 旁白气泡

/// T59 起旁白独立一条（对齐 Android `MessageList.kt` 旁白分支）；P1 视觉改为「漫画旁白」：
/// 左 2pt 电光黄竖条 + 斜体灰白字，无卡片底、无描边（计划书 2.2 节）。
/// 线上模式不产生旁白段；台词与旁白按 segments 顺序交错出现。
struct NarrationBubble: View {
    var text: String
    var isStreaming: Bool = false

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var caretVisible = true

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            // 漫画旁白：2pt 电光黄竖条（黄仅此 2pt，面积远小于 5%）
            Rectangle()
                .fill(KnockTokens.electric)
                .frame(width: 2)
                .frame(maxHeight: .infinity)
                .accessibilityHidden(true)

            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(displayText)
                    .knockFont(.caption)
                    .italic()
                    .foregroundStyle(KnockTokens.textSecondary)
                    .lineSpacing(3)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if isStreaming && caretVisible {
                    Capsule()
                        .fill(KnockTokens.electric)
                        .frame(width: 2, height: 13)
                        .opacity(0.9)
                        .accessibilityHidden(true)
                }
            }
        }
        .padding(.vertical, 4)
        .padding(.leading, 2)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, KnockTokens.spacingS)
        .fixedSize(horizontal: false, vertical: true)
        .task(id: isStreaming) {
            guard isStreaming, !reduceMotion else { return }
            caretVisible = true
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 500_000_000)
                guard !Task.isCancelled else { break }
                caretVisible.toggle()
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(isStreaming ? "旁白生成中：\(text)" : "旁白：\(text)")
    }

    private var displayText: String {
        if isStreaming && text.isEmpty { return "…" }
        return text
    }
}