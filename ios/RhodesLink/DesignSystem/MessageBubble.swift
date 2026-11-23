//
//  MessageBubble.swift
//  消息气泡：**不使用玻璃**（设计原则 3：玻璃上放长文伤可读性）。
//  - 高对比半透明/不透明色 + Dynamic Type（.body 自适应）；
//  - T34：支持真实历史消息的旁白（ai_json narration 段）、撤回态、shared 格式化的时间；
//  - 流式中：末尾闪烁光标（减弱动态效果时静态）；
//  - 系统提示：居中灰色胶囊。
//

import SwiftUI

/// 气泡方向（与 ChatModels.ChatMessage.Side 对应，独立定义避免 DesignSystem 依赖 Feature 层）
enum BubbleSide {
    case incoming
    case outgoing
    case system
}

struct MessageBubble: View {
    var text: String
    var side: BubbleSide
    var isStreaming: Bool = false
    /// 旁白（**T59 起旧字段**：旁白已改为独立 `NarrationBubble`，聊天页不再传本参数；
    /// 保留以兼容群聊/历史调用点，避免渲染口径混用）
    var narration: String = ""
    /// ai_json 全部段落已撤回
    var isRecalled: Bool = false
    /// shared 侧格式化的展示时间（今天 HH:mm / 更早 M/d HH:mm）
    var timeText: String = ""
    /// T42：图片消息的本机文件 URL（非图片为 nil）
    var imageURL: URL? = nil
    /// T42：是否图片消息（决定是否按图片渲染 + 点击全屏）
    var isImageMessage: Bool = false
    /// T42：点击图片（全屏查看）
    var onImageTap: (() -> Void)? = nil
    /// T43 群聊：多发言人气泡的发送者名（接收方向显示在气泡上方；私聊/系统消息为空不显示）
    var senderName: String = ""

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var caretVisible = true

    var body: some View {
        switch side {
        case .system:
            systemRow
        case .incoming:
            bubble
                .frame(maxWidth: .infinity, alignment: .leading)
        case .outgoing:
            bubble
                .frame(maxWidth: .infinity, alignment: .trailing)
        }
    }

    private var bubble: some View {
        VStack(alignment: .leading, spacing: 5) {
            if side == .incoming && !senderName.isEmpty {
                Text(senderName)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .accessibilityLabel("发送者：\(senderName)")
            }
            if !narration.isEmpty {
                Text(narration)
                    .font(.footnote)
                    .italic()
                    .foregroundStyle(GlassTokens.bubbleIncomingText.opacity(0.72))
            }
            if isImageMessage, let imageURL {
                LocalMessageImage(url: imageURL)
                    .onTapGesture { onImageTap?() }
                    .accessibilityLabel("图片消息，点击全屏查看")
                    .accessibilityAddTraits(.isButton)
            }
            if !captionText.isEmpty {
                Text(captionText)
                    .font(.body)
                    .foregroundStyle(side == .outgoing ? GlassTokens.bubbleOutgoingText : GlassTokens.bubbleIncomingText)
                    .italic(isRecalled)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(backgroundStyle, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(alignment: .topLeading) {
            if isStreaming && caretVisible {
                caret
            }
        }
        .frame(maxWidth: 320, alignment: side == .outgoing ? .trailing : .leading)
        .padding(.leading, side == .incoming ? 0 : 48)
        .padding(.trailing, side == .incoming ? 48 : 0)
        .task(id: isStreaming) {
            guard isStreaming, !reduceMotion else { return }
            caretVisible = true
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 500_000_000)
                guard !Task.isCancelled else { break }
                caretVisible.toggle()
            }
        }
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

    /// 流式光标：用一个细窄的橙色圆角条叠在气泡左上角
    private var caret: some View {
        Capsule()
            .fill(GlassTokens.brandOrange)
            .frame(width: 2, height: 16)
            .offset(x: 6, y: 8)
            .opacity(0.85)
            .accessibilityHidden(true)
    }

    private var backgroundStyle: AnyShapeStyle {
        switch side {
        case .outgoing:
            return AnyShapeStyle(GlassTokens.bubbleOutgoing)
        case .incoming:
            return AnyShapeStyle(GlassTokens.bubbleIncoming)
        case .system:
            return AnyShapeStyle(GlassTokens.bubbleSystem)
        }
    }

    private var systemRow: some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(GlassTokens.bubbleSystem, in: Capsule())
            .frame(maxWidth: .infinity, alignment: .center)
            .accessibilityElement(children: .combine)
            .accessibilityLabel("系统提示：\(text)")
    }
}

/// 图片消息缩略图（T42）：本地文件走 UIImage（shared 媒体目录），远程 http(s) 走 AsyncImage；
/// 加载失败显示占位图标（不崩溃、不阻塞时间线）。
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
                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
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
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
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
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Color.primary.opacity(0.08))
            ProgressView().controlSize(.small)
        }
        .frame(width: 180, height: 120)
    }

    private var placeholder: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Color.primary.opacity(0.08))
            Image(systemName: "photo")
                .font(.system(size: 26, weight: .light))
                .foregroundStyle(.secondary)
        }
        .frame(width: 180, height: 120)
        .accessibilityLabel("图片无法显示")
    }
}

/// 头像占位：首字母 + 品牌渐变圆（真实立绘/头像资产 Phase 2 提供）
struct AvatarBadge: View {
    var name: String
    var size: CGFloat = 44

    private var initial: String {
        String(name.trimmingCharacters(in: .whitespaces).prefix(1))
    }

    var body: some View {
        ZStack {
            Circle()
                .fill(
                    LinearGradient(
                        colors: [GlassTokens.brandOrange, GlassTokens.brandOrangeDeep],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
            Text(initial.isEmpty ? "?" : initial)
                .font(.system(size: size * 0.42, weight: .semibold, design: .rounded))
                .foregroundStyle(.white)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// T59 旁白气泡：**独立一条**、居中、浅色卡片、无头像（对齐 Android `MessageList.kt:397-425`
/// 旁白分支：90% 宽 Card + 细边框 + 斜体正文；线上模式不产生旁白段）。
/// 台词与旁白按 segments 顺序交错出现，不再合并进同一条气泡。
struct NarrationBubble: View {
    var text: String
    var isStreaming: Bool = false

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var caretVisible = true

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 2) {
            Text(displayText)
                .font(.footnote)
                .italic()
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
            if isStreaming && caretVisible {
                Capsule()
                    .fill(GlassTokens.brandOrange)
                    .frame(width: 2, height: 14)
                    .opacity(0.85)
                    .accessibilityHidden(true)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color.primary.opacity(0.045))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(Color.primary.opacity(0.08), lineWidth: 0.5)
        )
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 20)
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
