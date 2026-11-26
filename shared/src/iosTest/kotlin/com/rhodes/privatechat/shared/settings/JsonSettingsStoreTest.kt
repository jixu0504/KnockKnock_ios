package com.rhodes.privatechat.shared.settings

import com.rhodes.privatechat.shared.files.FileIo
import com.rhodes.privatechat.shared.testutil.withTempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.int
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 设置 JSON 原子写：往返一致、坏 JSON 回退、无半文件/无 tmp 残留。 */
class JsonSettingsStoreTest {

    @Test
    fun writeReadRoundTrip() = withTempDir("json-settings") { dir ->
        val store = JsonSettingsStore("$dir/settings.json")
        val data = buildJsonObject {
            put("theme", "dark")
            put("lastSessionId", 42)
            putJsonObject("nested") {
                put("a", true)
                put("b", "中文")
            }
            putJsonArray("tags") {
                add("x")
                add("y")
            }
        }
        assertTrue(store.write(data), "写入应成功")
        assertTrue(store.exists())
        assertEquals(data, store.read(), "读回应完全一致（含嵌套与 Unicode）")
    }

    @Test
    fun missingFileFallsBackToEmptyObject() = withTempDir("json-settings") { dir ->
        val store = JsonSettingsStore("$dir/nope.json")
        assertEquals(JsonObject(emptyMap()), store.read(), "缺失文件应回退空对象")
    }

    @Test
    fun corruptJsonFallsBackToEmptyObjectAndDoesNotCrash() = withTempDir("json-settings") { dir ->
        val path = "$dir/settings.json"
        assertTrue(FileIo.writeBytes(path, "not-json{{".encodeToByteArray()))
        val store = JsonSettingsStore(path)
        assertEquals(JsonObject(emptyMap()), store.read(), "坏 JSON 应回退空对象且不抛异常")
        // 坏文件之后可被正常原子写修复
        val fixed = buildJsonObject { put("recovered", true) }
        assertTrue(store.write(fixed))
        assertEquals(fixed, store.read())
    }

    @Test
    fun truncatedJsonFallsBackToEmptyObject() = withTempDir("json-settings") { dir ->
        val path = "$dir/settings.json"
        assertTrue(FileIo.writeBytes(path, "{\"k\":\"v\"".encodeToByteArray()))
        val store = JsonSettingsStore(path)
        assertEquals(JsonObject(emptyMap()), store.read(), "截断 JSON 应回退空对象")
    }

    @Test
    fun atomicWriteLeavesNoHalfFileAndNoTmpResidue() = withTempDir("json-settings") { dir ->
        val path = "$dir/settings.json"
        val store = JsonSettingsStore(path)
        for (i in 1..50) {
            val payload = "x".repeat(i * 100)
            val data = buildJsonObject { put("i", i); put("payload", payload) }
            assertTrue(store.write(data), "第 $i 次写失败")
            val readBack = store.read()
            assertEquals(i, readBack["i"]!!.jsonPrimitive.int, "第 $i 次读到不完整/错误数据")
            // 无 tmp 残留（原子替换后临时文件必须消失）
            assertFalse(FileIo.exists("$path.tmp"), "第 $i 次写后残留 tmp 文件")
            // 磁盘原文与本次写入完全一致（不存在半文件）
            val text = store.readText()!!
            assertTrue(text.contains("\"payload\":\"$payload\""), "第 $i 次磁盘原文不完整: $text")
        }
    }

    @Test
    fun staleTmpFileDoesNotCorruptReads() = withTempDir("json-settings") { dir ->
        val path = "$dir/settings.json"
        // 模拟上次写入崩溃：留下 tmp 但正式文件是旧完整版本
        val old = buildJsonObject { put("version", 1) }
        val store = JsonSettingsStore(path)
        assertTrue(store.write(old))
        assertTrue(FileIo.writeBytes("$path.tmp", "{\"half\":".encodeToByteArray()))
        // 读只认正式文件；新写会覆盖 tmp 并原子替换
        assertEquals(old, store.read(), "残留 tmp 不影响正式文件读取")
        val fresh = buildJsonObject { put("version", 2) }
        assertTrue(store.write(fresh))
        assertEquals(fresh, store.read())
        assertFalse(FileIo.exists("$path.tmp"))
    }
}
