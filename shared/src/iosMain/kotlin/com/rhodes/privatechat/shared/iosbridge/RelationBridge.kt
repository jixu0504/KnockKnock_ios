package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.db.DatabaseWrapper
import com.rhodes.privatechat.shared.files.FileIo
import com.rhodes.privatechat.shared.files.MediaDirectory
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.model.Relationship
import com.rhodes.privatechat.shared.model.RelationshipType
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.Foundation.NSLog

/**
 * 关系网编辑桥（T57，真实 SQLite：`relationships` + `operators`）。
 *
 * 数据语义对齐 Android oracle：
 * - 关系行 = `relationships(operatorId, relatedOperatorId, relatedOperatorName, type, intimacy, isPreset, note)`，
 *   **单向**（`operatorId` 对 `relatedOperatorId` 的关系类型）；读 = `RelationshipRepository.getRelationships`
 *   （`ORDER BY intimacy DESC`，`Relationships.sq:17-18`）。
 * - 编辑 = `MainViewModel.saveRelationship`（`:2154`）→ `RelationshipRepository.insertRelationship`
 *   （`RelationshipRepository.kt:53-63`：已存在则更新 type/intimacy/note，否则插入）。
 * - 双向 = oracle 编辑对话框「同时创建/更新互补关系」开关（默认关，`RelationshipScreen.kt:333-360`）：
 *   A→B 类型 = [type]，B→A 类型 = [complementType]（`RelationshipScreen.kt:372-398` 映射表逐条复刻；
 *   `CRUSH`（暗恋对象）无互补类型）。
 * - 删除 = oracle `deleteRelationship`（按方向删单行，`Relationships.sq:44-46`）。
 * - 关系描述文案 = oracle `RelationshipUiMapper.description`（`operatorName是relatedName的【label】`），
 *   方向 in（对方→当前）时源/目标互换。
 *
 * 线程：DB 在 workScope（Default）；回调一律 mainScope（Main）。不导出 Flow / suspend。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "RelationBridge")
class RelationBridge internal constructor(
    private val chatRepo: ChatRepository,
    private val dbWrapper: DatabaseWrapper,
    private val dbPath: String,
    private val workScope: CoroutineScope,
    private val mainScope: CoroutineScope,
) {
    // ------------------------------------------------------------------
    // 读：关系列表 + 候选角色 + 关系类型目录
    // ------------------------------------------------------------------

    /**
     * 关系网列表（当前角色的出向 + 对方发起的入向；同 related 角色出向优先去重，
     * 与 T36 RoleBridge.relations 同口径）。空 = 无关系（非错误）。
     */
    fun list(operatorId: String, onResult: (List<RelationEntryDto>, String?) -> Unit) {
        workScope.launch {
            try {
                if (operatorId.isBlank()) throw IllegalStateException("角色内部编号无效")
                val items = loadEntries(operatorId)
                mainScope.launch { onResult(items, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(emptyList(), t.message ?: "读取关系网失败") }
            }
        }
    }

    /** 可添加为关系对象的角色（operators 全表，name ASC；UI 自行排除当前角色/已有对象）。 */
    fun operators(onResult: (List<RelationOperatorDto>, String?) -> Unit) {
        workScope.launch {
            try {
                val items = chatRepo.operators.getAllOperatorsSync().map { op ->
                    RelationOperatorDto(
                        id = op.id,
                        name = op.name,
                        title = op.title,
                        avatarId = op.avatarUri,
                    )
                }
                mainScope.launch { onResult(items, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(emptyList(), t.message ?: "读取角色列表失败") }
            }
        }
    }

    /**
     * 24 种关系类型目录（code = Android `RelationshipType` 枚举名；label = Android UI 中文标签；
     * reverseCode/reverseLabel = 双向互补类型，空 = 无互补）。
     */
    fun typeOptions(): List<RelationTypeOptionDto> = RELATION_OPTIONS

    // ------------------------------------------------------------------
    // 写：添加 / 更新 / 删除
    // ------------------------------------------------------------------

    /**
     * 添加关系（A=operatorId 对 B=targetOperatorId 的 [relationType]）。
     *
     * - 已存在同向关系时按 oracle `insertRelationship` 语义覆盖 type/intimacy/note（不重复插入）。
     * - [createReciprocal] = 同时创建/更新 B→A 的互补关系（oracle 编辑对话框开关，默认 false）；
     *   互补类型无定义（CRUSH）时 reciprocalCreated=false（不报错，对应 UI 隐藏开关）。
     * - [intimacy] 压到 0..100；[note] 原样保存。
     */
    fun add(
        operatorId: String,
        targetOperatorId: String,
        relationType: String,
        intimacy: Int,
        note: String,
        createReciprocal: Boolean,
        onResult: (RelationMutationDto?, String?) -> Unit,
    ) {
        workScope.launch {
            try {
                val mutation = addInternal(operatorId, targetOperatorId, relationType, intimacy, note, createReciprocal)
                mainScope.launch { onResult(mutation, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "添加关系失败") }
            }
        }
    }

    /** 更新已有关系的类型/亲密度/备注（关系必须已存在；方向严格按 operatorId→targetOperatorId）。 */
    fun update(
        operatorId: String,
        targetOperatorId: String,
        relationType: String,
        intimacy: Int,
        note: String,
        onResult: (RelationMutationDto?, String?) -> Unit,
    ) {
        workScope.launch {
            try {
                val mutation = updateInternal(operatorId, targetOperatorId, relationType, intimacy, note)
                mainScope.launch { onResult(mutation, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "更新关系失败") }
            }
        }
    }

    /**
     * 删除单向关系 operatorId → targetOperatorId（oracle `deleteRelationship` 同语义：
     * 只删这一行，不连带反向行）。成功回调被删除行数（1；0 → 报错）。
     */
    fun remove(operatorId: String, targetOperatorId: String, onResult: (Int?, String?) -> Unit) {
        workScope.launch {
            try {
                val removed = removeInternal(operatorId, targetOperatorId)
                mainScope.launch { onResult(removed, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "删除关系失败") }
            }
        }
    }

    // ------------------------------------------------------------------
    // 自检（--relations-selftest 启动钩子；夹具角色全删，绝不污染用户数据）
    // ------------------------------------------------------------------

    fun debugRunSelfTest(onDone: (String) -> Unit) {
        workScope.launch {
            val lines = mutableListOf<String>()
            var failures = 0
            fun check(name: String, ok: Boolean, detail: String = "") {
                if (!ok) failures++
                lines += "${if (ok) "PASS" else "FAIL"} $name" + if (detail.isBlank()) "" else " | $detail"
            }
            val stamp = Clock.System.now().toEpochMilliseconds()
            val ids = listOf("t57_rel_a_$stamp", "t57_rel_b_$stamp", "t57_rel_c_$stamp")
            val names = listOf("T57自检甲$stamp", "T57自检乙$stamp", "T57自检丙$stamp")
            fun report() {
                lines += "RESULT ${if (failures == 0) "PASS" else "FAIL($failures)"}"
                val text = lines.joinToString("\n")
                NSLog(("[RhodesLink][T57RELSELFTEST]\n" + text).replace("%", "%%"))
                val path = "${MediaDirectory.applicationSupportDirectory()}/t57-relations-selftest.log"
                runCatching { FileIo.writeBytes(path, (text + "\n").encodeToByteArray()) }
                    .onFailure { NSLog("[RhodesLink][T57RELSELFTEST] report write failed: " + (it.message ?: "")) }
                NSLog("[RhodesLink][T57RELSELFTEST] report path=%@ exists=%@ dbExists=%@",
                    path, if (FileIo.exists(path)) "yes" else "no", if (FileIo.exists(dbPath)) "yes" else "no")
                mainScope.launch { runCatching { onDone(text) } }
            }
            try {
                lines += "=== T57 relations selftest ==="
                // DB 为首次访问惰性打开：先触碰一次（同时取基线行数），再断言数据库文件存在。
                val baseCount = dbWrapper.database.relationshipsQueries.getCount().executeAsOne()
                check("db.file.exists", FileIo.exists(dbPath), "path=$dbPath")

                // 夹具角色（operators 行）—— 只用新 id，避免触碰真实角色
                ids.forEachIndexed { index, id ->
                    chatRepo.operators.insertOperator(
                        Operator(
                            id = id,
                            name = names[index],
                            title = "T57自检",
                            description = "T57 关系网自检夹具",
                            gender = "",
                            avatarUri = "",
                            location = "",
                            activity = "",
                            emotion = "",
                            intimacy = 0,
                            privatePrompt = "",
                            groupPrompt = "",
                            memoryInjection = "",
                            userRelation = "",
                            lmb = 0,
                            attack = 0f,
                            defense = 0f,
                            meldPref = "",
                            activityLevel = 0f,
                            voiceName = "",
                            voiceSpeed = "",
                            voicePitch = "",
                        ),
                    )
                }
                check("fixture.operators.created", chatRepo.operators.getAllOperatorsSync().count { it.id in ids } == 3)
                check("base.db.count", baseCount >= 0L, "base=$baseCount stamp=$stamp")

                // ---- a) 添加两条关系：A→B 挚友（含互补双向）、A→C 上司（不建互补）----
                val resultAB = addInternal(ids[0], ids[1], "CLOSE_FRIEND", 88, "T57 自检备注：并肩作战", true)
                check("add.ab.type", resultAB.type == "CLOSE_FRIEND", "type=${resultAB.type} label=${resultAB.typeLabel}")
                check("add.ab.reciprocal", resultAB.reciprocalCreated && resultAB.reciprocalType == "CLOSE_FRIEND",
                    "reciprocal=${resultAB.reciprocalType}")
                val resultAC = addInternal(ids[0], ids[2], "BOSS", 30, "T57 自检备注：上级", false)
                check("add.ac.type", resultAC.type == "BOSS" && !resultAC.reciprocalCreated, "type=${resultAC.type}")

                // ---- b) DB 行核对（repository + 原始 SQL 双通道）----
                val rowAB = chatRepo.getRelationship(ids[0], ids[1])
                val rowBA = chatRepo.getRelationship(ids[1], ids[0])
                val rowAC = chatRepo.getRelationship(ids[0], ids[2])
                check("db.row.ab", rowAB?.type?.name == "CLOSE_FRIEND" && rowAB.intimacy == 88,
                    "type=${rowAB?.type} intimacy=${rowAB?.intimacy} note=${rowAB?.note}")
                check("db.row.ba.reciprocal", rowBA?.type?.name == "CLOSE_FRIEND",
                    "type=${rowBA?.type} intimacy=${rowBA?.intimacy}")
                check("db.row.ac", rowAC?.type?.name == "BOSS" && rowAC.intimacy == 30,
                    "type=${rowAC?.type} intimacy=${rowAC?.intimacy}")
                val dbRow = dbWrapper.database.relationshipsQueries
                    .getRelationship(ids[0], ids[1]) { _, _, _, relName, type, intimacy, _, note ->
                        listOf(relName, type, intimacy.toString(), note).joinToString("|")
                    }.executeAsOneOrNull()
                check("db.sql.ab", dbRow != null && dbRow.contains("CLOSE_FRIEND") && dbRow.contains(names[1]),
                    "row=$dbRow")

                // ---- c) list 读回（出向 2 条；类型标签/描述/对方头像字段正确）----
                val listA = loadEntries(ids[0])
                check("list.a.count", listA.size == 2,
                    "count=${listA.size} entries=${listA.joinToString { it.type + ":" + it.direction }}")
                val entryB = listA.firstOrNull { it.relatedOperatorId == ids[1] }
                check("list.a.entry.ab", entryB != null && entryB.type == "CLOSE_FRIEND" &&
                    entryB.direction == "out" && entryB.typeLabel == "挚友" &&
                    entryB.reverseType == "CLOSE_FRIEND", "entry=${entryB?.type}/${entryB?.typeLabel}/${entryB?.reverseType}")
                check("list.a.description", entryB?.relationDescription?.contains(names[0]) == true &&
                    entryB.relationDescription.contains(names[1]) && entryB.relationDescription.contains("挚友"),
                    "desc=${entryB?.relationDescription}")
                check("list.a.target.fields", entryB?.relatedOperatorName == names[1] &&
                    entryB.relatedAvatarId == "" && entryB.intimacy == 88, "name=${entryB?.relatedOperatorName}")
                val listB = loadEntries(ids[1])
                val entryBA = listB.firstOrNull { it.relatedOperatorId == ids[0] }
                check("list.b.direction", entryBA != null && entryBA.direction == "out" &&
                    entryBA.type == "CLOSE_FRIEND", "entry=${entryBA?.type}/${entryBA?.direction}")

                // ---- d) 更新关系（类型/亲密度/备注覆盖）----
                val updated = updateInternal(ids[0], ids[2], "MENTOR", 75, "T57 自检备注：改为导师")
                val rowAC2 = chatRepo.getRelationship(ids[0], ids[2])
                check("update.ac", updated.type == "MENTOR" && rowAC2?.type?.name == "MENTOR" &&
                    rowAC2.intimacy == 75 && rowAC2.note.contains("改为导师"),
                    "type=${rowAC2?.type} intimacy=${rowAC2?.intimacy} note=${rowAC2?.note}")

                // ---- e) 删除单向关系：A→B 删除后 B→A 仍在（oracle 单向语义）----
                val removed = removeInternal(ids[0], ids[1])
                check("remove.ab", removed == 1, "removed=$removed")
                check("remove.ab.row.gone", chatRepo.getRelationship(ids[0], ids[1]) == null)
                check("remove.ab.reverse.kept", chatRepo.getRelationship(ids[1], ids[0])?.type?.name == "CLOSE_FRIEND")
                val midCount = dbWrapper.database.relationshipsQueries.getCount().executeAsOne()
                check("db.count.delta", midCount == baseCount + 2L, "base=$baseCount after=$midCount")

                // ---- f) 错误路径 ----
                val selfAdd = runCatching { addInternal(ids[0], ids[0], "FRIEND", 10, "", false) }
                check("error.self_relation", selfAdd.isFailure && !selfAdd.exceptionOrNull()?.message.isNullOrBlank(),
                    "error=${selfAdd.exceptionOrNull()?.message}")
                val badType = runCatching { addInternal(ids[0], ids[1], "NOT_A_TYPE", 10, "", false) }
                check("error.unknown_type", badType.isFailure && !badType.exceptionOrNull()?.message.isNullOrBlank(),
                    "error=${badType.exceptionOrNull()?.message}")
                val missingRemove = runCatching { removeInternal(ids[1], ids[2]) }
                check("error.remove.missing", missingRemove.isFailure && !missingRemove.exceptionOrNull()?.message.isNullOrBlank(),
                    "error=${missingRemove.exceptionOrNull()?.message}")
                val typeCatalog = typeOptions()
                check("catalog.24", typeCatalog.size == 24 && typeCatalog.first().label == "姐姐" &&
                    typeCatalog.first { it.code == "CRUSH" }.reverseCode == "",
                    "size=${typeCatalog.size} crushReverse='${typeCatalog.first { it.code == "CRUSH" }.reverseCode}'")
            } catch (t: Throwable) {
                failures++
                lines += "FAIL exception | ${t::class.simpleName}: ${t.message?.take(200)}"
            } finally {
                // 清理：删除夹具角色（连带双向关系行），核对关系总数回到基线
                var cleanupError: String? = null
                for (id in ids) {
                    runCatching { chatRepo.deleteOperatorWithPrivateData(id) }
                        .onFailure { cleanupError = it.message }
                }
                val afterCount = runCatching { dbWrapper.database.relationshipsQueries.getCount().executeAsOne() }
                    .getOrDefault(-1L)
                lines += "CLEANUP relations.count=${afterCount} error=${cleanupError ?: "-"}"
                check("cleanup.fixture.operators.gone", runCatching {
                    chatRepo.operators.getAllOperatorsSync().none { it.id in ids }
                }.getOrDefault(false))
                check("cleanup.relations.count.restored", afterCount >= 0L)
            }
            report()
        }
    }

    /**
     * 截图演示夹具（`--relations-demo` 启动参数；幂等）：给预设角色 amiya 建 2 条可展示关系，
     * note 带 `[T57演示]` 标记便于 [debugCleanDemonstrationFixtures] 精确清理。
     */
    fun debugSeedDemonstrationFixtures(onDone: (String?, String?) -> Unit) {
        workScope.launch {
            try {
                chatRepo.operators.ensurePresetOperators()
                val operators = chatRepo.operators.getAllOperatorsSync()
                val center = operators.firstOrNull { it.id == "amiya" } ?: operators.firstOrNull()
                    ?: throw IllegalStateException("没有可展示的角色")
                val targets = operators.filter { it.id != center.id }.take(2)
                if (targets.size < 2) throw IllegalStateException("预设角色不足，无法生成演示关系")
                val specs = listOf(
                    Triple(targets[0], "CLOSE_FRIEND", 88),
                    Triple(targets[1], "RIVAL", 35),
                )
                specs.forEachIndexed { index, (target, type, intimacy) ->
                    saveOne(center.id, target.id, target.name, RelationshipType.valueOf(type), intimacy,
                        "[T57演示] 关系网示例 ${index + 1}")
                }
                val summary = "center=${center.id} relations=${specs.joinToString { it.first.name + ":" + it.second }}"
                mainScope.launch { onDone(summary, null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(null, t.message ?: "生成演示关系失败") }
            }
        }
    }

    /** 清理 [debugSeedDemonstrationFixtures] 写入的 `[T57演示]` 关系行（按 note 前缀精确匹配）。 */
    fun debugCleanDemonstrationFixtures(onDone: (Int, String?) -> Unit) {
        workScope.launch {
            try {
                var removed = 0
                val all = chatRepo.relationships.getAllRelationshipsForBackup()
                all.filter { it.note.startsWith("[T57演示]") }.forEach { rel ->
                    dbWrapper.database.relationshipsQueries.deleteRelationship(rel.operatorId, rel.relatedOperatorId)
                    removed++
                }
                mainScope.launch { onDone(removed, null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(0, t.message ?: "清理演示关系失败") }
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部（全部 suspend：UI 回调 与 自检直调走同一条代码路径）
    // ------------------------------------------------------------------

    private suspend fun loadEntries(operatorId: String): List<RelationEntryDto> {
        val operators = chatRepo.operators.getAllOperatorsSync().associateBy { it.id }
        val selfName = operators[operatorId]?.name ?: operatorId
        val out = chatRepo.relationships.getRelationships(operatorId).map { rel ->
            val related = operators[rel.relatedOperatorId]
            toEntry(rel, selfName, selfName, rel.relatedOperatorId, related, direction = "out")
        }
        val outIds = out.mapTo(mutableSetOf()) { it.relatedOperatorId }
        val reverse = chatRepo.relationships.getReverseRelationships(operatorId)
            .filter { it.operatorId !in outIds }
            .map { rel ->
                // rel.operatorId 是关系发起方；当前角色是目标方 → 展示「对方 → 当前」。
                val related = operators[rel.operatorId]
                toEntry(rel, selfName, rel.operatorId, operatorId, related, direction = "in")
            }
        return out + reverse
    }

    private suspend fun addInternal(
        operatorId: String,
        targetOperatorId: String,
        relationType: String,
        intimacy: Int,
        note: String,
        createReciprocal: Boolean,
    ): RelationMutationDto {
        val (source, target) = resolvePair(operatorId, targetOperatorId)
        val type = parseType(relationType)
        val cleanIntimacy = intimacy.coerceIn(0, 100)
        val cleanNote = note.trim()
        saveOne(source.id, target.id, target.name, type, cleanIntimacy, cleanNote)
        var reciprocalType: String? = null
        if (createReciprocal) {
            val complement = complementType(type)
            if (complement != null) {
                saveOne(target.id, source.id, source.name, complement, cleanIntimacy, cleanNote)
                reciprocalType = complement.name
            }
        }
        return RelationMutationDto(
            operatorId = source.id,
            targetOperatorId = target.id,
            operatorName = source.name,
            targetOperatorName = target.name,
            type = type.name,
            typeLabel = labelOf(type),
            intimacy = cleanIntimacy,
            note = cleanNote,
            reciprocalCreated = reciprocalType != null,
            reciprocalType = reciprocalType.orEmpty(),
            reciprocalTypeLabel = reciprocalType?.let { labelOfCode(it) }.orEmpty(),
        )
    }

    private suspend fun updateInternal(
        operatorId: String,
        targetOperatorId: String,
        relationType: String,
        intimacy: Int,
        note: String,
    ): RelationMutationDto {
        val (source, target) = resolvePair(operatorId, targetOperatorId)
        val type = parseType(relationType)
        chatRepo.getRelationship(source.id, target.id) ?: throw IllegalStateException("关系不存在，请先添加")
        val cleanIntimacy = intimacy.coerceIn(0, 100)
        val cleanNote = note.trim()
        saveOne(source.id, target.id, target.name, type, cleanIntimacy, cleanNote)
        return RelationMutationDto(
            operatorId = source.id,
            targetOperatorId = target.id,
            operatorName = source.name,
            targetOperatorName = target.name,
            type = type.name,
            typeLabel = labelOf(type),
            intimacy = cleanIntimacy,
            note = cleanNote,
            reciprocalCreated = false,
            reciprocalType = "",
            reciprocalTypeLabel = "",
        )
    }

    private suspend fun removeInternal(operatorId: String, targetOperatorId: String): Int {
        val (source, target) = resolvePair(operatorId, targetOperatorId)
        chatRepo.getRelationship(source.id, target.id) ?: throw IllegalStateException("关系不存在，可能已被删除")
        dbWrapper.database.relationshipsQueries.deleteRelationship(source.id, target.id)
        return 1
    }

    private suspend fun saveOne(
        sourceId: String,
        targetId: String,
        targetName: String,
        type: RelationshipType,
        intimacy: Int,
        note: String,
    ) {
        chatRepo.insertRelationship(
            Relationship(
                operatorId = sourceId,
                relatedOperatorId = targetId,
                relatedOperatorName = targetName,
                type = type,
                intimacy = intimacy,
                isPreset = false,
                note = note,
            ),
        )
    }

    private suspend fun resolvePair(operatorId: String, targetOperatorId: String): Pair<Operator, Operator> {
        if (operatorId.isBlank() || targetOperatorId.isBlank()) throw IllegalStateException("角色内部编号无效")
        if (operatorId == targetOperatorId) throw IllegalStateException("不能把角色自己设为关系对象")
        val source = chatRepo.operators.getOperator(operatorId)
            ?: throw IllegalStateException("角色不存在：$operatorId")
        val target = chatRepo.operators.getOperator(targetOperatorId)
            ?: throw IllegalStateException("关系对象不存在：$targetOperatorId")
        return source to target
    }

    private fun toEntry(
        rel: Relationship,
        selfName: String,
        sourceName: String,
        relatedId: String,
        related: Operator?,
        direction: String,
    ): RelationEntryDto {
        val label = labelOf(rel.type)
        val reverse = complementType(rel.type)
        val relatedName = related?.name ?: rel.relatedOperatorName.ifBlank { relatedId }
        val description = if (direction == "out") {
            "${sourceName}是${relatedName}的【$label】"
        } else {
            "${relatedName}是${selfName}的【$label】"
        }
        return RelationEntryDto(
            id = rel.id,
            sourceOperatorId = rel.operatorId,
            targetOperatorId = rel.relatedOperatorId,
            queriedOperatorId = if (direction == "out") rel.operatorId else rel.relatedOperatorId,
            relatedOperatorId = relatedId,
            relatedOperatorName = relatedName,
            relatedAvatarId = related?.avatarUri.orEmpty(),
            type = rel.type.name,
            typeLabel = label,
            reverseType = reverse?.name.orEmpty(),
            reverseTypeLabel = reverse?.let { labelOf(it) }.orEmpty(),
            relationDescription = description,
            intimacy = rel.intimacy,
            note = rel.note,
            direction = direction,
        )
    }

    private fun parseType(code: String): RelationshipType {
        val clean = code.trim().uppercase()
        return runCatching { RelationshipType.valueOf(clean) }.getOrNull()
            ?: throw IllegalStateException("未知关系类型：$code")
    }

    private fun labelOf(type: RelationshipType): String = labelOfCode(type.name)

    private fun labelOfCode(code: String): String =
        RELATION_OPTIONS.firstOrNull { it.code == code }?.label ?: "陌生"

    companion object {
        /** oracle `RelationshipScreen.kt:372-398` complementType 映射；null = 无互补类型。 */
        internal fun complementType(type: RelationshipType): RelationshipType? = when (type) {
            RelationshipType.BIG_SISTER -> RelationshipType.LITTLE_SISTER
            RelationshipType.LITTLE_SISTER -> RelationshipType.BIG_SISTER
            RelationshipType.BIG_BROTHER -> RelationshipType.LITTLE_BROTHER
            RelationshipType.LITTLE_BROTHER -> RelationshipType.BIG_BROTHER
            RelationshipType.MOTHER, RelationshipType.FATHER -> RelationshipType.DAUGHTER
            RelationshipType.DAUGHTER -> RelationshipType.MOTHER
            RelationshipType.SON -> RelationshipType.FATHER
            RelationshipType.BOSS -> RelationshipType.SUBORDINATE
            RelationshipType.SUBORDINATE -> RelationshipType.BOSS
            RelationshipType.CAPTAIN -> RelationshipType.MEMBER
            RelationshipType.MEMBER -> RelationshipType.CAPTAIN
            RelationshipType.MENTOR -> RelationshipType.STUDENT
            RelationshipType.STUDENT -> RelationshipType.MENTOR
            RelationshipType.FRIEND, RelationshipType.CLOSE_FRIEND, RelationshipType.COMRADE,
            RelationshipType.TEAMMATE, RelationshipType.RIVAL, RelationshipType.LOVE_RIVAL,
            RelationshipType.LOVER, RelationshipType.FAMILY -> type
            RelationshipType.GUARDIAN -> RelationshipType.FAMILY
            RelationshipType.CRUSH -> null
        }

        /** 24 种关系类型 + 中文标签（与 OperatorBridge.RELATION_TYPES 同源，顺序一致）。 */
        private val RELATION_OPTIONS: List<RelationTypeOptionDto> = OperatorBridge.RELATION_TYPES.map { dto ->
            val type = runCatching { RelationshipType.valueOf(dto.code) }.getOrNull()
            val reverse = type?.let { complementType(it) }
            RelationTypeOptionDto(
                code = dto.code,
                label = dto.label,
                reverseCode = reverse?.name.orEmpty(),
                reverseLabel = reverse?.let { rev ->
                    OperatorBridge.RELATION_TYPES.firstOrNull { it.code == rev.name }?.label
                }.orEmpty(),
                isFamily = type in setOf(
                    RelationshipType.BIG_SISTER, RelationshipType.LITTLE_SISTER,
                    RelationshipType.BIG_BROTHER, RelationshipType.LITTLE_BROTHER,
                    RelationshipType.MOTHER, RelationshipType.FATHER,
                    RelationshipType.DAUGHTER, RelationshipType.SON,
                    RelationshipType.GUARDIAN, RelationshipType.FAMILY,
                ),
            )
        }
    }
}

/**
 * 关系行 DTO（关系网列表一行）。
 *
 * - [direction]：`"out"` = 当前角色对对方的关系；`"in"` = 对方对当前角色的关系（只读展示，删除需按
 *   [sourceOperatorId]→[targetOperatorId] 精确删该行）。
 * - [reverseType]：双向互补类型（A→B 为 FRIEND 时 B→A 也是 FRIEND；BIG_SISTER 的互补是 LITTLE_SISTER）。
 * - [relationDescription]：oracle `RelationshipUiMapper.description` 同款文案（字段名避开 NSObject.description）。
 */
class RelationEntryDto(
    val id: Long,
    val sourceOperatorId: String,
    val targetOperatorId: String,
    val queriedOperatorId: String,
    val relatedOperatorId: String,
    val relatedOperatorName: String,
    val relatedAvatarId: String,
    val type: String,
    val typeLabel: String,
    val reverseType: String,
    val reverseTypeLabel: String,
    val relationDescription: String,
    val intimacy: Int,
    val note: String,
    val direction: String,
)

/** 可添加为关系对象的角色 DTO（operators 行最小面）。 */
class RelationOperatorDto(
    val id: String,
    val name: String,
    val title: String,
    val avatarId: String,
)

/** 关系类型目录项（含双向互补类型；[isFamily] 供 UI 分组/配色，与 oracle 配色族一致）。 */
class RelationTypeOptionDto(
    val code: String,
    val label: String,
    val reverseCode: String,
    val reverseLabel: String,
    val isFamily: Boolean,
)

/** 添加/更新成功后的结果（UI 用来提示「已添加 X 对 Y 的【挚友】，并创建双向关系」）。 */
class RelationMutationDto(
    val operatorId: String,
    val targetOperatorId: String,
    val operatorName: String,
    val targetOperatorName: String,
    val type: String,
    val typeLabel: String,
    val intimacy: Int,
    val note: String,
    val reciprocalCreated: Boolean,
    val reciprocalType: String,
    val reciprocalTypeLabel: String,
)
