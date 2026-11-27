//
//  EmojiImage.swift
//  表情图片渲染：优先按 bundle 文件路径加载 PNG（表情是随包资源，不走媒体库/网络）。
//

import SwiftUI
import UIKit

struct EmojiImage: View {
    let assetName: String
    var size: CGFloat = 56

    var body: some View {
        Group {
            if let image = Self.load(assetName) {
                Image(uiImage: image)
                    .resizable()
                    .interpolation(.none)
                    .scaledToFit()
            } else {
                // 资源缺失时的可诊断占位（不应出现在正式包里）
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(KnockTokens.row)
                    .overlay {
                        Image(systemName: "questionmark")
                            .font(.system(size: 16, weight: .heavy))
                            .foregroundStyle(KnockTokens.textTertiary)
                    }
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }

    static func load(_ name: String) -> UIImage? {
        guard !name.isEmpty else { return nil }
        if let url = Bundle.main.url(forResource: name, withExtension: "png"),
           let image = UIImage(contentsOfFile: url.path) {
            return image
        }
        return UIImage(named: name)
    }
}
