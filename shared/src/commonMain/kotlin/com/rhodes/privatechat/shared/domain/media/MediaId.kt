package com.rhodes.privatechat.shared.domain.media

/**
 * 相对媒体 ID 值对象 + 校验（规格卡 message-id-media-spec.md 契约 12/17/边界「媒体 ID 校验」）。
 *
 * 实现注记：规格卡落点为「值类（value class）」，但 @JvmInline 在 Kotlin/Native 不存在、
 * 无注解值类在 JVM target 是编译错误，expect/actual 值类又需要写 androidMain/iosMain 的
 * domain/media 包（超出本波写域）——故退化为不可变普通类（私有构造 + parse 校验），
 * 语义等价（校验不可绕过、等值语义保留）。后续波如需零开销可再迁 expect/actual 值类。
 *
 * 合法形态（本卡正式值域，risk 5 lead 裁决）：
 *   - `media/<uuid>.<ext>`  运行时新写入文件（UUID 随机、扩展名保真小写）
 *   - `local-image/<sha24>` 备份导入文件（BackupMediaCollector.kt:26 公式）
 *   - 数字字符串 `1..147`   内置头像（非媒体文件 ID，由渲染层分支处理，见 MediaRepository）
 *   - `http(s)://` / `data:` 远程引用（不是媒体 ID，resolve 返回 nil，调用方不得对其调用 resolve）
 *
 * 非法（T9 契约 11 同断言集）：空 / `..` / 绝对路径（`/` 开头）/ `://`（含 `file://`）。
 */
class MediaId private constructor(val value: String) {

    companion object {
        /** 校验通过返回 MediaId，否则 null。 */
        fun parse(raw: String): MediaId? = if (isValid(raw)) MediaId(raw) else null

        fun isValid(raw: String): Boolean {
            if (raw.isBlank()) return false
            if (raw.contains("..")) return false
            if (raw.startsWith("/")) return false
            if (raw.contains("://")) return false
            return true
        }
    }

    override fun equals(other: Any?): Boolean = other is MediaId && other.value == value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value
}
