//
//  AmbientBackground.swift
//  「敲敲」环境背景：纯色底 + 程序化斜切迷彩纹理（原渐变 / 光斑 / 暗角 / 立绘模糊全部删除）。
//
//  背景：T33 时期本组件是「渐变 + 光斑 + 可选立绘模糊 + 暗角」的三层环境背景；
//  敲敲改版（计划书 1.2 / 2.2 / 第五章）要求：
//  - **禁止毛玻璃 / 模糊 / 渐变光斑 / 半透明白**，页面底 = 纯色 `#000000`（浅色 `#F4F4F2`）
//    + 低对比斜切迷彩纹理；
//  - 纹理为静态生成 + 尺寸缓存（见 `KnockTexture`），不参与每帧重算；
//  - 「降低透明度」→ 纹理关闭，只留纯色底（可读性优先）。
//
//  API 兼容（调用面零改动）：
//  - `parallax`：旧滚动视差（背景已静态，参数保留并忽略，不做任何动画）；
//  - `showsStandee`：旧立绘模糊层开关（立绘资产在敲敲体系不再使用，参数保留并忽略）。
//
//  可访问性：降低透明度 → 纹理关闭；减弱动态效果 → 本就静态无动画；深浅色自动跟随系统。
//

import SwiftUI

struct AmbientBackground: View {
    /// 旧滚动视差偏移（敲敲背景静态化，**保留参数仅兼容调用面**）。
    var parallax: CGFloat = 0
    /// 旧立绘模糊层开关（敲敲体系不使用立绘，**保留参数仅兼容调用面**）。
    var showsStandee: Bool = true

    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.knockTextureEnabled) private var textureEnabled

    /// 降低透明度或全局关闭纹理时只保留纯色底。
    private var textureIntensity: Double {
        (reduceTransparency || !textureEnabled) ? 0 : 1
    }

    var body: some View {
        KnockCamoBackground(intensity: textureIntensity)
    }
}