package com.rhodes.privatechat.shared.backup.operatorcard

import com.rhodes.privatechat.shared.backup.BackupCrypto
import com.rhodes.privatechat.shared.backup.BackupFileReader
import com.rhodes.privatechat.shared.backup.BackupFormatException
import com.rhodes.privatechat.shared.backup.BackupManifestWire
import com.rhodes.privatechat.shared.backup.BackupZip
import kotlinx.serialization.json.Json

/**
 * rhodes-operator v1 角色卡 Reader（规格卡契约 C24-C25；镜像 `app/.../data/backup/OperatorPackage.kt:68-119`）。
 *
 * 校验：路径安全/重复拒绝、条目上限 16 / 单文件 20MB / 总计 25MB（`:135-139` 同值）、manifest 三元组、
 * 逐条目 size+SHA-256、payload 解码、头像清单一致性。`readCompatible` 兼容旧版单角色 JSON
 * （`ExportHelper.importFromString` 目标，`:103-119`）。
 */
class OperatorCardReader {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(bytes: ByteArray): OperatorCard {
        val zipEntries = runCatching {
            BackupZip.read(bytes, MAX_ENTRIES, MAX_ENTRY_BYTES, MAX_TOTAL_BYTES)
        }.getOrElse { throw BackupFormatException(it.message ?: "角色包无效") }

        val entries = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        for (entry in zipEntries) {
            val path = entry.name.replace('\\', '/')
            if (!BackupFileReader.isSafeArchivePath(path)) throw BackupFormatException("角色包路径不安全")
            if (entries.containsKey(path)) throw BackupFormatException("角色包存在重复路径")
            if (entry.isDirectory) continue
            totalBytes += entry.uncompressedSize
            if (totalBytes > MAX_TOTAL_BYTES) throw BackupFormatException("角色包解压总大小超过限制")
            entries[path] = entry.data
        }

        val manifestContent = entries[OperatorCardWriter.MANIFEST_PATH]?.decodeToString()
            ?: throw BackupFormatException("缺少 manifest.json")
        val manifest = runCatching { json.decodeFromString<BackupManifestWire>(manifestContent) }
            .getOrElse { throw BackupFormatException("manifest.json 无法解析") }
        if (manifest.format != OperatorCardWriter.CARD_FORMAT ||
            manifest.formatVersion != OperatorCardWriter.CARD_FORMAT_VERSION ||
            manifest.scope != OperatorCardWriter.CARD_SCOPE
        ) {
            throw BackupFormatException("不支持的角色包格式")
        }
        if (manifest.files.map { it.path }.distinct().size != manifest.files.size) {
            throw BackupFormatException("角色包清单存在重复文件")
        }
        if (manifest.files.map { it.path }.toSet() != entries.keys - OperatorCardWriter.MANIFEST_PATH) {
            throw BackupFormatException("角色包清单与文件不一致")
        }
        manifest.files.forEach { file ->
            val actual = entries[file.path] ?: throw BackupFormatException("缺少文件：${file.path}")
            if (actual.size.toLong() != file.size || BackupCrypto.sha256Hex(actual) != file.sha256.lowercase()) {
                throw BackupFormatException("角色包校验失败：${file.path}")
            }
        }

        val payloadContent = entries[OperatorCardWriter.OPERATOR_PATH]?.decodeToString()
            ?: throw BackupFormatException("缺少角色资料")
        val payload = runCatching { json.decodeFromString<OperatorCardPayloadWire>(payloadContent) }
            .getOrElse { throw BackupFormatException("角色资料无法解析") }
        if (payload.operator.id.isBlank() || payload.operator.name.isBlank()) {
            throw BackupFormatException("角色资料不完整")
        }
        if ((payload.avatar == null) != !manifest.mediaIncluded) throw BackupFormatException("角色包头像清单不一致")
        val avatarBytes = payload.avatar?.let { avatar ->
            if (!OperatorCardWriter.isSafePath(avatar.archivePath)) throw BackupFormatException("角色包路径不安全")
            entries[avatar.archivePath] ?: throw BackupFormatException("角色包缺少头像文件")
        }
        return OperatorCard(payload, avatarBytes)
    }

    /**
     * 兼容读取：优先当前签名角色包（zip），失败回退旧版单角色 JSON（`OperatorPackage.kt:103-119`）。
     * 旧 JSON 形态：`{type:"operators", operators:[Operator 22 字段], relationships:[…]}`。
     */
    fun readCompatible(bytes: ByteArray): OperatorCard {
        val readError = runCatching { read(bytes) }
        readError.getOrNull()?.let { return it }
        val legacy = runCatching {
            json.decodeFromString<LegacyOperatorJson>(bytes.decodeToString())
        }.getOrNull() ?: throw (readError.exceptionOrNull() as? BackupFormatException
            ?: BackupFormatException("角色包无效"))
        if (legacy.type != "operators" || legacy.operators.size != 1) {
            throw BackupFormatException("请选择新版角色包，或仅包含一个角色的旧版角色 JSON 文件")
        }
        val operator = legacy.operators.single()
        val payload = OperatorCardPayloadWire(
            operator = operator,
            relationships = legacy.relationships.filter { it.operatorId == operator.id },
        )
        // 镜像 Android：旧 JSON 先封装成 v1 角色包再走同一校验路径。
        val wrapped = OperatorCardWriter("legacy-json").write(payload).bytes
        return read(wrapped)
    }

    private companion object {
        const val MAX_ENTRIES = 16
        const val MAX_ENTRY_BYTES = 20L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 25L * 1024 * 1024
    }
}
