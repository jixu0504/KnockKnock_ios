@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.db.DatabaseWrapper
import com.rhodes.privatechat.shared.domain.media.MediaRepository
import com.rhodes.privatechat.shared.domain.media.MediaSource
import com.rhodes.privatechat.shared.files.FileIo
import com.rhodes.privatechat.shared.files.MediaDirectory
import com.rhodes.privatechat.shared.files.MediaStoreIos
import com.rhodes.privatechat.shared.model.Moment
import com.rhodes.privatechat.shared.model.MomentComment
import com.rhodes.privatechat.shared.model.MomentLike
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlin.experimental.ExperimentalObjCName
import kotlin.io.encoding.Base64
import kotlin.native.ObjCName
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.Foundation.NSLog

/**
 * 动态广场桥（T50，真实 SQLite：`moments` / `moment_likes` / `moment_comments`）。
 *
 * 数据语义对齐 Android oracle：
 * - 列表 = `MomentRepository.getMomentsPaged`（`ORDER BY createdAt DESC, id DESC`），
 *   作者头像取 `operators.avatarUri`（内置头像数字串 1..147，Swift 侧解析 Bundle 资源）；
 *   点赞数/评论数以实际行数（`moment_likes`/`moment_comments`）为准——与 Android
 *   `MomentsScreen` 用 `likes.size`/`comments.size` 展示完全一致（表内计数字段同步回写）；
 * - 点赞/取消 = `MomentsViewModel.likeMoment`（operatorId="user"，operatorName=设置里的用户昵称；
 *   切换后回写 likeCount）；
 * - 评论 = `MainViewModel.commentOnMoment` 的「写评论」最小语义（operatorId="user"、isRead=true、
 *   回写 commentCount）；AI 自动评论（MomentReplyWorker / A7 延迟任务层，T22/T23）**不在本卡范围**，
 *   故此处不创建 reply_turns；
 * - 发布 = `MainViewModel.postUserMoment` 的「用户动态落库」最小语义（operatorId="user"、
 *   isUserPost=true、operatorName=用户昵称）。**T60**：发布成功后调用 [momentReplyHook]（生产 =
 *   `A7Glue.onUserMomentPublished`）调度干员自动评论/点赞（oracle `postUserMoment` :3038-3090）；
 *   hook 失败不影响发布结果。
 * - 未读 = `countUnreadMomentsAfterId(settings.lastSeenMomentId)`（新动态）+
 *   `getUnreadCommentCount(30 天, 用户昵称)`（动态评论收件箱）；打开广场后 `markSeen` 落
 *   `last_seen_moment_id`（对齐 FeaturesScreen 打开即消角标）。
 *
 * 图片（iOS 增强，Android oracle 动态为纯文本）：
 * Android `moments` 表**没有图片列**（golden/db/schema/Moments.sq）。为满足「发布可选图片」，
 * 采用内容内嵌媒体标记的约定（不改表结构/迁移，零跨端 schema 风险）：
 *   `content = "<正文>\n[图片] media/<uuid>.<ext>"`（纯图片动态时 `content = "[图片] media/..."`）。
 * 桥在出 DTO 时把标记拆成 [MomentDto.text] / [MomentDto.imageId]；Swift 侧用媒体 ID 解析本地
 * 文件渲染。限制：在 Android 端打开该动态会看到字面标记（跨端渲染不识别），见 spec/handoff。
 *
 * 纪律（同 ios-bridge-spec.md）：只导出简单类型 DTO；回调一律 Main 队列；错误经第二参数回传
 * （null = 成功）；运行期媒体 ID 形如 `media/<uuid>.<ext>`。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "MomentsBridge")
class MomentsBridge internal constructor(
    private val chatRepo: ChatRepository,
    dbWrapper: DatabaseWrapper,
    private val settingsRepo: SettingsRepository,
    private val workScope: CoroutineScope,
    private val mainScope: CoroutineScope,
    /**
     * T60：发布成功后的 AI 自动互动调度钩子（生产由 IosAppGraph 注入 `A7Glue.onUserMomentPublished`；
     * 自检临时替换为测试层调度）。null = 不触发（如单测/无 A7 接线场景）。
     */
    internal var momentReplyHook: (suspend (Long) -> Unit)? = null,
) {
    private val mediaStore = MediaStoreIos()
    private val mediaRepository = MediaRepository(mediaStore)
    private val db = dbWrapper.database

    // ==================================================================
    // 列表 / 刷新（分页）
    // ==================================================================

    /**
     * 时间线分页（offset/limit，倒序）。返回 [MomentPageDto]；[MomentPageDto.hasMore] = 本页取满
     * limit（下一页返回空即到底，与 Android `hasMoreMoments` 的「取满继续」语义一致）。
     */
    fun list(offset: Int, limit: Int, onResult: (MomentPageDto, String?) -> Unit) {
        workScope.launch {
            try {
                val safeOffset = offset.coerceAtLeast(0)
                val safeLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
                val page = MomentPageDto(
                    items = listSync(safeOffset, safeLimit),
                    offset = safeOffset,
                    limit = safeLimit,
                    hasMore = false,
                )
                val hasMore = page.items.size >= safeLimit
                mainScope.launch { onResult(MomentPageDto(page.items, safeOffset, safeLimit, hasMore), null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(MomentPageDto(emptyList(), offset, limit, false), t.message ?: "读取动态失败") }
            }
        }
    }

    // ==================================================================
    // 发布（文本 + 可选媒体 ID）
    // ==================================================================

    /**
     * 发布用户动态（isUserPost=true，operatorId="user"）。
     * [imageId] 为空 = 纯文本；非空必须是 [ingestImage] 返回的 `media/...` 媒体 ID。
     * 回调 (新动态 id, 错误)：id > 0 表示成功。
     */
    fun publish(text: String, imageId: String, onDone: (Long, String?) -> Unit) {
        workScope.launch {
            try {
                val cleanText = text.trim()
                val cleanImage = imageId.trim()
                if (cleanText.isBlank() && cleanImage.isBlank()) {
                    mainScope.launch { onDone(0L, "动态内容不能为空") }
                    return@launch
                }
                if (cleanImage.isNotBlank()) {
                    if (!cleanImage.startsWith(MediaRepository.MEDIA_ID_PREFIX)) {
                        mainScope.launch { onDone(0L, "图片未入库，请重新选择") }
                        return@launch
                    }
                    if (mediaRepository.resolve(cleanImage) == null) {
                        mainScope.launch { onDone(0L, "图片文件不存在，请重新选择") }
                        return@launch
                    }
                }
                val id = insertUserMoment(cleanText, cleanImage)
                scheduleMomentReplies(id)
                mainScope.launch { onDone(id, null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(0L, t.message ?: "发布失败") }
            }
        }
    }

    // ==================================================================
    // 点赞 / 取消点赞
    // ==================================================================

    /** 切换当前用户点赞态（幂等）。回调 (点赞结果(状态+总数), 错误)。 */
    fun toggleLike(momentId: Long, onDone: (MomentLikeResultDto, String?) -> Unit) {
        workScope.launch {
            try {
                chatRepo.getMoment(momentId) ?: throw IllegalStateException("动态不存在（可能已被删除）")
                val result = toggleLikeSync(momentId)
                mainScope.launch { onDone(result, null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(MomentLikeResultDto(false, 0), t.message ?: "点赞失败") }
            }
        }
    }

    // ==================================================================
    // 评论
    // ==================================================================

    /** 评论列表（createdAt ASC，对齐 Android `getComments`）。 */
    fun comments(momentId: Long, onResult: (List<MomentCommentDto>, String?) -> Unit) {
        workScope.launch {
            try {
                val rows = commentsSync(momentId)
                mainScope.launch { onResult(rows, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(emptyList(), t.message ?: "读取评论失败") }
            }
        }
    }

    /**
     * 发表评论（当前用户）。[parentCommentId] > 0 时为回复（回复目标名 [replyToName]）。
     * 语义 = MainViewModel.commentOnMoment 的用户评论最小面：写 moment_comments（isRead=true）+
     * 回写 commentCount；AI 自动评论（reply_turns / A7）不在本卡触发。
     * 回调 (新评论 id, 错误)。
     */
    fun addComment(
        momentId: Long,
        text: String,
        parentCommentId: Long,
        replyToName: String,
        onDone: (Long, String?) -> Unit,
    ) {
        workScope.launch {
            try {
                val cleanText = text.trim()
                if (cleanText.isBlank()) {
                    mainScope.launch { onDone(0L, "评论内容不能为空") }
                    return@launch
                }
                chatRepo.getMoment(momentId) ?: throw IllegalStateException("动态不存在（可能已被删除）")
                val parent = if (parentCommentId > 0) chatRepo.getCommentById(parentCommentId) else null
                if (parentCommentId > 0) {
                    if (parent == null) throw IllegalStateException("回复的评论不存在")
                    if (parent.momentId != momentId) throw IllegalStateException("回复目标不属于该动态")
                }
                val rootParentId = parent?.parentCommentId?.takeIf { it > 0 } ?: parentCommentId
                val id = insertUserComment(momentId, cleanText, rootParentId, replyToName.trim())
                mainScope.launch { onDone(id, null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(0L, t.message ?: "评论失败") }
            }
        }
    }

    // ==================================================================
    // 删除（仅本人动态）
    // ==================================================================

    /** 删除本人动态（级联点赞/评论）。非本人动态拒绝；成功回调 null。 */
    fun delete(momentId: Long, onDone: (String?) -> Unit) {
        workScope.launch {
            try {
                val error = deleteSync(momentId)
                mainScope.launch { onDone(error) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(t.message ?: "删除失败") }
            }
        }
    }

    // ==================================================================
    // 未读计数 / 已读标记
    // ==================================================================

    /**
     * 未读计数：(新动态数, 未读评论数)。
     * - 新动态 = `last_seen_moment_id` 之后的非用户动态（对齐 MomentsViewModel.getMomentBadgeSuspend）；
     * - 未读评论 = 30 天内 AI 对「我的动态/我的评论回复」的未读评论（对齐 getUnreadCommentCountSuspend）。
     */
    fun badges(onResult: (MomentBadgeDto, String?) -> Unit) {
        workScope.launch {
            try {
                val result = badgesSync()
                mainScope.launch { onResult(result, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(MomentBadgeDto(0, 0), t.message ?: "读取未读失败") }
            }
        }
    }

    /** 打开动态广场：把 `last_seen_moment_id` 推进到当前最大 id（只清「新动态」角标）。 */
    fun markMomentsSeen(onDone: (String?) -> Unit) {
        workScope.launch {
            try {
                val maxId = chatRepo.getMaxMomentId()
                if (maxId > 0) settingsRepo.lastSeenMomentId = maxId
                mainScope.launch { onDone(null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(t.message ?: "标记已读失败") }
            }
        }
    }

    /** 评论收件箱全部标记已读（`last_seen_comment_id` 推进；对齐 markAllCommentsRead）。 */
    fun markCommentsRead(onDone: (String?) -> Unit) {
        workScope.launch {
            try {
                chatRepo.markAllCommentsRead(settingsRepo.userName.ifBlank { "绳匠" })
                val maxId = chatRepo.getMaxCommentId()
                if (maxId != null && maxId > 0) settingsRepo.lastSeenCommentId = maxId
                mainScope.launch { onDone(null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(t.message ?: "标记评论已读失败") }
            }
        }
    }

    // ==================================================================
    // 图片（发布器 PhotosPicker → 媒体入库 → 发布带图动态）
    // ==================================================================

    /** 本地图片文件 → 媒体 ID（`media/<uuid>.<ext>`，落 Application Support/media/images）。 */
    fun ingestImage(sourcePath: String, onDone: (String, String?) -> Unit) {
        workScope.launch {
            try {
                if (sourcePath.isBlank()) {
                    mainScope.launch { onDone("", "图片路径为空") }
                    return@launch
                }
                val mediaId = mediaRepository.ingest(MediaSource.PICKER, sourcePath)
                if (mediaId == sourcePath || !mediaId.startsWith(MediaRepository.MEDIA_ID_PREFIX)) {
                    mainScope.launch { onDone("", "图片保存失败（源文件不可读或写入失败）") }
                } else {
                    mainScope.launch { onDone(mediaId, null) }
                }
            } catch (t: Throwable) {
                mainScope.launch { onDone("", t.message ?: "图片保存失败") }
            }
        }
    }

    /** 媒体 ID → 本机绝对文件路径（渲染用；http(s) 原样透传，缺失返回 ""）。 */
    fun imagePath(imageId: String): String {
        if (imageId.isBlank()) return ""
        if (imageId.startsWith("http://") || imageId.startsWith("https://")) return imageId
        val direct = imageId.removePrefix("file://")
        if (direct.startsWith("/") && FileIo.exists(direct)) return direct
        return mediaRepository.resolve(imageId)?.removePrefix("file://").orEmpty()
    }

    /** 当前用户昵称/头像（列表头部/发布器展示用；同步读 settings）。 */
    fun userName(): String = settingsRepo.userName.ifBlank { "绳匠" }

    fun userAvatarId(): String = settingsRepo.userAvatarUri

    // ==================================================================
    // 自检（仅 `--moments-selftest`，由 IosAppGraph 启动时触发；不参与用户路径）
    // ==================================================================

    /**
     * T50 端到端自检：发布（文本+图片）→ 列表出现 → 点赞（计数+1/再点取消）→
     * 评论（列表出现/计数+1）→ 未读角标（新动态提醒 + 已读清零）→ 删除（列表消失 + DB 级联）
     * → 删除非本人动态被拒 → 空内容被拒 → 留下 1 条截图夹具（t50 fixture，可用
     * `--moments-fixture-clean` 清理）。结果写 Application Support/t50-moments-selftest.log。
     */
    fun debugRunSelfTest(onDone: (String) -> Unit) {
        workScope.launch {
            val lines = mutableListOf<String>()
            var failures = 0
            fun check(name: String, ok: Boolean, detail: String = "") {
                if (!ok) failures++
                lines += "${if (ok) "PASS" else "FAIL"} $name" + if (detail.isBlank()) "" else " | $detail"
            }
            fun report() {
                lines += "RESULT ${if (failures == 0) "PASS" else "FAIL($failures)"}"
                val text = lines.joinToString("\n")
                NSLog(("[RhodesLink][T50SELFTEST]\n" + text).replace("%", "%%"))
                val path = "${MediaDirectory.applicationSupportDirectory()}/t50-moments-selftest.log"
                runCatching { FileIo.writeBytes(path, (text + "\n").encodeToByteArray()) }
                    .onFailure { NSLog("[RhodesLink][T50SELFTEST] report write failed: " + (it.message ?: "")) }
                mainScope.launch { runCatching { onDone(text) } }
            }

            val userName = settingsRepo.userName.ifBlank { "绳匠" }
            var createdMomentId = 0L
            var createdImageId = ""
            try {
                lines += "=== T50 moments selftest ==="
                lines += "owner=$userName avatar=${settingsRepo.userAvatarUri.ifBlank { "<empty>" }}"

                // 0. 清理历史 T50 夹具（幂等）
                lines += "fixture.cleanup removed=${cleanFixturesSync()}"

                val baselineMax = chatRepo.getMaxMomentId()
                lines += "baselineMaxMomentId=$baselineMax"

                // 1. 图片入库 + 发布带图动态
                val imagePath = writeSelfTestImage()
                var ingestError = ""
                val imageId = runCatching {
                    mediaRepository.ingest(MediaSource.PICKER, imagePath)
                }.onFailure { ingestError = it.message ?: "ingest 异常" }.getOrDefault("")
                createdImageId = imageId
                check("image.ingest", imageId.startsWith(MediaRepository.MEDIA_ID_PREFIX), "id=$imageId err=$ingestError")
                check("image.resolve", mediaRepository.resolve(imageId) != null, "path=${mediaRepository.resolve(imageId) ?: "<null>"}")

                val token = "T50自检" + Clock.System.now().toEpochMilliseconds()
                val text = "$token：今天和干员们一起完成了训练，记录一下。"
                createdMomentId = runCatching { insertUserMoment(text, imageId) }.getOrDefault(0L)
                check("publish.ok", createdMomentId > 0L, "id=$createdMomentId")
                check("publish.newId", createdMomentId > baselineMax, "baselineMax=$baselineMax id=$createdMomentId")

                // 2. 列表出现（文本/图片/作者/计数）
                val page = listSync(0, 20)
                val row = page.firstOrNull { it.id == createdMomentId }
                check("list.contains", row != null, "pageSize=${page.size}")
                check("list.text", row?.text == text, "text=${row?.text ?: "-"}")
                check("list.image", row?.imageId == imageId, "imageId=${row?.imageId ?: "-"}")
                check("list.author", row?.authorName == userName && row?.isMine == true && row?.isUserPost == true, "author=${row?.authorName} mine=${row?.isMine}")
                check("list.countsInit", row?.likeCount == 0 && row?.commentCount == 0, "like=${row?.likeCount} comment=${row?.commentCount}")

                // 3. 点赞（计数+1）→ 取消（回 0）→ 再点赞（夹具态）
                val liked1 = toggleLikeSync(createdMomentId)
                check("like.on", liked1.liked && liked1.likeCount == 1, "liked=${liked1.liked} count=${liked1.likeCount}")
                val liked2 = toggleLikeSync(createdMomentId)
                check("like.off", !liked2.liked && liked2.likeCount == 0, "liked=${liked2.liked} count=${liked2.likeCount}")
                val liked3 = toggleLikeSync(createdMomentId)
                check("like.onAgain", liked3.liked && liked3.likeCount == 1, "liked=${liked3.liked} count=${liked3.likeCount}")
                check("like.db", chatRepo.getLikeCount(createdMomentId) == 1, "dbCount=${chatRepo.getLikeCount(createdMomentId)}")

                // 4. 评论（列表出现 + 计数+1）
                val commentText = "$token 评论：图片很清晰。"
                val commentId = insertUserComment(createdMomentId, commentText, 0L, "")
                val comments = commentsSync(createdMomentId)
                check("comment.ok", commentId > 0L, "id=$commentId")
                check("comment.list", comments.any { it.id == commentId && it.text == commentText }, "count=${comments.size}")
                check("comment.db", chatRepo.getCommentCount(createdMomentId) == 1, "dbCommentCount=${chatRepo.getCommentCount(createdMomentId)}")
                check("comment.mine", comments.firstOrNull { it.id == commentId }?.isMine == true)
                val afterMutate = listSync(0, 20).firstOrNull { it.id == createdMomentId }
                check("list.countsAfter", afterMutate?.likeCount == 1 && afterMutate?.commentCount == 1, "like=${afterMutate?.likeCount} comment=${afterMutate?.commentCount}")

                // 5. 未读角标：造一条干员动态 → 新动态数 ≥1 → markSeen 清零
                val operator = chatRepo.getAllOperatorsSync().firstOrNull()
                if (operator != null) {
                    val aiMomentId = chatRepo.insertMoment(
                        Moment(
                            operatorId = operator.id,
                            operatorName = operator.name,
                            content = "T50自检干员动态：训练结束了，博士辛苦。",
                            isUserPost = false,
                            createdAt = Clock.System.now().toEpochMilliseconds() + 1,
                        ),
                    )
                    settingsRepo.lastSeenMomentId = createdMomentId
                    val badgeBefore = badgesSync()
                    check("badge.newMoment", badgeBefore.unreadMoments >= 1, "unreadMoments=${badgeBefore.unreadMoments}")
                    val maxId = chatRepo.getMaxMomentId()
                    settingsRepo.lastSeenMomentId = maxId
                    val badgeAfter = badgesSync()
                    check("badge.markSeen", badgeAfter.unreadMoments == 0, "after=${badgeAfter.unreadMoments}")
                    // 6. 删除非本人动态被拒（以刚造的干员动态为目标；随后自检内部直接清理）
                    val rejectOther = deleteSync(aiMomentId) != null
                    check("delete.rejectOther", rejectOther, "momentId=$aiMomentId operator=${operator.name}")
                    check("delete.rejectOtherKept", chatRepo.getMoment(aiMomentId) != null, "被拒后动态仍在")
                    chatRepo.deleteMoment(aiMomentId)
                } else {
                    check("badge.newMoment", false, "operators 为空，跳过")
                }

                // 7. 空内容发布被拒
                check("publish.rejectEmpty", (runCatching { insertUserMoment("   ", "") }.exceptionOrNull()?.message ?: "").isNotBlank())

                // 8. 删除本人动态：列表消失 + DB 级联
                val tempId = insertUserMoment("$token 待删除动态", "")
                check("delete.tempCreated", tempId > 0L, "id=$tempId")
                toggleLikeSync(tempId)
                insertUserComment(tempId, "$token 删除前评论", 0L, "")
                val deleteError = deleteSync(tempId)
                check("delete.ok", deleteError == null, "err=${deleteError ?: "-"}")
                check("delete.gone", listSync(0, 50).none { it.id == tempId }, "id=$tempId")
                check("delete.dbRow", chatRepo.getMoment(tempId) == null)
                check(
                    "delete.dbCascade",
                    chatRepo.getLikeCount(tempId) == 0 && chatRepo.getCommentCount(tempId) == 0,
                    "likes=${chatRepo.getLikeCount(tempId)} comments=${chatRepo.getCommentCount(tempId)}",
                )

                // 9. 截图夹具：保留 1 条本人动态（文本+图片）+ 1 条干员点赞/评论（供 UI 截图）
                val fixtureOperator = chatRepo.getAllOperatorsSync().firstOrNull()
                if (createdMomentId > 0L && fixtureOperator != null) {
                    chatRepo.insertLike(
                        MomentLike(
                            momentId = createdMomentId,
                            operatorId = fixtureOperator.id,
                            operatorName = fixtureOperator.name,
                            createdAt = Clock.System.now().toEpochMilliseconds(),
                        ),
                    )
                    chatRepo.updateLikeCount(createdMomentId, chatRepo.getLikeCount(createdMomentId))
                    chatRepo.insertComment(
                        MomentComment(
                            momentId = createdMomentId,
                            operatorId = fixtureOperator.id,
                            operatorName = fixtureOperator.name,
                            content = "博士拍得真好，训练场今天的风也很舒服。",
                            createdAt = Clock.System.now().toEpochMilliseconds(),
                            isRead = false,
                        ),
                    )
                    chatRepo.updateCommentCount(createdMomentId, chatRepo.getCommentCount(createdMomentId))
                    lines += "fixture.kept momentId=$createdMomentId image=$createdImageId (UI 截图数据；--moments-fixture-clean 清理)"
                }
            } catch (t: Throwable) {
                lines += "FATAL ${t.message ?: t::class.simpleName}"
                failures++
            }
            report()
        }
    }

    /** 清理 T50 自检/截图夹具（内容含 T50 标记的用户动态）。回调 (删除条数, 错误)。 */
    fun debugCleanFixtures(onDone: (Int, String?) -> Unit) {
        workScope.launch {
            try {
                val removed = cleanFixturesSync()
                mainScope.launch { onDone(removed, null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(0, t.message ?: "清理失败") }
            }
        }
    }

    // ==================================================================
    // 内部同步实现（回调方法内的 workScope 协程 / 自检直调；均挂起安全）
    // ==================================================================

    private fun isMine(moment: Moment): Boolean =
        moment.isUserPost || moment.operatorId == USER_OPERATOR_ID

    private suspend fun insertUserMoment(text: String, imageId: String): Long {
        val cleanText = text.trim()
        val cleanImage = imageId.trim()
        if (cleanText.isBlank() && cleanImage.isBlank()) throw IllegalStateException("动态内容不能为空")
        if (cleanImage.isNotBlank() && !cleanImage.startsWith(MediaRepository.MEDIA_ID_PREFIX)) {
            throw IllegalStateException("图片未入库，请重新选择")
        }
        if (cleanImage.isNotBlank() && mediaRepository.resolve(cleanImage) == null) {
            throw IllegalStateException("图片文件不存在，请重新选择")
        }
        return chatRepo.insertMoment(
            Moment(
                operatorId = USER_OPERATOR_ID,
                operatorName = settingsRepo.userName.ifBlank { "绳匠" },
                content = encodeContent(cleanText, cleanImage),
                isUserPost = true,
                createdAt = Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }

    /** T60：发布成功后触发 AI 自动互动调度（失败只记日志，不影响发布结果）。 */
    private suspend fun scheduleMomentReplies(momentId: Long) {
        val hook = momentReplyHook ?: return
        runCatching { hook(momentId) }
            .onFailure { NSLog("[RhodesLink][T60] publish schedule failed: " + (it.message ?: "")) }
    }

    /** T60 自检：走与 [publish] 相同的「落库 + 调度 hook」路径（跳过图片校验），返回新动态 id。 */
    internal suspend fun publishForSelfTest(text: String): Long {
        val cleanText = text.trim()
        if (cleanText.isBlank()) throw IllegalStateException("动态内容不能为空")
        val id = insertUserMoment(cleanText, "")
        scheduleMomentReplies(id)
        return id
    }

    /** T60 自检：读取单条动态的 UI 列表 DTO（与 [list] 同口径，验证评论/点赞数刷新）。 */
    internal suspend fun debugMomentRow(momentId: Long): MomentDto? =
        listSync(0, MAX_PAGE_SIZE).firstOrNull { it.id == momentId }

    private suspend fun insertUserComment(momentId: Long, text: String, parentCommentId: Long, replyToName: String): Long {
        val id = chatRepo.insertComment(
            MomentComment(
                momentId = momentId,
                operatorId = USER_OPERATOR_ID,
                operatorName = settingsRepo.userName.ifBlank { "绳匠" },
                content = text,
                parentCommentId = parentCommentId,
                replyToName = replyToName,
                createdAt = Clock.System.now().toEpochMilliseconds(),
                isRead = true,
            ),
        )
        chatRepo.updateCommentCount(momentId, chatRepo.getCommentCount(momentId))
        return id
    }

    private suspend fun toggleLikeSync(momentId: Long): MomentLikeResultDto {
        val existing = chatRepo.getLike(momentId, USER_OPERATOR_ID)
        if (existing == null) {
            chatRepo.insertLike(
                MomentLike(
                    momentId = momentId,
                    operatorId = USER_OPERATOR_ID,
                    operatorName = settingsRepo.userName.ifBlank { "绳匠" },
                    createdAt = Clock.System.now().toEpochMilliseconds(),
                ),
            )
        } else {
            chatRepo.deleteLike(momentId, USER_OPERATOR_ID)
        }
        val count = chatRepo.getLikeCount(momentId)
        chatRepo.updateLikeCount(momentId, count)
        return MomentLikeResultDto(chatRepo.getLike(momentId, USER_OPERATOR_ID) != null, count)
    }

    private suspend fun deleteSync(momentId: Long): String? {
        val moment = chatRepo.getMoment(momentId) ?: return "动态不存在（可能已被删除）"
        if (!isMine(moment)) return "只能删除自己发布的动态"
        chatRepo.deleteMoment(momentId)
        return null
    }

    private suspend fun commentsSync(momentId: Long): List<MomentCommentDto> =
        db.momentCommentsQueries.getComments(momentId) { id, mId, opId, opName, content, parentId, replyToName, createdAt, _ ->
            MomentCommentDto(
                id = id,
                momentId = mId,
                operatorId = opId,
                authorName = opName,
                text = content,
                parentCommentId = parentId,
                replyToName = replyToName,
                createdAt = createdAt,
                isMine = opId == USER_OPERATOR_ID,
            )
        }.executeAsList()

    private suspend fun badgesSync(): MomentBadgeDto {
        val unreadMoments = chatRepo.countUnreadMomentsAfterId(settingsRepo.lastSeenMomentId)
        val userName = settingsRepo.userName.ifBlank { "绳匠" }
        val cutoff = Clock.System.now().toEpochMilliseconds() - COMMENT_INBOX_WINDOW_MS
        val unreadComments = runCatching { chatRepo.getUnreadCommentCount(cutoff, userName) }.getOrDefault(0)
        return MomentBadgeDto(unreadMoments, unreadComments)
    }

    private suspend fun listSync(offset: Int, limit: Int): List<MomentDto> {
        val moments = chatRepo.getMomentsPaged(limit, offset)
        val operators = runCatching { chatRepo.getAllOperatorsSync() }.getOrDefault(emptyList())
        val byId = operators.associateBy { it.id }
        val byName = operators.associateBy { it.name }
        val userName = settingsRepo.userName.ifBlank { "绳匠" }
        return moments.map { moment ->
            val (text, imageId) = splitContent(moment.content)
            val mine = isMine(moment)
            MomentDto(
                id = moment.id,
                operatorId = moment.operatorId,
                authorName = if (mine) userName else moment.operatorName,
                authorAvatarId = if (mine) {
                    settingsRepo.userAvatarUri
                } else {
                    byId[moment.operatorId]?.avatarUri ?: byName[moment.operatorName]?.avatarUri ?: ""
                },
                text = text,
                imageId = imageId,
                isUserPost = moment.isUserPost,
                isMine = mine,
                likeCount = chatRepo.getLikeCount(moment.id),
                commentCount = chatRepo.getCommentCount(moment.id),
                isLiked = chatRepo.getLike(moment.id, USER_OPERATOR_ID) != null,
                createdAt = moment.createdAt,
            )
        }
    }

    /** 删除内容带 T50 标记的用户动态（含级联）；返回删除条数。 */
    private suspend fun cleanFixturesSync(): Int {
        val mine = chatRepo.moments.getMomentsByOperator(USER_OPERATOR_ID)
        var removed = 0
        mine.forEach { moment ->
            if (moment.content.contains("T50")) {
                chatRepo.deleteMoment(moment.id)
                removed++
            }
        }
        return removed
    }

    /** 自检用 96x96 PNG（有效 PNG 字节见 companion，Swift 可正常渲染）。 */
    private fun writeSelfTestImage(): String {
        val path = "${MediaDirectory.applicationSupportDirectory()}/t50-selftest-image.png"
        FileIo.writeBytes(path, SELFTEST_PNG)
        return path
    }

    companion object {
        private const val USER_OPERATOR_ID = "user"
        private const val MAX_PAGE_SIZE = 50
        private const val COMMENT_INBOX_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        private const val IMAGE_MARKER = "\n[图片] "
        private const val IMAGE_MARKER_START = "[图片] "

        /** 内容编码：正文 + 可选 `\n[图片] <mediaId>`（见类注释）。 */
        internal fun encodeContent(text: String, imageId: String): String {
            val cleanText = text.trim()
            val cleanImage = imageId.trim()
            if (cleanImage.isEmpty()) return cleanText
            return if (cleanText.isEmpty()) "$IMAGE_MARKER_START$cleanImage" else "$cleanText$IMAGE_MARKER$cleanImage"
        }

        /** 内容解码：返回 (正文, 媒体 ID)；非法标记原样保留在正文中。 */
        internal fun splitContent(raw: String): Pair<String, String> {
            if (raw.startsWith(IMAGE_MARKER_START)) {
                val id = raw.removePrefix(IMAGE_MARKER_START).trim()
                if (isMediaIdLike(id)) return "" to id
            }
            val index = raw.lastIndexOf(IMAGE_MARKER)
            if (index >= 0) {
                val id = raw.substring(index + IMAGE_MARKER.length).trim()
                if (isMediaIdLike(id)) return raw.substring(0, index) to id
            }
            return raw to ""
        }

        private fun isMediaIdLike(id: String): Boolean =
            id.startsWith(MediaRepository.MEDIA_ID_PREFIX) && !id.contains(' ') && id.length in 8..200

        /** 自检用 96x96 渐变 PNG（有效 PNG：IHDR/IDAT/IEND CRC 正确，Swift UIImage 可渲染）。 */
        private val SELFTEST_PNG: ByteArray = Base64.decode(
            "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",
        )
    }
}

// ---------------------------------------------------------------------------
// T50 DTO（纪律：只用简单类型；普通 class 非 data class）
// ---------------------------------------------------------------------------

/** 动态行 DTO（列表/时间线）。 */
class MomentDto(
    val id: Long,
    val operatorId: String,
    /** 展示名：用户动态 = 设置昵称，干员动态 = moments.operatorName。 */
    val authorName: String,
    /** operators.avatarUri 原样（"1".."147" = 内置 Bundle 头像）；用户动态 = user_avatar_uri。 */
    val authorAvatarId: String,
    /** 正文（已剥离图片标记）。 */
    val text: String,
    /** 图片媒体 ID（`media/<uuid>.<ext>`）；纯文本动态为 ""。 */
    val imageId: String,
    val isUserPost: Boolean,
    val isMine: Boolean,
    val likeCount: Int,
    val commentCount: Int,
    /** 当前用户（operatorId="user"）是否已点赞。 */
    val isLiked: Boolean,
    val createdAt: Long,
)

/** 动态分页 DTO。 */
class MomentPageDto(
    val items: List<MomentDto>,
    val offset: Int,
    val limit: Int,
    /** 本页取满 → 可能还有下一页。 */
    val hasMore: Boolean,
)

/** 评论 DTO（列表顺序 createdAt ASC）。 */
class MomentCommentDto(
    val id: Long,
    val momentId: Long,
    val operatorId: String,
    val authorName: String,
    val text: String,
    val parentCommentId: Long,
    val replyToName: String,
    val createdAt: Long,
    val isMine: Boolean,
)

/** 点赞切换结果。 */
class MomentLikeResultDto(
    val liked: Boolean,
    val likeCount: Int,
)

/** 未读计数：新动态数 + 未读评论数。 */
class MomentBadgeDto(
    val unreadMoments: Int,
    val unreadComments: Int,
)
