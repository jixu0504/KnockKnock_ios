package com.rhodes.privatechat.shared.backup.wire

import com.rhodes.privatechat.shared.backup.BackupCrypto
import com.rhodes.privatechat.shared.backup.BackupZip
import com.rhodes.privatechat.shared.backup.BackupZipWriter
import com.rhodes.privatechat.shared.db.RhodesDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 全类目金样测试公共设施（T31；双路径共用）。
 *
 * - [readFullGoldenBytes]：expect/actual 读取 `golden/backup-full/` 金样（JVM classpath / iOS test bundle）；
 * - [FullBackupGoldens]：8 包冻结 SHA-256（与 `ios-port/golden/backup-full/README.md` §1 一致）；
 * - [FullBackupGoldenZip]：基于 commonMain [BackupZip]/[BackupZipWriter] 的金样变体改写工具
 *   （构造坏包 / 篡改 recordCount / 注入 DB 失败用），复用 T30 同款工具链；
 * - DB 读取 helper：全部走 SQLDelight 既有 select-all 查询（不新增 SQL）。
 */
internal expect fun readFullGoldenBytes(name: String): ByteArray

internal object FullBackupGoldens {
    private const val DIR = "golden/backup-full"

    val FROZEN_SHA256 = mapOf(
        "full-all-v2.zip" to "d9ed1a9513b6b72939d08a0c354a76c72352801b90c413f4e16d12c13a06bbb7",
        "full-memories-v2.zip" to "2fe8d76fdece9915e0b47b9f8569d9982b104625ba5ba4f57516a52019388143",
        "full-social-v2.zip" to "561954730942fb9a411f3f77bb36379a6f554b03c4b1e31d98c5acdbb4094f4e",
        "full-knowledge-v2.zip" to "a99207f63442c54320471df5d10e49952de42209746257bbaf6a342c8c41ba6a",
        "full-extras-v2.zip" to "5de57cc882eec680262ffffa9aead0c43f15292a04ae1416a3d4d5918314e551",
        "full-media-v2.zip" to "e4bdfc060f824ab9689fc258b73686d0f25a137979b011417953e090821c507d",
        "full-empty-v2.zip" to "6cfc6553cf60c85affa07eb91b6b7b4d63fd7a0a8cdae16476ec61aa581a7180",
        "full-v1-single.zip" to "d1cf1f70c239c5804f121146eaae30d395be25e3a899ecac0cd47eafcc5e6c32",
    )

    val ALL_PACKAGES: List<String> = FROZEN_SHA256.keys.toList()

    fun load(name: String): ByteArray {
        val bytes = readFullGoldenBytes("$DIR/$name")
        val expected = FROZEN_SHA256[name] ?: error("未登记的全类目金样：$name")
        val actual = BackupCrypto.sha256Hex(bytes)
        check(actual == expected) { "金样 $name SHA-256 与冻结表不一致（资源同步过期？实际 $actual）" }
        return bytes
    }
}

/**
 * 金样变体改写器：默认重算 `manifest.files` 的 size/sha256（保证通过 T10 reader 校验），
 * 用 `recomputeFiles=false` 构造 sha 不符的坏包。
 */
internal class FullBackupGoldenZip(private val packageName: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val order = mutableListOf<String>()
    private val entries = linkedMapOf<String, ByteArray>()

    init {
        val raw = FullBackupGoldens.load(packageName)
        BackupZip.read(raw, 1_000, 100L * 1024 * 1024, 500L * 1024 * 1024).forEach { entry ->
            if (!entry.isDirectory) {
                order += entry.name
                entries[entry.name] = entry.data
            }
        }
    }

    fun pagePath(category: String, pageIndex: Int): String =
        "data/pages/${category.lowercase()}/${pageIndex.toString().padStart(6, '0')}.json"

    /** 改写某个分页 JSON（`transform` 收到 page 根对象，需返回同结构对象）。 */
    fun mutatePage(path: String, transform: (JsonObject) -> JsonObject): FullBackupGoldenZip {
        val text = entries.getValue(path).decodeToString()
        entries[path] = transform(json.parseToJsonElement(text).jsonObject).toString().encodeToByteArray()
        return this
    }

    fun mutatePayloadContent(path: String, transform: (JsonObject) -> JsonObject): FullBackupGoldenZip =
        mutatePage(path) { page ->
            val payload = page["payload"]!!.jsonObject
            val content = payload["content"]!!.jsonObject
            JsonObject(page + ("payload" to JsonObject(payload + ("content" to transform(content)))))
        }

    fun mutatePayloadArray(path: String, key: String, transform: (JsonArray) -> JsonArray): FullBackupGoldenZip =
        mutatePage(path) { page ->
            val payload = page["payload"]!!.jsonObject
            JsonObject(page + ("payload" to JsonObject(payload + (key to transform(payload[key] as JsonArray)))))
        }
    fun mutateContentArray(path: String, key: String, transform: (JsonArray) -> JsonArray): FullBackupGoldenZip =
        mutatePayloadContent(path) { content ->
            JsonObject(content + (key to transform(content[key] as JsonArray)))
        }

    fun duplicateEntry(name: String): FullBackupGoldenZip {
        require(name in entries) { "未知条目：$name" }
        order += name
        return this
    }

    fun corruptEntry(name: String, transform: (ByteArray) -> ByteArray): FullBackupGoldenZip {
        entries[name] = transform(entries.getValue(name))
        return this
    }

    fun mutateManifest(transform: (JsonObject) -> JsonObject): FullBackupGoldenZip {
        val text = entries.getValue("manifest.json").decodeToString()
        entries["manifest.json"] = transform(json.parseToJsonElement(text).jsonObject).toString().encodeToByteArray()
        return this
    }

    /** 只删 ZIP 条目、保留 manifest pages/files（构造「清单与 ZIP 不一致」坏包）。 */
    fun removeEntry(name: String): FullBackupGoldenZip {
        entries.remove(name)
        order.remove(name)
        return this
    }

    fun removePage(path: String): FullBackupGoldenZip {
        entries.remove(path)
        order.remove(path)
        mutateManifest { manifest ->
            val pages = manifest["pages"]!!.jsonArray.filter { it.jsonObject["path"]!!.jsonPrimitive.content != path }
            JsonObject(manifest + ("pages" to JsonArray(pages)))
        }
        return this
    }

    fun addRawEntry(name: String, data: ByteArray = "{}".encodeToByteArray()): FullBackupGoldenZip {
        if (name !in entries) order += name
        entries[name] = data
        return this
    }

    fun build(recomputeFiles: Boolean = true, allowUnsafePaths: Boolean = false): ByteArray {
        val manifestText = entries.getValue("manifest.json").decodeToString()
        val manifest = json.parseToJsonElement(manifestText).jsonObject
        val files = if (!recomputeFiles) manifest["files"]!!.jsonArray else {
            JsonArray(manifest["files"]!!.jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                val path = obj["path"]!!.jsonPrimitive.content
                val data = entries[path] ?: return@mapNotNull null
                JsonObject(
                    obj + mapOf(
                        "size" to JsonPrimitive(data.size),
                        "sha256" to JsonPrimitive(BackupCrypto.sha256Hex(data)),
                    ),
                )
            })
        }
        entries["manifest.json"] = JsonObject(manifest + ("files" to files)).toString().encodeToByteArray()
        val payload = order.map { BackupZipWriter.Entry(it, entries.getValue(it)) }
        return BackupZipWriter.write(payload, allowUnsafePaths = allowUnsafePaths)
    }
}

/** 金样还原后的 DB 读取 helper（全部为既有查询；返回 generated data class 行）。 */
internal object FullBackupDb {
    fun operators(db: RhodesDatabase) = db.operatorsQueries.getAllOperators().executeAsList()
    fun sessions(db: RhodesDatabase) = db.chatSessionsQueries.getAllSessions().executeAsList()
    fun messages(db: RhodesDatabase) = db.chatMessagesQueries.getAllMessagesForBackup().executeAsList()
    fun displayEvents(db: RhodesDatabase) = db.chatDisplayEventsQueries.getAllDisplayEvents().executeAsList()
    fun archives(db: RhodesDatabase) = db.chatArchivesQueries.getAllArchives().executeAsList()
    fun historySegments(db: RhodesDatabase) = db.chatArchivesQueries.getAllHistorySegments().executeAsList()
    fun relationships(db: RhodesDatabase) = db.relationshipsQueries.getAllRelationshipsForBackup().executeAsList()
    fun memories(db: RhodesDatabase) = db.memoriesQueries.getAllMemoriesForBackup().executeAsList()
    fun anchors(db: RhodesDatabase) = db.memoryAnchorsQueries.getAllAnchorsForBackup().executeAsList()
    fun memoryItems(db: RhodesDatabase) = db.memoryItemsQueries.getAllMemoryItems().executeAsList()
    fun memoryFacts(db: RhodesDatabase) = db.memoryFactsQueries.getAllMemoryFactsForBackup().executeAsList()
    fun memoryRelations(db: RhodesDatabase) = db.memoryRelationsQueries.getAllMemoryRelationsForBackup().executeAsList()
    fun impressions(db: RhodesDatabase) = db.impressionsQueries.selectAllForBackup().executeAsList()
    fun impressionEvidence(db: RhodesDatabase) = db.impressionEvidenceQueries.selectAllForBackup().executeAsList()
    fun moments(db: RhodesDatabase) = db.momentsQueries.getAllMoments().executeAsList()
    fun comments(db: RhodesDatabase) = db.momentCommentsQueries.getAllCommentsForBackup().executeAsList()
    fun likes(db: RhodesDatabase) = db.momentLikesQueries.getAllLikesForBackup().executeAsList()
    fun diaries(db: RhodesDatabase) = db.diariesQueries.getAllDiariesForBackup().executeAsList()
    fun knowledgeBases(db: RhodesDatabase) = db.knowledgeBasesQueries.getAllKnowledgeBases().executeAsList()
    fun chunks(db: RhodesDatabase) = db.knowledgeBasesQueries.getAllKnowledgeBaseChunks().executeAsList()
    fun assignments(db: RhodesDatabase) = db.knowledgeBasesQueries.getAllKnowledgeBaseAssignments().executeAsList()
    fun gifts(db: RhodesDatabase) = db.giftRecordsQueries.getAllGifts().executeAsList()
    fun dispatches(db: RhodesDatabase) = db.dispatchRecordsQueries.getAllDispatches().executeAsList()
    fun mahjong(db: RhodesDatabase) = db.mahjongSavesQueries.getSave().executeAsOneOrNull()
    fun experiences(db: RhodesDatabase) = db.sharedExperiencesQueries.getAllSharedExperiences().executeAsList()
    fun participants(db: RhodesDatabase) = db.sharedExperiencesQueries.getSharedExperienceParticipants().executeAsList()

    fun counts(db: RhodesDatabase): Map<String, Int> = BackupFullCounts.read(db)
}
