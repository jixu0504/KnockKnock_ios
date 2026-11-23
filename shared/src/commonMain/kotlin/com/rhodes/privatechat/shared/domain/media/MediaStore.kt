package com.rhodes.privatechat.shared.domain.media

import kotlin.concurrent.Volatile

/**
 * 平台媒体文件落盘接口（规格卡 message-id-media-spec.md 抽取落点「MediaStore.kt（平台文件落盘接口）」）。
 *
 * 所有相对路径以 media 根（iOS = Application Support/media，T9 契约 11；Android 迁移后 = filesDir）为基准。
 * 生产实现：iosMain `com.rhodes.privatechat.shared.files.MediaStoreIos`（复用 T9 MediaDirectory 路径与幂等创建）。
 * 测试实现：commonTest InMemoryMediaStore（金样矩阵）+ androidUnitTest/iosTest 各自临时目录实现（目录清理测试）。
 *
 * 设计说明：由于 KMP expect/actual 要求同名包，而本波 iosMain 写域限定在 shared/files 包，
 * 生产注入经 [MediaStoreProvider] 完成（平台入口注册，T1/T15 阶段接线）；测试一律经构造参数传入，不用 provider。
 */
interface MediaStore {

    /**
     * 幂等创建 media 根 + 全部媒体子目录（images/chat_images/voice/newspapers）。
     * 返回实际存在的目录相对路径列表。oracle：ImageUtils.kt:15、LocalAudioController.kt:44 的 mkdirs 语义。
     */
    fun ensureDirectories(): List<String>

    /**
     * 从源引用读取字节（`file://` 绝对路径、裸绝对路径、平台 `content://`）。不可读返回 null。
     * 远程引用（http/https/data:）不经此方法（ingest 先透传）。
     */
    fun readSourceBytes(ref: String): ByteArray?

    /** 写入 media 根下的相对路径（父目录幂等创建）。失败返回 false。 */
    fun writeBytes(relativePath: String, bytes: ByteArray): Boolean

    /** 读取 media 根下相对路径的字节；文件缺失/不可读返回 null。 */
    fun readBytes(relativePath: String): ByteArray?

    /** media 根下相对路径是否存在（文件或目录）。 */
    fun exists(relativePath: String): Boolean

    /** 删除 media 根下相对路径（文件或目录，目录递归）。目标不存在视作成功。 */
    fun delete(relativePath: String): Boolean

    /** 相对路径 → 绝对 file:// URL（不校验存在性）。 */
    fun fileUrl(relativePath: String): String

    /**
     * 删除绝对路径文件，仅限 media 根内（兼容 Android `file://` 历史引用，镜像 LocalAudioController.kt:141
     * `File(path.removePrefix("file://")).delete()`；iOS 收窄为根内删除，越界返回 false）。
     */
    fun deleteAbsolutePath(path: String): Boolean
}

/**
 * 平台 MediaStore 注入点。平台入口（iOS app 启动/T1/T15 接线）调用 [register]；
 * [require] 供 [MediaRepository] 默认构造使用；未注册即使用视为平台接线错误（fail fast）。
 */
object MediaStoreProvider {

    @Volatile
    private var registered: MediaStore? = null

    fun register(store: MediaStore) {
        registered = store
    }

    fun require(): MediaStore =
        registered ?: error("MediaStore 未注册：平台入口须先调用 MediaStoreProvider.register(...)（测试请走 MediaRepository 构造参数）")
}
