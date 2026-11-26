package com.rhodes.privatechat.shared.backup.operatorcard

import com.rhodes.privatechat.shared.backup.BackupCrypto
import com.rhodes.privatechat.shared.backup.BackupFileEntryWire
import com.rhodes.privatechat.shared.backup.BackupFormatException
import com.rhodes.privatechat.shared.backup.BackupManifestWire
import com.rhodes.privatechat.shared.backup.BackupZipWriter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * rhodes-operator v1 角色卡 Writer（规格卡契约 C23；镜像 `app/.../data/backup/OperatorPackage.kt:37-66`）。
 *
 * 容器：`data/operator.json`（[OperatorCardPayloadWire]）+ 可选 `media/avatar.<ext>` + `manifest.json`（最后写）；
 * manifest：`format="rhodes-operator"`、`formatVersion=1`、`scope="operator_card"`、每条目 `{path,size,sha256}`、
 * `excludedCategories=["chat_history","memories","diaries","moments","credentials"]`。
 *
 * ZIP 写出走纯 Kotlin [BackupZipWriter]（STORED，commonMain 零依赖；Android 包为 deflate，读取侧仍兼容）。
 */
class OperatorCardWriter(
    private val appVersion: String,
    private val now: () -> Long = { 0L },
) {
    private val json = Json { encodeDefaults = true }

    data class Written(val manifest: BackupManifestWire, val bytes: ByteArray)

    /**
     * @param avatarBytes 非空时 payload.avatar 必须非空（镜像 `OperatorPackage.kt:41` 的一致性要求）。
     */
    fun write(payload: OperatorCardPayloadWire, avatarBytes: ByteArray? = null): Written {
        require((payload.avatar == null) == (avatarBytes == null)) { "角色头像清单与文件不一致" }
        val files = mutableListOf<BackupFileEntryWire>()
        val body = json.encodeToString(payload).encodeToByteArray()
        files += BackupFileEntryWire(OPERATOR_PATH, body.size.toLong(), BackupCrypto.sha256Hex(body))
        payload.avatar?.let { avatar ->
            val bytes = avatarBytes ?: throw BackupFormatException("角色包缺少头像文件")
            if (!isSafePath(avatar.archivePath)) throw BackupFormatException("角色包路径不安全")
            files += BackupFileEntryWire(avatar.archivePath, bytes.size.toLong(), BackupCrypto.sha256Hex(bytes))
        }
        val manifest = BackupManifestWire(
            format = CARD_FORMAT,
            formatVersion = CARD_FORMAT_VERSION,
            scope = CARD_SCOPE,
            appVersion = appVersion,
            schemaVersion = 1,
            createdAt = now(),
            backupId = randomUuid(),
            mediaIncluded = avatarBytes != null,
            recordCounts = mapOf(
                "operators" to 1,
                "relationships" to payload.relationships.size,
                "mediaFiles" to if (avatarBytes == null) 0 else 1,
            ),
            files = files,
            excludedCategories = EXCLUDED_CATEGORIES,
        )
        val entries = buildList {
            add(BackupZipWriter.Entry(OPERATOR_PATH, body))
            payload.avatar?.let { avatar -> add(BackupZipWriter.Entry(avatar.archivePath, avatarBytes!!)) }
            // manifest 最后写（OperatorPackage.kt:59 同序）
            add(BackupZipWriter.Entry(MANIFEST_PATH, json.encodeToString(manifest).encodeToByteArray()))
        }
        return Written(manifest, BackupZipWriter.write(entries))
    }

    companion object {
        const val CARD_FORMAT = "rhodes-operator"
        const val CARD_FORMAT_VERSION = 1
        const val CARD_SCOPE = "operator_card"
        const val MANIFEST_PATH = "manifest.json"
        const val OPERATOR_PATH = "data/operator.json"
        val EXCLUDED_CATEGORIES = listOf("chat_history", "memories", "diaries", "moments", "credentials")

        internal fun isSafePath(path: String): Boolean {
            val normalized = path.replace('\\', '/')
            return normalized.isNotBlank() && !normalized.startsWith('/') && !normalized.contains(":/") &&
                normalized.split('/').none { it.isBlank() || it == "." || it == ".." }
        }

        internal fun randomUuid(): String {
            val bytes = Random.nextBytes(16)
            bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
            val hex = "0123456789abcdef"
            return buildString(36) {
                bytes.forEachIndexed { index, byte ->
                    if (index == 4 || index == 6 || index == 8 || index == 10) append('-')
                    val value = byte.toInt() and 0xFF
                    append(hex[value ushr 4])
                    append(hex[value and 0x0F])
                }
            }
        }
    }
}
