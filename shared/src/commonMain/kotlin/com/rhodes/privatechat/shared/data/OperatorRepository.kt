package com.rhodes.privatechat.shared.data
import com.rhodes.privatechat.shared.ioDispatcher

import com.rhodes.privatechat.shared.db.DatabaseWrapper
import com.rhodes.privatechat.shared.db.DatabaseDispatcher
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.model.*
import kotlinx.coroutines.Dispatchers
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

class OperatorRepository(private val wrapper: DatabaseWrapper) {

    private val db: RhodesDatabase get() = wrapper.database

    private fun mapOperator(
        id: String,
        name: String,
        title: String,
        description: String,
        gender: String,
        avatarUri: String,
        location: String,
        activity: String,
        emotion: String,
        intimacy: Long,
        privatePrompt: String,
        groupPrompt: String,
        memoryInjection: String,
        userRelation: String,
        lmb: Long,
        attack: Double,
        defense: Double,
        meldPref: String,
        activityLevel: Double,
        voiceName: String,
        voiceSpeed: String,
        voicePitch: String
    ) = Operator(id, name, title, description, gender, avatarUri, location, activity, emotion, intimacy.toInt(), privatePrompt, groupPrompt, memoryInjection, userRelation, lmb.toInt(), attack.toFloat(), defense.toFloat(), meldPref, activityLevel.toFloat(), voiceName, voiceSpeed, voicePitch)

    // --- Operators ---
    val allOperators: Flow<List<Operator>> =
        db.operatorsQueries.getAllOperators(::mapOperator).asFlow().mapToList(Dispatchers.Default)

    suspend fun getOperator(id: String): Operator? = DatabaseDispatcher.execute("operator_get") {
        db.operatorsQueries.getOperator(id, ::mapOperator).executeAsOneOrNull()
    }

    suspend fun insertPresetOperators() = withContext(Dispatchers.Default) {
        val count = db.operatorsQueries.getCount().executeAsOne()
        if (count > 0L) {
            if (count == presetOperators.size.toLong()) {
                db.operatorsQueries.getOperator(presetOperators.first().id, ::mapOperator).executeAsOneOrNull()?.let { first ->
                    if (first.privatePrompt.isBlank()) {
                        presetOperators.forEach { op ->
                            db.operatorsQueries.updatePrompts(op.privatePrompt, op.groupPrompt, op.id)
                        }
                    }
                }
            }
            return@withContext
        }
        presetOperators.forEach { op ->
            db.operatorsQueries.insertOperator(op.id, op.name, op.title, op.description, op.gender, op.avatarUri, op.location, op.activity, op.emotion, op.intimacy.toLong(), op.privatePrompt, op.groupPrompt, op.memoryInjection, op.userRelation, op.lmb.toLong(), op.attack.toDouble(), op.defense.toDouble(), op.meldPref, op.activityLevel.toDouble(), op.voiceName, op.voiceSpeed, op.voicePitch)
        }
    }

    /** Adds presets introduced by newer app versions without overwriting existing user data. */
    suspend fun ensurePresetOperators(excludedIds: Set<String> = emptySet()) = withContext(Dispatchers.Default) {
        presetOperators.forEach { op ->
            if (op.id !in excludedIds && db.operatorsQueries.getOperator(op.id, ::mapOperator).executeAsOneOrNull() == null) {
                db.operatorsQueries.insertOperator(op.id, op.name, op.title, op.description, op.gender, op.avatarUri, op.location, op.activity, op.emotion, op.intimacy.toLong(), op.privatePrompt, op.groupPrompt, op.memoryInjection, op.userRelation, op.lmb.toLong(), op.attack.toDouble(), op.defense.toDouble(), op.meldPref, op.activityLevel.toDouble(), op.voiceName, op.voiceSpeed, op.voicePitch)
            }
        }
    }

    fun isPresetOperatorId(id: String): Boolean = presetOperators.any { it.id == id }

    suspend fun getAllOperatorsSync(): List<Operator> = DatabaseDispatcher.execute("operator_list") {
        db.operatorsQueries.getAllOperators(::mapOperator).executeAsList()
    }

    private val presetOperators = ZzzPresets.operators

    suspend fun deleteOperator(id: String) = withContext(ioDispatcher) { db.operatorsQueries.deleteOperator(id) }

    suspend fun updateOperator(op: Operator) = DatabaseDispatcher.execute("operator_update") {
        db.operatorsQueries.updateOperator(op.name, op.title, op.description, op.gender, op.avatarUri, op.location, op.activity, op.emotion, op.intimacy.toLong(), op.privatePrompt, op.groupPrompt, op.memoryInjection, op.userRelation, op.lmb.toLong(), op.attack.toDouble(), op.defense.toDouble(), op.meldPref, op.activityLevel.toDouble(), op.voiceName, op.voiceSpeed, op.voicePitch, op.id)
    }

    suspend fun updateIntimacy(id: String, intimacy: Int) = withContext(ioDispatcher) {
        db.operatorsQueries.updateIntimacy(intimacy.toLong(), id)
    }

    suspend fun insertOperator(op: Operator) = DatabaseDispatcher.execute("operator_insert") {
        db.operatorsQueries.insertOperator(op.id, op.name, op.title, op.description, op.gender, op.avatarUri, op.location, op.activity, op.emotion, op.intimacy.toLong(), op.privatePrompt, op.groupPrompt, op.memoryInjection, op.userRelation, op.lmb.toLong(), op.attack.toDouble(), op.defense.toDouble(), op.meldPref, op.activityLevel.toDouble(), op.voiceName, op.voiceSpeed, op.voicePitch)
    }
}

