@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rhodes.privatechat.shared.settings

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * 设置 JSON 文件的最小原子写实现（iOS 侧，Phase 2 正式版前验证）。
 *
 * 不照搬 Android EncryptedSharedPreferences：iOS 偏好小键值走 NSUserDefaults
 * （见 SettingsFactory.ios.kt），需要「整块 JSON 文档 + 原子替换」语义的数据
 * （如备份元数据、调试快照）走本类。敏感数据不落此文件，走 KeychainStore。
 *
 * 原子写策略：序列化 → 写 `<file>.tmp`（NSData.writeToFile atomically=true）→
 * NSFileManager.replaceItemAtURL 原子替换（rename 语义）；替换失败时退化
 * remove+move。读者任何时候只能看到完整旧文件或完整新文件，不会读到半文件。
 * 读失败（文件缺失 / 坏 JSON / 非对象）一律回退空对象，绝不抛异常。
 *
 * 注意：K/N 2.3 新 interop 下 NSFileManager 的方法为类成员（无需扩展导入），
 * 而 NSData/NSString 工厂仍为扩展（需 import platform.Foundation.create 等）。
 */
class JsonSettingsStore(
    private val filePath: String,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    },
) {

    /** 读当前 JSON。缺失/损坏/非对象 → 空 JsonObject。 */
    fun read(): JsonObject {
        val text = readText() ?: return JsonObject(emptyMap())
        return try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            JsonObject(emptyMap())
        } catch (e: IllegalArgumentException) {
            JsonObject(emptyMap())
        }
    }

    /** 原子写整份 JSON。返回是否成功。 */
    fun write(data: JsonObject): Boolean {
        // JsonElementSerializer 在 kotlinx-serialization 1.7.3 为 internal；
        // JsonObject.toString() 输出规范紧凑 JSON，与 Json.parseToJsonElement 完全互逆。
        val text = data.toString()
        val nsData = NSString.create(string = text)?.dataUsingEncoding(NSUTF8StringEncoding)
            ?: return false
        val tmpPath = "$filePath.tmp"
        if (!nsData.writeToFile(tmpPath, atomically = true)) return false
        return replaceAtomically(tmpPath)
    }

    /** 直接读原文（供诊断/迁移用）；文件缺失或读失败返回 null。 */
    fun readText(): String? {
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(filePath)) return null
        val data = NSData.dataWithContentsOfFile(filePath) ?: return null
        val ns = NSString.create(data = data, encoding = NSUTF8StringEncoding) ?: return null
        return ns.toString()
    }

    fun exists(): Boolean = NSFileManager.defaultManager.fileExistsAtPath(filePath)

    // ---- 原子替换 ----

    private fun replaceAtomically(tmpPath: String): Boolean {
        val fm = NSFileManager.defaultManager
        val target = NSURL.fileURLWithPath(filePath)
        val source = NSURL.fileURLWithPath(tmpPath)
        return memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            var ok = fm.replaceItemAtURL(
                originalItemURL = target,
                withItemAtURL = source,
                backupItemName = null,
                options = 1uL, // NSFileManagerItemReplacementUsingNewMetadataOnly
                resultingItemURL = null,
                error = error.ptr,
            )
            if (!ok) {
                // 退化路径：remove + move（move 不覆盖已存在文件，故先删）
                fm.removeItemAtPath(filePath, null)
                ok = fm.moveItemAtPath(tmpPath, filePath, error.ptr)
            }
            if (!ok) fm.removeItemAtPath(tmpPath, null)
            ok
        }
    }
}
