//
//  AmbientBackground.swift
//  三层结构之第 1 层：环境背景（渐变 + 光斑 + 可选立绘模糊 + 暗角）。
//  内容层（高对比/不透明）与玻璃 chrome 层由各 Feature 页在此之上叠加。
//
//  可访问性响应：
//  - 降低透明度：光斑与立绘淡出，保留纯色渐变（保证可读）；
//  - 增强对比度：光斑更实、模糊更小、暗角加深；
//  - 减弱动态效果：视差系数归零（背景静止，不做跟随动画）。
//
//  深浅色：token 自适应（浅色冷灰蓝 / 深色深海军蓝），跟随系统外观。
//

import SwiftUI

struct AmbientBackground: View {
    /// 来自滚动容器的偏移（聊天页 / 列表页注入）
    var parallax: CGFloat = 0
    /// 是否绘制立绘模糊层（正式立绘资产待 Phase 2 提供，当前为形状占位）
    var showsStandee: Bool = true

    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorSchemeContrast) private var contrast
    @Environment(\.colorScheme) private var colorScheme

    /// 减弱动态效果 → 视差系数 0
    private var parallaxFactor: CGFloat { reduceMotion ? 0 : 1 }
    /// 增强对比度 → 光斑更实
    private var glowStrength: Double { contrast == .increased ? 0.32 : 1.0 }

    var body: some View {
        ZStack {
            // ① 环境基础渐变
            LinearGradient(
                colors: [GlassTokens.ambientTop, GlassTokens.ambientBottom],
                startPoint: .top,
                endPoint: .bottom
            )

            // ② 光斑（随滚动缓慢视差）
            Circle()
                .fill(GlassTokens.ambientGlowOrange.opacity(glowStrength))
                .frame(width: 320, height: 320)
                .blur(radius: contrast == .increased ? 60 : 90)
                .offset(
                    x: 120 - parallax * 0.10 * parallaxFactor,
                    y: -180 + parallax * 0.06 * parallaxFactor
                )
            Circle()
                .fill(GlassTokens.ambientGlowBlue.opacity(glowStrength))
                .frame(width: 280, height: 280)
                .blur(radius: contrast == .increased ? 60 : 80)
                .offset(
                    x: -140 + parallax * 0.06 * parallaxFactor,
                    y: 220 - parallax * 0.04 * parallaxFactor
                )

            // ③ 可选立绘模糊层（降低透明度时淡出）
            if showsStandee && !reduceTransparency && colorScheme == .dark {
                StandeeArtPlaceholder()
                    .opacity(0.35)
                    .offset(y: 40 + parallax * 0.03 * parallaxFactor)
            }

            // ④ 暗角（增强对比度时加深）
            RadialGradient(
                colors: [
                    .clear,
                    GlassTokens.vignette.opacity(contrast == .increased ? 1.4 : 1.0),
                ],
                center: .center,
                startRadius: 200,
                endRadius: 520
            )
        }
        .ignoresSafeArea()
    }
}

/// 立绘占位：真实角色立绘资产由 Phase 2 提供。
private struct StandeeArtPlaceholder: View {
    var body: some View {
        ZStack(alignment: .bottom) {
            Circle()
                .fill(
                    LinearGradient(
                        colors: [Color.white.opacity(0.9), Color(hex: 0xCBD5FF)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .frame(width: 140, height: 140)
                .offset(y: -210)
            Capsule()
                .fill(
                    LinearGradient(
                        colors: [Color(hex: 0x2A3550), Color(hex: 0x141A2E)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .frame(width: 220, height: 260)
        }
        .blur(radius: 40)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
        .padding(.trailing, 48)
        .padding(.bottom, 120)
    }
}
