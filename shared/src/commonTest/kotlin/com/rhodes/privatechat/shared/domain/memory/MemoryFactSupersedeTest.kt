package com.rhodes.privatechat.shared.domain.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** T78：单值谓词换值 → supersedes；可多值谓词换客体不取代。 */
class MemoryFactSupersedeTest {

    @Test
    fun functionalPredicateChangeSupersedes() {
        assertEquals(
            MemoryRelationRules.SUPERSEDES,
            MemoryRelationRules.relationType(
                "fact:positive:user|reside|上海浦东|q=最近",
                "fact:positive:user|reside|杭州",
            ),
        )
        assertEquals(
            MemoryRelationRules.SUPERSEDES,
            MemoryRelationRules.relationType("fact:positive:user|work|设计公司", "fact:positive:user|work|游戏公司"),
        )
    }

    @Test
    fun multiValuePredicateChangeHasNoRelation() {
        assertNull(
            MemoryRelationRules.relationType(
                "fact:positive:user|prefer|香菜",
                "fact:positive:user|prefer|川菜",
            ),
        )
        assertNull(
            MemoryRelationRules.relationType(
                "fact:positive:user|own|布偶猫团子",
                "fact:positive:user|own|橘猫",
            ),
            "养宠物可以多只，不能判为取代",
        )
    }

    @Test
    fun qualifierVariantsStillSupport() {
        assertEquals(
            MemoryRelationRules.SUPPORTS,
            MemoryRelationRules.relationType(
                "fact:positive:user|prefer|美式咖啡不加糖",
                "fact:positive:user|prefer|美式咖啡|q=每天一杯",
            ),
        )
        assertEquals(
            MemoryRelationRules.CONTRADICTS,
            MemoryRelationRules.relationType(
                "fact:negative:user|prefer|美式咖啡",
                "fact:positive:user|prefer|美式咖啡不加糖",
            ),
        )
    }

    @Test
    fun differentSubjectNeverRelates() {
        assertNull(
            MemoryRelationRules.relationType("fact:positive:role|reside|新艾利都", "fact:positive:user|reside|杭州"),
        )
    }
}
