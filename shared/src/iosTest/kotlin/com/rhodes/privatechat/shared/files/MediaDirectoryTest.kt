package com.rhodes.privatechat.shared.files

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.Foundation.NSUUID

/** 媒体目录（Application Support/media/）初始化、相对 ID 规则、文件读写与清理。 */
class MediaDirectoryTest {

    @Test
    fun createsMediaStructureUnderApplicationSupport() {
        val support = MediaDirectory.applicationSupportDirectory()
        println("IOS-DATA application-support-dir: $support")
        assertTrue(support.isNotBlank())
        assertTrue(support.startsWith("/"), "Application Support 应为绝对路径")

        val paths = MediaDirectory.ensureMediaStructure()
        assertTrue(paths.isNotEmpty())
        val root = MediaDirectory.mediaRootPath()
        assertTrue(root.startsWith(support), "media 根应在 Application Support 下")
        assertTrue(FileIo.exists(root), "media 根应已创建")
        for (sub in listOf("images", "voice", "chat_images", "newspapers")) {
            assertTrue(FileIo.exists("$root/$sub"), "缺少子目录 $sub")
        }
        // 幂等：重复 ensure 不报错
        assertTrue(MediaDirectory.ensureDirectory(root))
    }

    @Test
    fun relativeIdResolutionRules() {
        // 非法输入一律拒绝
        assertNull(MediaDirectory.resolve(""))
        assertNull(MediaDirectory.resolve("   "))
        assertNull(MediaDirectory.resolve("../etc/passwd"))
        assertNull(MediaDirectory.resolve("a/../../b"))
        assertNull(MediaDirectory.resolve("/abs/path"))
        assertNull(MediaDirectory.resolve("file:///tmp/x"))
        assertNull(MediaDirectory.resolve("http://evil/x"))
        // 合法 ID → 子目录映射（与 paths.md §4 目录映射一致）
        assertEquals("images/avatar_abc123", MediaDirectory.resolve("avatar:abc123"))
        assertEquals("voice/voice_1", MediaDirectory.resolve("voice:1"))
        assertEquals("images/bg_op7", MediaDirectory.resolve("bg:op7"))
        assertEquals("media/unknown_id", MediaDirectory.resolve("unknown_id"))
        // 绝对路径解析同样拒绝非法 ID
        assertNull(MediaDirectory.absolutePathFor("../../secret"))
        assertNull(MediaDirectory.absolutePathFor("file:///etc/passwd"))
    }

    @Test
    fun writesAndReadsMediaFileByRelativeId() {
        MediaDirectory.ensureMediaStructure()
        val relId = "avatar:t9-${NSUUID().UUIDString}"
        val abs = MediaDirectory.absolutePathFor(relId)
        assertNotNull(abs, "合法 ID 应解析出绝对路径")
        assertTrue(abs.startsWith(MediaDirectory.mediaRootPath() + "/images/"))
        val payload = "media-bytes-测试-📁".encodeToByteArray()
        assertTrue(FileIo.writeBytes(abs, payload), "媒体文件写入失败")
        assertContentEquals(payload, FileIo.readBytes(abs)!!, "媒体文件读回一致")
        assertTrue(FileIo.delete(abs), "清理失败")
        assertFalse(FileIo.exists(abs))
    }

    @Test
    fun fileIoHandlesMissingFiles() {
        val missing = "/nonexistent/t9-nope-${NSUUID().UUIDString}"
        assertNull(FileIo.readBytes(missing), "缺失文件读应返回 null")
        assertFalse(FileIo.exists(missing))
        assertTrue(FileIo.delete(missing), "缺失文件删应幂等成功")
    }

    @Test
    fun wipeAndRecreateMediaRoot() {
        MediaDirectory.ensureMediaStructure()
        assertTrue(MediaDirectory.wipeMediaRoot(), "清空失败")
        assertFalse(FileIo.exists(MediaDirectory.mediaRootPath()))
        MediaDirectory.ensureMediaStructure()
        assertTrue(FileIo.exists(MediaDirectory.mediaRootPath()), "重建失败")
    }
}
