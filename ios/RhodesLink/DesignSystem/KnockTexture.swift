//
//  KnockTexture.swift
//  「敲敲」程序化纹理：页面底「斜切迷彩」+ 面板内「极淡 2pt 棋盘格」。
//
//  设计依据：docs/敲敲UI改版计划书-v1.1.md
//  - 第 1.2 节：背景 = `#111111` + `#050505` 斜切迷彩纹理；列表行/面板带极淡棋盘格；
//    **禁止毛玻璃 / 模糊 / 半透明白 / 渐变光斑**；
//  - 第 1.3 节 + 风险 7：浅色纹理对比度 ≤3%，面板内棋盘格更淡；
//  - 第 2.1 节：`Canvas` 程序化绘制，缓存为 `CGImage`，提供全局关闭开关。
//
//  性能策略（关键）：
//  1. 纹理是**静态**的：一次生成 `UIImage` 后进 `NSCache`，按「尺寸 + 深浅」键缓存，
//     绝不按帧重算；迷彩底按屏幕点尺寸（1x 采样，低对比纹理足够）生成；
//  2. 棋盘格生成 4×4pt 小瓦片，用 `ImagePaint` 平铺，内存常量级；
//  3. 纹理层一律 `allowsHitTesting(false)` + `accessibilityHidden(true)`，不参与命中与朗读。
//
//  响应：
//  - 「降低透明度」→ 纹理整体关闭（避免干扰可读性）；
//  - 降低动态效果：纹理静态，无动画，无需额外处理；
//  - 深浅色：深色 `#050505`/`#0D0D0D`，浅色 `#F4F4F2`/`#ECECE9`，对比度均压到 ≈2–3%；
//  - 全局开关：`KnockPreferences.textureEnabled`（`knock_texture_enabled`）或环境值
//    `knockTextureEnabled` 关闭。
//

import SwiftUI
import UIKit

// MARK: - 纹理种类

enum KnockTextureKind: String, CaseIterable, Identifiable {
    /// 页面底斜切迷彩（低对比块面）。
    case camo
    /// 面板/行内极淡 2pt 棋盘格。
    case checker

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .camo: return "斜切迷彩"
        case .checker: return "极淡棋盘格"
        }
    }
}

// MARK: - 纹理渲染器（静态生成 + 尺寸缓存）

/// 纹理位图生成器：**只跑一次**，结果进 `NSCache`。
@MainActor
enum KnockTextureRenderer {
    private static let cache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 8
        return cache
    }()

    /// 取纹理位图；关闭/尺寸非法返回 nil（调用方退回纯色底）。
    static func image(kind: KnockTextureKind, size: CGSize, dark: Bool) -> UIImage? {
        switch kind {
        case .camo:
            return camoImage(size: size, dark: dark)
        case .checker:
            return checkerTile(dark: dark)
        }
    }

    /// 斜切迷彩：整片底 + 旋转 ≈30° 的块面（仿参考图低对比切角迷彩）。
    static func camoImage(size: CGSize, dark: Bool) -> UIImage? {
        let width = Int(size.width.rounded(.up))
        let height = Int(size.height.rounded(.up))
        guard width > 8, height > 8 else { return nil }

        let key = "camo|\(width)x\(height)|\(dark ? "d" : "l")" as NSString
        if let cached = cache.object(forKey: key) { return cached }

        // 1x 采样：纹理对比度 ≤3%，2x/3x 无视觉收益，只增加内存与生成时间。
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format)

        let base = dark ? UIColor(hexValue: 0x050505) : UIColor(hexValue: 0xF4F4F2)
        let block = dark ? UIColor(hexValue: 0x0D0D0D) : UIColor(hexValue: 0xECECE9)
        let blockAlt = dark ? UIColor(hexValue: 0x111111) : UIColor(hexValue: 0xE9E9E5)
        // 深浅各自固定 seed：同一尺寸/主题下纹理稳定，截图可复现。
        var rng = KnockSeededGenerator(seed: dark ? 0x5EED_F00D_1234_5678 : 0xC0DE_FACE_9ABC_DEF0)

        let image = renderer.image { context in
            let cg = context.cgContext
            base.setFill()
            cg.fill(CGRect(x: 0, y: 0, width: width, height: height))

            // 整体旋转 -30°（斜切）：在扩展画布上画块，旋转后仍铺满。
            cg.saveGState()
            cg.translateBy(x: CGFloat(width) / 2, y: CGFloat(height) / 2)
            cg.rotate(by: -30 * .pi / 180)
            cg.translateBy(x: -CGFloat(width) / 2, y: -CGFloat(height) / 2)

            let extended = CGRect(
                x: -CGFloat(width) * 0.6,
                y: -CGFloat(height) * 0.6,
                width: CGFloat(width) * 2.2,
                height: CGFloat(height) * 2.2
            )
            let blockCount = max(18, min(48, width * height / 9000))
            // 小尺寸（如画廊纹理样张）也要保证区间合法，避免 ClosedRange 下界 > 上界崩溃。
            let maxBlockWidth = max(60, CGFloat(width) * 0.55)
            let maxBlockHeight = max(22, CGFloat(height) * 0.16)
            for index in 0..<blockCount {
                let blockWidth = CGFloat.random(in: 44...maxBlockWidth, using: &rng)
                let blockHeight = CGFloat.random(in: 16...maxBlockHeight, using: &rng)
                let x = CGFloat.random(in: extended.minX...extended.maxX, using: &rng)
                let y = CGFloat.random(in: extended.minY...extended.maxY, using: &rng)
                let radius = CGFloat.random(in: 6...24, using: &rng)
                // 一个块面 = 两个重叠圆角矩形，形成切角迷彩的「碎块」轮廓。
                let rect = CGRect(x: x, y: y, width: blockWidth, height: blockHeight)
                (index % 3 == 0 ? blockAlt : block).setFill()
                UIBezierPath(roundedRect: rect, cornerRadius: radius).fill()

                let wingWidth = blockWidth * CGFloat.random(in: 0.25...0.6, using: &rng)
                let wingRect = CGRect(
                    x: rect.maxX - wingWidth * 0.5,
                    y: rect.minY + blockHeight * CGFloat.random(in: -0.6...0.4, using: &rng),
                    width: wingWidth,
                    height: blockHeight * CGFloat.random(in: 0.5...1.2, using: &rng)
                )
                block.setFill()
                UIBezierPath(roundedRect: wingRect, cornerRadius: radius * 0.8).fill()
            }
            cg.restoreGState()

            // 极少量更暗的斜向压边，增加块面层次（仍受 ≤3% 对比度约束）。
            cg.saveGState()
            cg.translateBy(x: CGFloat(width) / 2, y: CGFloat(height) / 2)
            cg.rotate(by: -30 * .pi / 180)
            (dark ? UIColor(white: 0, alpha: 0.10) : UIColor(white: 0, alpha: 0.015)).setFill()
            for _ in 0..<4 {
                let bandHeight = CGFloat.random(in: 3...9, using: &rng)
                let y = CGFloat.random(in: -CGFloat(height) / 2...CGFloat(height) / 2, using: &rng)
                cg.fill(CGRect(x: -CGFloat(width), y: y, width: CGFloat(width) * 2, height: bandHeight))
            }
            cg.restoreGState()
        }

        cache.setObject(image, forKey: key)
        return image
    }

    /// 2pt 棋盘格瓦片（4×4pt），用 `ImagePaint` 平铺，内存常量级。
    static func checkerTile(dark: Bool) -> UIImage? {
        let key = "checker|\(dark ? "d" : "l")" as NSString
        if let cached = cache.object(forKey: key) { return cached }

        let tileSide: CGFloat = 4
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: tileSide, height: tileSide), format: format)

        // 深色：白墨 2%；浅色：黑墨 1.5%（均远低于 3% 对比度上限）。
        let ink = dark ? UIColor(white: 1, alpha: 0.020) : UIColor(white: 0, alpha: 0.015)
        let image = renderer.image { context in
            ink.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 2, height: 2))
            context.fill(CGRect(x: 2, y: 2, width: 2, height: 2))
        }
        cache.setObject(image, forKey: key)
        return image
    }

    /// 清空纹理缓存（外观切换自检/低内存时可选调用）。
    static func clearCache() {
        cache.removeAllObjects()
    }
}

// MARK: - 纹理视图

/// 程序化纹理层（不自带底色，由调用方决定铺在什么底上）。
/// - `.camo`：按自身尺寸生成斜切迷彩位图（静态缓存）；
/// - `.checker`：`ImagePaint` 平铺 2pt 棋盘格。
struct KnockTexture: View {
    var kind: KnockTextureKind = .camo
    var intensity: Double = 1

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.knockTextureEnabled) private var textureEnabled
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    private var isVisible: Bool {
        textureEnabled && !reduceTransparency && intensity > 0
    }

    private var isDark: Bool { colorScheme == .dark }

    var body: some View {
        Group {
            switch kind {
            case .camo:
                camoLayer
            case .checker:
                checkerLayer
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private var camoLayer: some View {
        GeometryReader { proxy in
            if isVisible, let image = KnockTextureRenderer.image(kind: .camo, size: proxy.size, dark: isDark) {
                Image(uiImage: image)
                    .resizable()
                    .frame(width: proxy.size.width, height: proxy.size.height)
                    .opacity(intensity)
            } else {
                // 关闭纹理：保持透明（调用方纯色底仍可见）。
                Color.clear
            }
        }
    }

    private var checkerLayer: some View {
        Group {
            if isVisible, let tile = KnockTextureRenderer.image(kind: .checker, size: .zero, dark: isDark) {
                Rectangle()
                    .fill(ImagePaint(image: Image(uiImage: tile), scale: 1))
                    .opacity(intensity)
            } else {
                Color.clear
            }
        }
    }
}

/// 页面底：纯色 canvas + 斜切迷彩（替换旧的渐变/光斑/立绘模糊，API 与旧 AmbientBackground 对齐）。
struct KnockCamoBackground: View {
    /// 关闭纹理时仍保留纯色底（默认 true）。
    var showsCanvas: Bool = true
    var intensity: Double = 1

    var body: some View {
        ZStack {
            if showsCanvas {
                KnockTokens.canvas
            }
            KnockTexture(kind: .camo, intensity: intensity)
        }
        .ignoresSafeArea()
    }
}

/// 面板/行内极淡棋盘格覆盖层（圆角可选；默认跟随面板圆角裁切）。
struct KnockCheckerOverlay: View {
    var cornerRadius: CGFloat?

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.knockTextureEnabled) private var textureEnabled
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    private var isVisible: Bool { textureEnabled && !reduceTransparency }
    private var isDark: Bool { colorScheme == .dark }

    var body: some View {
        Group {
            if isVisible,
               let tile = KnockTextureRenderer.image(kind: .checker, size: .zero, dark: isDark) {
                let paint = ImagePaint(image: Image(uiImage: tile), scale: 1)
                if let cornerRadius {
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous).fill(paint)
                } else {
                    Rectangle().fill(paint)
                }
            } else {
                Color.clear
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

// MARK: - 便捷修饰符

extension View {
    /// 页面底铺「纯色 + 斜切迷彩」并铺满安全区。
    func knockCamoBackground(intensity: Double = 1) -> some View {
        background(KnockCamoBackground(intensity: intensity))
    }

    /// 面板/行内叠极淡棋盘格（放在底色之上、描边之下）。
    func knockCheckerTexture(cornerRadius: CGFloat? = nil) -> some View {
        overlay(KnockCheckerOverlay(cornerRadius: cornerRadius))
    }
}

// MARK: - 确定性随机数（纹理可复现）

/// SplitMix64：同一 seed 生成同一张迷彩，保证截图验收可复现。
struct KnockSeededGenerator: RandomNumberGenerator {
    private var state: UInt64

    init(seed: UInt64) {
        state = seed == 0 ? 0x9E37_79B9_7F4A_7C15 : seed
    }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}