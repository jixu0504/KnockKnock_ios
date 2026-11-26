@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rhodes.privatechat.shared.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.CPointerVarOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.length
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecAuthFailed
import platform.Security.errSecDuplicateItem
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecMissingEntitlement
import platform.Security.errSecNotAvailable
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/**
 * iOS Keychain 最小实现骨架（Phase 2 正式版前验证 API 可用性）。
 *
 * 设计说明（对应可行性报告 §5.5）：不照搬 Android 的 EncryptedSharedPreferences
 * 整文件加密模式；iOS 侧密钥型小数据（如 API key、token 派生密钥）走系统 Keychain
 * （kSecClassGenericPassword），批量偏好设置走 JsonSettingsStore。
 *
 * - service 固定为应用级标识，account 为键名（等价 Android 的偏好键）。
 * - [set] 为 upsert：先 SecItemUpdate，命中 errSecItemNotFound 再 SecItemAdd。
 * - 错误码映射为 [KeychainError] 密封类，OSStatus 原文保留在 [KeychainError.osStatus]。
 * - K/N 2.3 新 interop 注意：NSString 不再自动映射 Kotlin String，桥接须显式
 *   NSString.create(string = ...) / NSString.toString()。
 * - 已知骨架级限制（Phase 2 正式版处理）：
 *   1) 未设置 kSecAttrAccessible（默认 whenUnlocked，Phase 2 按数据敏感度分级）。
 *   2) 真机首次解锁前读取会返回 InteractionNotAllowed，上层需感知并重试。
 *   3) 未处理 access group / iCloud 同步等（见 handoff 建议）。
 */
class KeychainStore(private val service: String = DEFAULT_SERVICE) {

    /** upsert：写入或覆盖。返回 [KeychainResult]（成功/失败+错误码）。 */
    fun set(account: String, value: ByteArray): KeychainResult {
        val query = baseQuery(account) ?: return KeychainResult.Failure(KeychainError.Param)
        val update = updateDict(value)
        if (update != null) {
            val updateStatus = SecItemUpdate(query, update)
            CFRelease(update)
            if (updateStatus == errSecSuccess) {
                CFRelease(query)
                return KeychainResult.Success(value)
            }
            if (updateStatus != errSecItemNotFound) {
                CFRelease(query)
                return KeychainResult.Failure(KeychainError.from(updateStatus))
            }
        }
        // 不存在 → 新增
        val nsData = retainedNSData(value)
        CFDictionaryAddValue(query, kSecValueData, nsData)
        val addStatus = SecItemAdd(query, null)
        CFRelease(query)
        if (nsData != null) CFRelease(nsData)
        return if (addStatus == errSecSuccess) KeychainResult.Success(value)
        else KeychainResult.Failure(KeychainError.from(addStatus))
    }

    /** 读取。未命中返回 null（不抛错；需要错误码用 [getOrError]）。 */
    fun get(account: String): ByteArray? {
        val r = getOrError(account)
        return (r as? KeychainResult.Success)?.value as? ByteArray
    }

    /** 读取并保留错误码：Success(value) / Failure(error)。 */
    fun getOrError(account: String): KeychainResult {
        val query = baseQuery(account) ?: return KeychainResult.Failure(KeychainError.Param)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        return memScoped {
            // SecItemCopyMatching 的 out 参数在 K/N 2.3 新 interop 下的实际类型为
            // CValuesRef<CPointerVarOf<CFTypeRef>>（CFTypeRefVar 别名不可直接引用）
            val result = alloc<CPointerVarOf<CFTypeRef>>()
            val status = SecItemCopyMatching(query, result.ptr)
            CFRelease(query)
            when {
                status == errSecSuccess -> {
                    val nsData = CFBridgingRelease(result.value) as? NSData
                    KeychainResult.Success(nsData?.toByteArray() ?: ByteArray(0))
                }
                else -> KeychainResult.Failure(KeychainError.from(status))
            }
        }
    }

    fun delete(account: String): Boolean {
        val r = deleteOrError(account)
        return r is KeychainResult.Success
    }

    fun deleteOrError(account: String): KeychainResult {
        val query = baseQuery(account) ?: return KeychainResult.Failure(KeychainError.Param)
        val status = SecItemDelete(query)
        CFRelease(query)
        return if (status == errSecSuccess || status == errSecItemNotFound) KeychainResult.Success(true)
        else KeychainResult.Failure(KeychainError.from(status))
    }

    fun contains(account: String): Boolean = get(account) != null

    // ---- interop 细节 ----

    private fun baseQuery(account: String): CFMutableDictionaryRef? {
        val dict = CFDictionaryCreateMutable(
            null, 3, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr
        ) ?: return null
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        addRetained(dict, kSecAttrService, NSString.create(string = service))
        addRetained(dict, kSecAttrAccount, NSString.create(string = account))
        return dict
    }

    private fun addRetained(dict: CFMutableDictionaryRef?, key: CFTypeRef?, value: Any?) {
        val ref = CFBridgingRetain(value)
        CFDictionaryAddValue(dict, key, ref)
        CFRelease(ref) // CFDictionary 自身会 retain，配对释放
    }

    private fun retainedNSData(value: ByteArray): CFTypeRef? =
        CFBridgingRetain(value.toNSData())

    private fun updateDict(value: ByteArray): CFMutableDictionaryRef? {
        val dict = CFDictionaryCreateMutable(
            null, 1, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr
        ) ?: return null
        addRetained(dict, kSecValueData, value.toNSData())
        return dict
    }

    companion object {
        const val DEFAULT_SERVICE = "com.rhodeslink.shared.keychain"
    }
}

sealed class KeychainResult {
    data class Success(val value: Any?) : KeychainResult()
    data class Failure(val error: KeychainError) : KeychainResult()
}

/** kSec 错误码 → 语义错误。OSStatus 原文保留在 [osStatus]。 */
sealed class KeychainError(val osStatus: Int) {
    /** 条目不存在（errSecItemNotFound，-25300） */
    data object NotFound : KeychainError(errSecItemNotFound)

    /** 条目已存在（errSecDuplicateItem，-25299） */
    data object Duplicate : KeychainError(errSecDuplicateItem)

    /** 鉴权失败（errSecAuthFailed，-25293） */
    data object AuthFailed : KeychainError(errSecAuthFailed)

    /** 交互不允许——真机常见于锁屏/首次解锁前（errSecInteractionNotAllowed，-25308） */
    data object InteractionNotAllowed : KeychainError(errSecInteractionNotAllowed)

    /** Keychain entitlement 缺失（errSecMissingEntitlement，-34018） */
    data object MissingEntitlement : KeychainError(errSecMissingEntitlement)

    /** 无可用 Keychain（errSecNotAvailable，-25291）——K/N standalone 测试运行器实测返回 */
    data object NotAvailable : KeychainError(errSecNotAvailable)

    /** 参数错误（errSecParam，-50） */
    data object Param : KeychainError(-50)

    /** 未分类错误，原始 OSStatus 在 [osStatus]（继承基类） */
    data class Other(val status: Int) : KeychainError(status)

    companion object {
        fun from(status: Int): KeychainError = when (status) {
            errSecItemNotFound -> NotFound
            errSecDuplicateItem -> Duplicate
            errSecAuthFailed -> AuthFailed
            errSecInteractionNotAllowed -> InteractionNotAllowed
            errSecMissingEntitlement -> MissingEntitlement
            errSecNotAvailable -> NotAvailable
            -50 -> Param
            else -> Other(status)
        }
    }
}

// ---- NSData ↔ ByteArray ----

internal fun ByteArray.toNSData(): NSData? = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}

internal fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    if (n == 0) return ByteArray(0)
    return ByteArray(n).apply {
        usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}

internal fun String.toNSStringData(): NSData? =
    NSString.create(string = this)?.dataUsingEncoding(NSUTF8StringEncoding)
