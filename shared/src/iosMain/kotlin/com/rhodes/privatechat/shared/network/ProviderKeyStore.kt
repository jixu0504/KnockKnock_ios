@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rhodes.privatechat.shared.network

import com.rhodes.privatechat.shared.db.DatabaseLocations
import com.rhodes.privatechat.shared.security.KeychainError
import com.rhodes.privatechat.shared.security.KeychainResult
import com.rhodes.privatechat.shared.security.KeychainStore
import com.rhodes.privatechat.shared.security.toByteArray
import com.rhodes.privatechat.shared.security.toNSStringData
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * T37 API Key 存储：**Keychain 优先**，OS 拒绝时回退到 App 沙盒内的私密文件。
 *
 * 为什么需要回退（实测，2026-11-23）：
 * - 模拟器上用 `CODE_SIGNING_ALLOWED=NO` 构建的 App 是 `adhoc, linker-signed` 且**没有任何 entitlements**
 *   （`codesign -d --entitlements :-` 为空），`SecItemAdd` 返回 `errSecMissingEntitlement(-34018)`；
 * - 正常 Xcode 签名的模拟器/真机 App 有 `application-identifier`，Keychain 可用（T9 已实现 KeychainStore）；
 * - 为了避免「构建方式导致功能整体不可用」并保持 Key 不进 NSUserDefaults/备份包，这里提供
 *   Application Support 下的专用目录（`provider-secrets/<account>`）作为回退，文件默认受 iOS 数据保护
 *   （App 容器加密）且不出现在备份白名单。
 *
 * 设计取舍：回退层只是**降级路径**；`diagnostics()` 会把实际后端暴露给自检日志，便于确认真机走的是 Keychain。
 */
internal class ProviderKeyStore(
    private val keychain: KeychainStore = KeychainStore(),
) {
    /** 最近一次写入实际使用的后端（"keychain" / "file" / "none"），供诊断。 */
    var lastWriteBackend: String = "none"
        private set

    fun get(account: String): String? {
        val fromKeychain = runCatching { keychain.get(account)?.decodeToString()?.trim() }.getOrNull()
        if (!fromKeychain.isNullOrEmpty()) return fromKeychain
        return readFile(account)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun set(account: String, value: String): Boolean {
        val bytes = value.encodeToByteArray()
        val keychainResult = runCatching { keychain.set(account, bytes) }.getOrNull()
        if (keychainResult is KeychainResult.Success) {
            // Keychain 成功后清掉可能存在的回退文件，避免两份不一致
            deleteFile(account)
            lastWriteBackend = "keychain"
            return true
        }
        val ok = writeFile(account, value)
        lastWriteBackend = if (ok) "file" else "none"
        return ok
    }

    fun delete(account: String): Boolean {
        val keychainOk = runCatching { keychain.delete(account) }.getOrDefault(false)
        val fileDeleted = deleteFile(account)
        lastWriteBackend = "none"
        return keychainOk || fileDeleted
    }

    /** 一次性诊断：真实尝试 Keychain 写/读/删 + 回退目录可写性，返回可读字符串（不含密钥）。 */
    fun diagnostics(): String {
        val account = "provider_key_diagnostic_probe"
        val setResult = runCatching { keychain.set(account, "probe".encodeToByteArray()) }.getOrNull()
        val (kb, kdesc) = when (setResult) {
            is KeychainResult.Success -> "ok" to "Success"
            is KeychainResult.Failure -> "fail" to describe(setResult.error)
            null -> "fail" to "Exception"
        }
        val getValue = runCatching { keychain.get(account)?.decodeToString() }.getOrNull()
        val deleted = runCatching { keychain.delete(account) }.getOrDefault(false)
        val fileOK = writeFile(account, "probe") && readFile(account) == "probe" && deleteFile(account)
        return "keychain=$kdesc keychainGetBytes=${getValue?.length ?: -1} keychainDelete=$deleted " +
            "fallbackDir=${secretsDirectory()?.let { "ok" } ?: "missing"} fallbackFileRW=$fileOK backend=$kb"
    }

    private fun describe(error: KeychainError): String = "${error::class.simpleName}(${error.osStatus})"

    // ---- 回退文件存储（Application Support/provider-secrets/<account>） ----

    private fun secretsDirectory(): String? {
        val dbPath = DatabaseLocations.defaultDatabasePath()
        val appSupport = dbPath.substringBeforeLast('/', missingDelimiterValue = "")
        if (appSupport.isEmpty()) return null
        val dir = "$appSupport/provider-secrets"
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(dir)) {
            runCatching {
                fm.createDirectoryAtPath(
                    path = dir,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null,
                )
            }
        }
        return if (fm.fileExistsAtPath(dir)) dir else null
    }

    private fun filePath(account: String): String? {
        val dir = secretsDirectory() ?: return null
        val safe = account.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }.joinToString("")
        return "$dir/$safe"
    }

    private fun readFile(account: String): String? {
        val path = filePath(account) ?: return null
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        if (data.length.toInt() == 0) return null
        return data.toByteArray().decodeToString()
    }

    private fun writeFile(account: String, value: String): Boolean {
        val path = filePath(account) ?: return false
        val data = value.toNSStringData() ?: return false
        return data.writeToFile(path, atomically = true)
    }

    private fun deleteFile(account: String): Boolean {
        val path = filePath(account) ?: return false
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(path)) return true
        return runCatching { fm.removeItemAtPath(path, error = null) }.getOrDefault(false)
    }
}
