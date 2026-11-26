//
//  RoleAvatar.swift
//  「敲敲」头像体系（P2 视觉口径，文件保留原 API，调用面零改动）。
//
//  设计依据：docs/敲敲UI改版计划书-v1.1.md
//  - 决策 4 / 第六章：**不复用原 147 个内置头像**，统一用「黑底 + 1.5pt 浅蓝圈 + 白色 heavy 首字」
//    占位，后续替换绝区零人物；旧 147 张 avatar_N.png 资源已删除（Release 体积 -6.9MB）；
//  - `knock:<id>` 前缀 → `Resources/AvatarsKnock/knock_<id>.png`，用户丢素材即生效；
//    无素材时回退首字占位（绝不 crash、绝不空白）；
//  - 上传头像（`media/<uuid>.<ext>`）保留并套 1.5pt 浅蓝圈 `#8CC4FF`。
//
//  解析兼容（自检零破坏）：
//  - 旧内置数字 "1".."147" 仍解析为 `builtin:N`（旧数据不 crash、不改写 DB），
//    但资源已删除 → 渲染一律走首字占位，诊断描述固定 `builtin-missing:N`；
//  - `RoleAvatarResolver.image(for:)` / `description(for:)` / `RoleAvatarProbe` 接口全部保留；
//  - `knock:` 非法值（空 / 含路径字符）→ `.placeholder` 首字占位，不 crash。
//
//  可访问性：
//  - 占位首字用高对比白字 + 蓝圈（对比度 > 10:1）；
//  - 纯装饰头像 `accessibilityHidden(true)`，人名由周边行文本朗读；
//  - 头像尺寸由调用方给定（36pt 聊天 / 44pt 行 / 92pt 详情），不随 Dynamic Type 放大，避免挤压布局。
//

import SwiftUI
import UIKit

// MARK: - 敲敲头像样式（黑底 + 1.5pt 蓝圈 + 白色 heavy 首字）

/// `KnockAvatarStyle`：计划书第六章头像占位口径。
/// - 黑底（深浅主题均黑，保证蓝圈与白字对比）；
/// - 1.5pt 浅蓝圈 `#8CC4FF`；
/// - 白色 heavy 首字（PingFang 无 Black，用 heavy 近似）。
struct KnockAvatarStyle {
    var background: Color
    var ringColor: Color
    var ringWidth: CGFloat
    var initialColor: Color

    init(
        background: Color = Color(hex: 0x0B0B0C),
        ringColor: Color = KnockTokens.electricRing,
        ringWidth: CGFloat = KnockTokens.strokeWidth,
        initialColor: Color = .white
    ) {
        self.background = background
        self.ringColor = ringColor
        self.ringWidth = ringWidth
        self.initialColor = initialColor
    }

    /// 标准占位样式（黑底 + 浅蓝圈 + 白首字）。
    static let standard = KnockAvatarStyle()
}

// MARK: - 头像值域解析（视图 / 自检 / 诊断共用）

/// 头像值域来源：
/// - `knock(id)`：`knock:<id>` 新素材（`Resources/AvatarsKnock/knock_<id>.png`）；
/// - `builtin(N)`：旧内置数字头像 "1".."147"（仅解析兼容；资源已删除，渲染走首字占位）；
/// - `media(id)`：T49 上传头像 `media/<uuid>.<ext>`（本机媒体目录文件）；
/// - `placeholder`：空 / 远程 URL / 其它非法值 → 首字占位。
enum RoleAvatarSource: Equatable {
    case knock(String)
    case builtin(Int)
    case media(String)
    case placeholder

    var isKnock: Bool {
        if case .knock = self { return true }
        return false
    }

    var isBuiltin: Bool {
        if case .builtin = self { return true }
        return false
    }
}

/// 头像解析器（纯函数 + 两级缓存）：
/// - `mediaPath` 缓存媒体 ID → 本机路径（列表滚动时避免重复 resolve 的跨桥开销）；
/// - `image` 用 `NSCache` 缓存已解码位图（内置头像走 UIKit 自身缓存，上传头像走本缓存）。
@MainActor
enum RoleAvatarResolver {
    /// 旧内置头像值域 1...147（仅解析兼容：资源已删除，渲染一律首字占位）。
    static let builtinRange = 1...147

    /// 媒体 ID 前缀（shared `MediaRepository.MEDIA_ID_PREFIX`）。
    static let mediaPrefix = "media/"

    /// 敲敲头像前缀：`knock:<id>`。
    static let knockPrefix = "knock:"

    // MARK: 值域判定

    /// 内置头像编号（1...147），非法/非数字返回 nil。
    static func builtinNumber(_ avatarId: String) -> Int? {
        let trimmed = avatarId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let number = Int(trimmed), builtinRange.contains(number) else { return nil }
        return number
    }

    /// 上传头像媒体 ID：`media/<uuid>.<ext>`（去掉首尾空白后判定；不接受含空格/换行的脏值）。
    static func mediaId(_ avatarId: String) -> String? {
        let trimmed = avatarId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix(mediaPrefix), trimmed.count > mediaPrefix.count,
              !trimmed.contains(" "), !trimmed.contains("\n") else { return nil }
        return trimmed
    }

    /// 敲敲头像 ID：`knock:<id>`（id 仅允许字母数字 / 连字符 / 下划线，避免路径注入与非法资源名）。
    static func knockId(_ avatarId: String) -> String? {
        let trimmed = avatarId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix(knockPrefix) else { return nil }
        let raw = trimmed.dropFirst(knockPrefix.count).trimmingCharacters(in: .whitespaces)
        guard !raw.isEmpty else { return nil }
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
        guard raw.unicodeScalars.allSatisfy({ allowed.contains($0) }) else { return nil }
        return raw
    }

    /// Bundle 资源名：`knock_<id>`（无 `.png` 后缀，交给 UIImage 查找）。
    static func knockAssetName(_ avatarId: String) -> String? {
        knockId(avatarId).map { "knock_\($0)" }
    }

    static func source(for avatarId: String) -> RoleAvatarSource {
        if knockId(avatarId) != nil { return .knock(knockId(avatarId) ?? "") }
        if let number = builtinNumber(avatarId) { return .builtin(number) }
        if let id = mediaId(avatarId) { return .media(id) }
        return .placeholder
    }

    // MARK: 媒体路径 / 位图解析

    private static let lock = NSLock()
    private static var pathCache: [String: String] = [:]
    private static let imageCache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 64
        return cache
    }()

    /// 媒体 ID → 本机绝对路径（文件不存在/非媒体 ID/桥异常返回 ""）。命中缓存不跨桥。
    static func mediaPath(for avatarId: String) -> String {
        guard let id = mediaId(avatarId) else { return "" }
        lock.lock()
        let cached = pathCache[id]
        lock.unlock()
        if let cached { return cached }

        let resolved = AppGraphClient.shared.operatorMediaPath(mediaId: id)
        let path: String
        if resolved.isEmpty {
            path = ""
        } else if resolved.hasPrefix("file://") {
            path = URL(string: resolved)?.path ?? ""
        } else if resolved.hasPrefix("http://") || resolved.hasPrefix("https://") {
            // 上传头像只应落本机媒体目录；远程 URL 不在此渲染（回退首字占位）。
            path = ""
        } else {
            path = resolved
        }
        // 只缓存成功结果：文件暂未落盘（如刚入库的竞态）时下次仍会重新解析。
        if !path.isEmpty, FileManager.default.fileExists(atPath: path) {
            lock.lock()
            pathCache[id] = path
            lock.unlock()
            return path
        }
        return ""
    }

    /// 头像位图：敲敲素材 / 上传媒体文件；
    /// 不可渲染返回 nil（调用方回退首字占位）。
    /// 旧内置数字（builtin）资源已删除 → 恒返回 nil（解析兼容保留，渲染走首字占位）。
    static func image(for avatarId: String) -> UIImage? {
        switch source(for: avatarId) {
        case .knock:
            return knockImage(for: avatarId)
        case .builtin:
            return nil
        case .media(let id):
            let key = id as NSString
            if let cached = imageCache.object(forKey: key) { return cached }
            let path = mediaPath(for: avatarId)
            guard !path.isEmpty, let image = UIImage(contentsOfFile: path) else { return nil }
            imageCache.setObject(image, forKey: key)
            return image
        case .placeholder:
            return nil
        }
    }

    /// 敲敲头像素材：`knock_<id>.png`；兼容 Bundle 根与 `AvatarsKnock/` 子目录两种打包方式。
    /// 素材缺失返回 nil（调用方回退首字占位，绝不 crash）。
    static func knockImage(for avatarId: String) -> UIImage? {
        guard let assetName = knockAssetName(avatarId) else { return nil }
        return UIImage(named: assetName)
            ?? UIImage(named: "AvatarsKnock/\(assetName)")
    }

    /// 诊断用的可读描述（角色列表/详情自检日志、DB 核对用）。
    /// - `knock:<id>` / `knock-missing:<id>` / `builtin-missing:12`（旧内置资源已删除，恒 missing）/
    ///   `media:media/<uuid>.png` / `media-missing:media/<uuid>.png` /
    ///   `placeholder:empty` / `placeholder:<raw>`
    static func description(for avatarId: String) -> String {
        switch source(for: avatarId) {
        case .knock(let id):
            return knockImage(for: avatarId) != nil ? "knock:\(id)" : "knock-missing:\(id)"
        case .builtin(let number):
            return "builtin-missing:\(number)"
        case .media(let id):
            return image(for: avatarId) != nil ? "media:\(id)" : "media-missing:\(id)"
        case .placeholder:
            let trimmed = avatarId.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? "placeholder:empty" : "placeholder:\(trimmed)"
        }
    }
}

// MARK: - 敲敲头像（黑底 + 蓝圈 + 首字）

/// 敲敲占位头像：`knock:<id>` 有素材则显示素材，否则显示「黑底 + 1.5pt 浅蓝圈 + 白色 heavy 首字」。
struct KnockAvatar: View {
    var avatarId: String
    var name: String
    var size: CGFloat
    var style: KnockAvatarStyle

    init(
        avatarId: String = "",
        name: String,
        size: CGFloat = KnockTokens.rowIconSize,
        style: KnockAvatarStyle = .standard
    ) {
        self.avatarId = avatarId
        self.name = name
        self.size = size
        self.style = style
    }

    /// 首字：中文取首个汉字，英文取首字母（空名回退 "?"）。
    private var initial: String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let first = trimmed.first else { return "?" }
        return String(first)
    }

    private var assetImage: UIImage? {
        RoleAvatarResolver.knockImage(for: avatarId)
    }

    var body: some View {
        ZStack {
            Circle().fill(style.background)
            if let assetImage {
                Image(uiImage: assetImage)
                    .resizable()
                    .scaledToFill()
            } else {
                Text(initial)
                    .font(.system(size: max(11, size * 0.40), weight: .semibold))
                    .foregroundStyle(style.initialColor)
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .overlay {
            Circle().strokeBorder(style.ringColor, lineWidth: style.ringWidth)
        }
        .accessibilityLabel(name.isEmpty ? "头像占位" : "\(name)的头像")
        .accessibilityAddTraits(.isImage)
    }
}

// MARK: - 头像 View

/// 统一头像 View（角色列表 / 角色详情 / 群成员 / 新建聊天 / 动态 / 日记 / 会话列表全部复用）：
/// - `knock:` 前缀 → 敲敲素材（缺失回退首字占位）；
/// - `media/` 上传头像 → 位图 + 1.5pt 浅蓝圈；
/// - 旧内置数字 1..147 → 首字占位（资源已删除，仅保留值域解析兼容）；
/// - 空 / 其它 → 首字占位。
struct RoleAvatar: View {
    /// operators.avatarUri 原样值：`knock:<id>` / 旧内置 "1".."147" / `media/<uuid>.<ext>` / 空。
    let avatarId: String
    let name: String
    var size: CGFloat = 52

    var body: some View {
        Group {
            switch RoleAvatarResolver.source(for: avatarId) {
            case .media:
                if let image = RoleAvatarResolver.image(for: avatarId) {
                    imageAvatar(image)
                } else {
                    KnockAvatar(avatarId: avatarId, name: name, size: size)
                }
            case .knock, .builtin, .placeholder:
                KnockAvatar(avatarId: avatarId, name: name, size: size)
            }
        }
        .frame(width: size, height: size)
    }

    /// 上传头像：等比填充 + 1.5pt 浅蓝圈（计划书第六章：上传头像保留并套蓝圈）。
    private func imageAvatar(_ image: UIImage) -> some View {
        Image(uiImage: image)
            .resizable()
            .scaledToFill()
            .frame(width: size, height: size)
            .clipShape(Circle())
            .overlay {
                Circle().strokeBorder(KnockTokens.electricRing, lineWidth: KnockTokens.strokeWidth)
            }
            .accessibilityHidden(true)
    }
}

// MARK: - 诊断入口（自检日志；设置页/角色列表打点用）

/// 头像数据源诊断：证明敲敲素材、旧内置资源、上传媒体文件、占位回退均可判定。
@MainActor
enum RoleAvatarProbe {
    /// 解析结果：`knock:<id>` / `knock-missing:<id>` / `builtin:12` / `builtin-missing:12` /
    /// `media:media/<uuid>.png` / `media-missing:...` / `placeholder:empty` / `placeholder:<raw>`。
    static func description(avatarId: String) -> String {
        RoleAvatarResolver.description(for: avatarId)
    }

    /// Bundle 中可解析的旧内置头像数量（启动自检日志证据；147 张旧资源已删除 → 恒为 0）。
    static func bundledAvatarCount() -> Int {
        RoleAvatarResolver.builtinRange.reduce(into: 0) { count, number in
            if UIImage(named: "avatar_\(number)") != nil { count += 1 }
        }
    }

    /// 敲敲头像素材诊断（`knock:<id>` → 资源名 → 是否命中 Bundle）。
    static func knockProbe(avatarId: String) -> String {
        guard let assetName = RoleAvatarResolver.knockAssetName(avatarId) else {
            return "probe.knock.invalid id=\(avatarId.isEmpty ? "<empty>" : avatarId)"
        }
        let hit = RoleAvatarResolver.knockImage(for: avatarId) != nil
        return "probe.knock id=\(avatarId) asset=\(assetName) hit=\(hit ? "yes" : "no") fallback=initial"
    }

    /// 上传头像渲染链路诊断（T52 自检）：媒体 ID → 本机路径 → 文件大小 → 位图尺寸。
    /// 返回一行可落日志的证据文本（不可渲染时说明原因）。
    static func mediaRenderProbe(avatarId: String) -> String {
        guard let id = RoleAvatarResolver.mediaId(avatarId) else {
            return "probe.media.invalid id=\(avatarId.isEmpty ? "<empty>" : avatarId)"
        }
        let path = RoleAvatarResolver.mediaPath(for: avatarId)
        if path.isEmpty {
            return "probe.media id=\(id) path=<missing>"
        }
        let size = ((try? FileManager.default.attributesOfItem(atPath: path))?[.size] as? Int) ?? -1
        let image = RoleAvatarResolver.image(for: avatarId)
        let dimensions = image.map { "\(Int($0.size.width))x\(Int($0.size.height))" } ?? "<nil>"
        return "probe.media id=\(id) path=\(path) bytes=\(size) image=\(dimensions) suffix=\(path.split(separator: ".").last ?? "-")"
    }
}