package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.model.MemoryItem
import com.rhodes.privatechat.shared.model.MemoryLevel
import com.rhodes.privatechat.shared.model.MemorySourceKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** 向量 id ↔ 记忆行 id 的可逆性（召回层父子去重依赖它）。 */
class MemoryVectorIdTest {

    private fun item(id: Long, kind: MemorySourceKind = MemorySourceKind.PRIVATE_CHAT) = MemoryItem(
        id = id,
        ownerType = "operator",
        ownerId = "op_1",
        memoryLevel = MemoryLevel.L1,
        memoryType = "event",
        sourceKind = kind,
        content = "内容",
    )

    @Test
    fun vectorIdRoundTripsToMemoryId() {
        assertEquals(7L, MemoryVectorFormatter.originMemoryId(MemoryVectorFormatter.vectorId(item(7))))
        assertEquals(
            42L,
            MemoryVectorFormatter.originMemoryId(MemoryVectorFormatter.vectorId(item(42, MemorySourceKind.MANUAL_MEMORY))),
        )
        assertEquals(0L, MemoryVectorFormatter.originMemoryId("knowledge_chunk_abc"))
        assertEquals(0L, MemoryVectorFormatter.originMemoryId(""))
    }
}
