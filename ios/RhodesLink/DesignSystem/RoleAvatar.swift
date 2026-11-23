//
//  RoleAvatar.swift
//  角色头像（A2 / T36，T52 扩展上传头像渲染）：
//  - 内置头像 1..147 按数字解析 Bundle 资源（lead T16 裁决：内置头像 = Bundle 资源按数字解析，
//    不落 media 目录、值域保持数字串原样）；
//  - 用户上传头像（T49：MediaRepository `media/<uuid>.<ext>` 相对 ID）→ 经 Swift 桥
//    `AppGraphClient.operatorMediaPath(mediaId:)` 解析成本机绝对路径 → UIImage 渲染；
//    文件缺失/解析失败回退 `AvatarBadge` 首字占位（绝不 crash、绝不空白）；
//  - 所有展示面（角色列表 RolesView / 角色详情 RoleDetailView / 群成员 / 新建聊天 / 动态 /
//    日记）统一走本 View，因此上传头像一处修复全部生效。
//
//  资源打包：`RhodesLink/Resources/Avatars/avatar_<N>.png`（来自 Android
//  app/src/main/res/drawable/avatar_1..147.png，经 PBXFileSystemSynchronizedRootGroup
//  自动进 Copy Bundle Resources，Bundle 根目录按文件名解析）。
//

import SwiftUI
import UIKit

// MARK: - 头像值域解析（视图 / 自检 / 诊断共用）

/// 头像值域来源：
/// - `builtin(N)`：内置数字头像 "1".."147"（Bundle 资源）；
/// - `media(id)`：T49 上传头像 `media/<uuid>.<ext>`（本机媒体目录文件）；
/// - `placeholder`：空 / 远程 URL / 其它非法值 → 首字占位。
enum RoleAvatarSource: Equatable {
    case builtin(Int)
    case media(String)
    case placeholder

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
    /// 内置头像值域 1...147。
    static let builtinRange = 1...147

    /// 媒体 ID 前缀（shared `MediaRepository.MEDIA_ID_PREFIX`）。
    static let mediaPrefix = "media/"

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

    static func source(for avatarId: String) -> RoleAvatarSource {
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

    /// 头像位图：内置 Bundle 资源 / 上传媒体文件；不可渲染返回 nil（调用方回退首字占位）。
    static func image(for avatarId: String) -> UIImage? {
        switch source(for: avatarId) {
        case .builtin(let number):
            return UIImage(named: "avatar_\(number)")
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

    /// 诊断用的可读描述（角色列表/详情自检日志、DB 核对用）。
    /// - `builtin:12` / `builtin-missing:12` / `media:media/<uuid>.png` / `media-missing:media/<uuid>.png` /
    ///   `placeholder:empty` / `placeholder:<raw>`
    static func description(for avatarId: String) -> String {
        switch source(for: avatarId) {
        case .builtin(let number):
            return UIImage(named: "avatar_\(number)") != nil ? "builtin:\(number)" : "builtin-missing:\(number)"
        case .media(let id):
            return image(for: avatarId) != nil ? "media:\(id)" : "media-missing:\(id)"
        case .placeholder:
            let trimmed = avatarId.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? "placeholder:empty" : "placeholder:\(trimmed)"
        }
    }
}

// MARK: - 头像 View

struct RoleAvatar: View {
    /// operators.avatarUri 原样值：内置头像为 "1".."147"；T49 上传头像为 `media/<uuid>.<ext>`；
    /// 其它（空 / 远程 URL）走首字占位。
    let avatarId: String
    let name: String
    var size: CGFloat = 52

    var body: some View {
        Group {
            if let image = RoleAvatarResolver.image(for: avatarId) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: size, height: size)
                    .clipShape(Circle())
                    .overlay(
                        Circle().strokeBorder(GlassTokens.glassStroke, lineWidth: 1)
                    )
                    .accessibilityHidden(true)
            } else {
                AvatarBadge(name: name, size: size)
            }
        }
        .frame(width: size, height: size)
    }
}

// MARK: - 诊断入口（T36/T52 自检日志；设置页/角色列表打点用）

/// 头像数据源诊断：证明 Bundle 资源、上传媒体文件、占位回退均可判定。
@MainActor
enum RoleAvatarProbe {
    /// 解析结果：`builtin:12` / `builtin-missing:12` / `media:media/<uuid>.png` / `media-missing:...` /
    /// `placeholder:empty` / `placeholder:<raw>`。
    static func description(avatarId: String) -> String {
        RoleAvatarResolver.description(for: avatarId)
    }

    /// Bundle 中可解析的内置头像数量（启动自检日志证据）。
    static func bundledAvatarCount() -> Int {
        RoleAvatarResolver.builtinRange.reduce(into: 0) { count, number in
            if UIImage(named: "avatar_\(number)") != nil { count += 1 }
        }
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
