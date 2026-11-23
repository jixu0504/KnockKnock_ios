package com.rhodes.privatechat.shared.domain.data

import com.rhodes.privatechat.shared.backup.BackupRestoreCoordinator
import com.rhodes.privatechat.shared.backup.GoldenBackupFixture
import com.rhodes.privatechat.shared.backup.InMemoryMediaSink
import com.rhodes.privatechat.shared.backup.TestBackupRepository
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 规格卡 message-id-media-spec.md 行为契约 A.1-A.11 + 验收 #3-#7。
 * 同一份 commonTest 跑两条路径：:shared:testDebugUnitTest（JVM JdbcSqliteDriver 内存库）与
 * :shared:iosSimulatorArm64Test（NativeSqliteDriver，iPhone 17 模拟器）。
 * 每条用例注明 oracle 位置与验收项编号。
 */
abstract class MessageIdAllocatorTest {

    /** 平台源集提供：androidUnitTest = JdbcSqliteDriver + InMemoryObservableSettings；iosTest = NativeSqliteDriver + NSUserDefaults suite。 */
    protected abstract fun createEnv(): TestAllocatorEnv

    private fun allocatorFor(db: RhodesDatabase, settings: SettingsRepository?) =
        MessageIdAllocator(DatabaseMessageMaxIdSource(db), settings)

    /** 原样插 id（INSERT OR IGNORE），等价恢复执行器路径。oracle：ChatMessages.sq:99-101。 */
    private fun insertRaw(db: RhodesDatabase, id: Long) {
        db.chatMessagesQueries.insertMessageIfAbsent(
            id, "session_test", "op", "测试", "content_$id", "text", "online",
            "", "", "", "", "", 0L, id * 1000L, 0L,
        )
    }

    // --- 契约 6 / 验收：首 id 与空库 ---
    @Test
    fun firstIdIsOneOnFreshDb_andStrictlyIncreasingOnReentry() = runBlocking {
        val env = createEnv()
        try {
            val allocator = allocatorFor(env.database, env.settings)
            // fresh 冷开：DB 无行（MAX NULL → 1）、persisted=0、内存 null → 首个 id = 1（oracle :298-300 + ChatMessages.sq:40-41）
            assertEquals(1L, allocator.allocate())
            // 同一协程连续调用得连续 id（重入语义）
            assertEquals(2L, allocator.allocate())
            assertEquals(3L, allocator.allocate())
        } finally {
            env.close()
        }
    }

    // --- 验收 #3 / 契约 7：并发 1000 次唯一且严格递增 ---
    @Test
    fun concurrent1000_allocationsAreUniqueAndStrictlyIncreasing() = runBlocking {
        val env = createEnv()
        try {
            val allocator = allocatorFor(env.database, env.settings)
            val ids = mutableListOf<Long>()
            val resultMutex = Mutex()
            coroutineScope {
                repeat(1000) {
                    launch(Dispatchers.Default) {
                        val id = allocator.allocate()
                        resultMutex.withLock { ids += id }
                    }
                }
            }
            assertEquals(1000, ids.size)
            assertEquals(1000, ids.toSet().size, "并发分配不得重复")
            val sorted = ids.sorted()
            assertEquals(ids.minOrNull()!!, sorted.first())
            assertEquals(999L, sorted.last() - sorted.first(), "1000 次分配必须严格递增（max-min=999）")
        } finally {
            env.close()
        }
    }

    // --- 验收 #4 / 契约 5：删除不回退 ---
    @Test
    fun noRewindAfterDelete_withPersistedRetainedAcrossInstances() = runBlocking {
        val env = createEnv()
        try {
            val first = allocatorFor(env.database, env.settings)
            // 模拟发送：allocate 后原样插入，id 1..5 落库，persisted 随 allocate 到 5（oracle :302）
            repeat(5) { n ->
                val id = first.allocate()
                assertEquals((n + 1).toLong(), id)
                insertRaw(env.database, id)
            }
            // 删除最新行（oracle :318-324 deleteMessage：display_events + reply_turns + message，不动 persisted）
            env.database.chatMessagesQueries.deleteMessage(5L)
            // 模拟进程重启：新实例（内存 null）、同 settings 键、同 DB（MAX 已回退为 4）
            val restarted = allocatorFor(env.database, env.settings)
            // persisted=5 → fromPersisted=6 胜出（oracle :285-289 doc comment 的防回退语义）
            assertEquals(6L, restarted.allocate())
        } finally {
            env.close()
        }
    }

    // --- 验收 #5 / 契约 8：恢复导入后自愈 ---
    @Test
    fun restoreSelfHeal_coldRestartAndHotRestore() = runBlocking {
        val env = createEnv()
        try {
            // 场景 A（冷启动恢复）：恢复执行器原样插入备份行 {1..5,100,999}，persisted=0、内存 null
            listOf(1L, 2L, 3L, 4L, 5L, 100L, 999L).forEach { insertRaw(env.database, it) }
            val cold = allocatorFor(env.database, env.settings)
            assertEquals(1000L, cold.allocate(), "冷启动恢复后必须取三源 max（DB MAX+1）不撞备份 id")

            // 场景 B（同进程热恢复）：内存高水位残留 5000，恢复后 DB max=999 → 内存方向胜出（安全方向，绝不撞 id）
            val hot = allocatorFor(env.database, env.settings)
            hot.observeUsed(5000L)
            assertEquals(5001L, hot.allocate(), "热恢复后内存高水位必须覆盖 DB max（不撞 id）")
        } finally {
            env.close()
        }
    }

    // --- 验收 #6 / 契约 3：observeUsed 抬高 ---
    @Test
    fun observeUsed_raisesWatermarkSoSubsequentAllocationsExceedIt() = runBlocking {
        val env = createEnv()
        try {
            val allocator = allocatorFor(env.database, env.settings)
            allocator.observeUsed(42L)
            val a = allocator.allocate()
            val b = allocator.allocate()
            assertTrue(a > 42L, "observeUsed(42) 后 allocate 必须 > 42，实际 $a")
            assertTrue(b > a, "连续 allocate 严格递增")
        } finally {
            env.close()
        }
    }

    // --- 契约 5 边界：settings 不可用（null）→ persisted 分量取 0，退化为 max(内存, DB+1)（oracle :290） ---
    @Test
    fun settingsNull_degradedModeAllowsRewindAfterDelete() = runBlocking {
        val env = createEnv()
        try {
            listOf(1L, 2L, 3L).forEach { insertRaw(env.database, it) }
            val first = allocatorFor(env.database, settings = null)
            assertEquals(4L, first.allocate())
            // 删除最新行后新实例（settings=null、persisted 恒 0）→ DB MAX(2)+1=3，与 Android settings=null 行为一致
            env.database.chatMessagesQueries.deleteMessage(3L)
            val restarted = allocatorFor(env.database, settings = null)
            assertEquals(3L, restarted.allocate(), "settings=null 时退化公式 max(内存, DB+1)（oracle :290 语义）")
        } finally {
            env.close()
        }
    }

    // --- 契约 1 边界：DB 查询失败向上抛（oracle :297-304 无 catch，无兜底） ---
    @Test
    fun dbQueryFailure_propagatesFromAllocate() {
        runBlocking {
            val failingSource = MessageMaxIdSource { throw IllegalStateException("DB 损坏") }
            val allocator = MessageIdAllocator(failingSource, settings = null)
            assertFailsWith<IllegalStateException> { allocator.allocate() }
        }
    }

    // --- 验收 #7 / 契约 9：恢复金样包 → 新消息不撞 ID ---
    @Test
    fun goldenRestore_thenAllocateDoesNotCollideWithBackupIds() = runBlocking {
        val env = createEnv()
        try {
            val coordinator = BackupRestoreCoordinator(TestBackupRepository(env.database), env.settings)
            val result = coordinator.restore(GoldenBackupFixture.zipBytes, InMemoryMediaSink())
            assertTrue(result.success, "金样恢复失败: ${result.reason}")
            val maxId = env.database.chatMessagesQueries.getMaxId().executeAsOne().MAX ?: 0L
            assertEquals(7L, maxId, "金样 7 条消息最大 id=7")
            val allocator = allocatorFor(env.database, env.settings)
            assertEquals(8L, allocator.allocate(), "恢复后新消息不得与金样 id 撞车")
        } finally {
            env.close()
        }
    }

    // --- T27 等价性（MessageRepository.getNextMessageId 已改为委托 MessageIdAllocator）：
    // 同一操作序列分别驱动「oracle 原内联公式（MessageRepository.kt:296-305）」与 MessageIdAllocator，
    // 逐项分配结果、最终 `max_used_message_id` 持久化水位、以及跨实例（冷启动）分配必须完全一致。
    // oracle 发送路径（:168/:194/:225）只抬内存、恢复路径（:243-244）才持久化；allocator.observeUsed 两者同时做
    // （T17 handoff §3.1 已记录的唯一保守差异，方向只增不减，不改变任何 allocate 结果）。
    @Test
    fun allocatorMatchesOracleInlineFormula_onSameOperationSequence() = runBlocking {
        val allocatorEnv = createEnv()
        val oracleEnv = createEnv()
        try {
            val allocator = allocatorFor(allocatorEnv.database, allocatorEnv.settings)
            val oracle = OracleMessageIdFormula(oracleEnv)
            val script = listOf(
                IdOp.Allocate, IdOp.Allocate, IdOp.Insert(1), IdOp.Allocate, IdOp.Observe(7),
                IdOp.Allocate, IdOp.Delete(1), IdOp.Insert(2), IdOp.Allocate, IdOp.Allocate,
                IdOp.Insert(100), IdOp.Allocate, IdOp.Delete(100), IdOp.Allocate, IdOp.Observe(3),
                IdOp.Insert(4), IdOp.Delete(4), IdOp.Allocate, IdOp.Insert(9), IdOp.Allocate,
                IdOp.Observe(20), IdOp.Insert(2), IdOp.Allocate, IdOp.Allocate,
            )
            script.forEachIndexed { index, op ->
                when (op) {
                    is IdOp.Allocate -> assertEquals(
                        oracle.allocate(), allocator.allocate(),
                        "第 $index 步 allocate：两种实现必须同序列同结果",
                    )
                    is IdOp.Insert -> {
                        insertRaw(allocatorEnv.database, op.id)
                        insertRaw(oracleEnv.database, op.id)
                    }
                    is IdOp.Delete -> {
                        allocatorEnv.database.chatMessagesQueries.deleteMessage(op.id)
                        oracleEnv.database.chatMessagesQueries.deleteMessage(op.id)
                    }
                    is IdOp.Observe -> {
                        allocator.observeUsed(op.id)
                        oracle.observeUsed(op.id)
                    }
                }
            }
            assertEquals(
                oracleEnv.settings.getLong(MessageIdAllocator.KEY_MAX_USED_MESSAGE_ID, 0L),
                allocatorEnv.settings.getLong(MessageIdAllocator.KEY_MAX_USED_MESSAGE_ID, 0L),
                "序列结束后 persisted 高水位必须一致",
            )
            // 模拟进程重启：两个实现的新实例各自从同一 DB + persisted 冷启动，首分配必须一致
            assertEquals(
                OracleMessageIdFormula(oracleEnv).allocate(),
                allocatorFor(allocatorEnv.database, allocatorEnv.settings).allocate(),
                "冷启动首分配必须与 oracle 内联公式一致",
            )
        } finally {
            allocatorEnv.close()
            oracleEnv.close()
        }
    }

    /** oracle MessageRepository.kt:296-305 内联公式的逐行参照实现（仅测试用，不进入生产代码）。 */
    private class OracleMessageIdFormula(private val env: TestAllocatorEnv) {
        private var nextMessageId: Long? = null

        fun allocate(): Long {
            val fromDatabase = (env.database.chatMessagesQueries.getMaxId().executeAsOne().MAX ?: 0L) + 1
            val fromPersisted = persisted() + 1
            val next = maxOf(nextMessageId ?: 0L, fromDatabase, fromPersisted)
            nextMessageId = next + 1
            remember(next)
            return next
        }

        /** oracle :168/:194/:225 —— 发送路径只抬内存高水位，不写 persisted。 */
        fun observeUsed(id: Long) {
            nextMessageId = maxOf(nextMessageId ?: 0L, id + 1)
        }

        private fun persisted(): Long = env.settings.getLong(MessageIdAllocator.KEY_MAX_USED_MESSAGE_ID, 0L)

        /** oracle :292-294 —— 只在更大时写回。 */
        private fun remember(id: Long) {
            if (id > persisted()) env.settings.putLong(MessageIdAllocator.KEY_MAX_USED_MESSAGE_ID, id)
        }
    }

    private sealed interface IdOp {
        data object Allocate : IdOp
        data class Insert(val id: Long) : IdOp
        data class Delete(val id: Long) : IdOp
        data class Observe(val id: Long) : IdOp
    }
}
