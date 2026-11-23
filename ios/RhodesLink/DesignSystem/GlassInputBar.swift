//
//  GlassInputBar.swift
//  聊天输入栏（Phase 3/4 重设计）：标准聊天软件外观（参考微信 / QQ）。
//
//  设计要点：
//  - 底部**只有**一行：附件按钮 + 输入框 + 发送/停止按钮，没有额外的玻璃胶囊或浮动容器；
//  - 输入框为圆角矩形（非胶囊），浅色白底 / 深色深灰底；
//  - 发送按钮为品牌橙圆形；生成中变为停止按钮；
//  - 背景为系统栏材质 + 顶部 0.5pt 分隔线（微信同款）；
//  - **键盘避让采用显式监听**（keyboardWillShow/Hide 抬升输入栏），不依赖父视图的
//    safeArea 推导（在 TabView + NavigationStack + safeAreaInset 组合下更可靠）。
//

import SwiftUI
import UIKit

struct GlassInputBar: View {
    @Binding var text: String
    var isStreaming: Bool
    var placeholder: String = "输入消息…"
    var onSend: () -> Void
    var onCancel: () -> Void
    /// T42：附件（+）→ PhotosPicker → 发送图片
    var onAddAttachment: () -> Void = {}

    @Environment(\.colorScheme) private var colorScheme
    @FocusState private var isFocused: Bool
    /// 键盘抬升高度（已扣除底部安全区，避免重复留白）
    @State private var keyboardInset: CGFloat = 0

    private var canSend: Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var fieldFill: Color {
        colorScheme == .dark ? Color.white.opacity(0.10) : Color(uiColor: .systemBackground)
    }

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            // 必要功能按钮 1：附件（+）
            Button(action: onAddAttachment) {
                Image(systemName: "plus")
                    .font(.system(size: 20, weight: .regular))
                    .foregroundStyle(.secondary)
                    .frame(width: 34, height: 36)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("添加附件")

            // 输入框（圆角矩形、多行自适应）
            TextField(placeholder, text: $text, axis: .vertical)
                .lineLimit(1...5)
                .font(.body)
                .foregroundStyle(.primary)
                .padding(.horizontal, 12)
                .padding(.vertical, 9)
                .background(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .fill(fieldFill)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.5)
                )
                .focused($isFocused)

            // 必要功能按钮 2：发送 / 停止
            trailingButton
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.bar)
        .overlay(alignment: .top) {
            Rectangle()
                .fill(Color.primary.opacity(0.10))
                .frame(height: 0.5)
        }
        // 键盘抬升：输入栏整体上移键盘高度（父视图需 ignoresSafeArea(.keyboard)，见 ChatView）
        .padding(.bottom, keyboardInset)
        .animation(.easeOut(duration: 0.22), value: keyboardInset)
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { note in
            guard
                let end = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
            let safeBottom = Self.bottomSafeAreaInset
            keyboardInset = max(0, end.height - safeBottom)
            SelfTestLog.add("input keyboardWillShow height=\(end.height) safeBottom=\(safeBottom) inset=\(keyboardInset)")
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            keyboardInset = 0
            SelfTestLog.add("input keyboardWillHide")
        }
    }

    /// 当前窗口底部安全区（键盘 frame 已包含该区域，需扣除避免双倍留白）
    private static var bottomSafeAreaInset: CGFloat {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        for scene in scenes {
            if let window = scene.windows.first(where: { $0.isKeyWindow }) ?? scene.windows.first {
                return window.safeAreaInsets.bottom
            }
        }
        return 0
    }

    @ViewBuilder
    private var trailingButton: some View {
        if isStreaming {
            Button(action: onCancel) {
                Image(systemName: "stop.fill")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 34, height: 34)
                    .background(Circle().fill(GlassTokens.brandOrangeDeep))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("停止生成")
        } else {
            Button(action: onSend) {
                Image(systemName: "arrow.up")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 34, height: 34)
                    .background(
                        Circle().fill(canSend ? GlassTokens.brandOrange : Color.gray.opacity(0.35))
                    )
            }
            .buttonStyle(.plain)
            .disabled(!canSend)
            .accessibilityLabel("发送消息")
        }
    }
}
