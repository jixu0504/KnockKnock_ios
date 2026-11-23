package com.rhodes.privatechat.shared.prompt

import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.SettingsListener
import com.rhodes.privatechat.shared.settings.SettingsRepository

/**
 * SettingsRepository → PromptResolver 适配（settings 域实现接口由调用方组装；
 * prompt 域不持有存储，本适配器仅存在于测试）。
 */
class SettingsPromptResolver(private val repository: SettingsRepository) : PromptResolver {
    override fun resolvePromptTemplate(type: String, mode: String, defaultTemplate: String, defaultVersion: Int): String =
        repository.resolvePromptTemplate(type, mode, defaultTemplate, defaultVersion)
    override fun saveCustomPromptTemplate(type: String, mode: String, value: String, version: Int) =
        repository.saveCustomPromptTemplate(type, mode, value, version)
    override fun removePromptTemplate(type: String, mode: String) = repository.removePromptTemplate(type, mode)
    override fun isPromptTemplateCustom(type: String, mode: String): Boolean = repository.isPromptTemplateCustom(type, mode)
    override fun getPromptModule(module: String, type: String, mode: String): String = repository.getPromptModule(module, type, mode)
    override fun getCustomPromptModuleOrNull(module: String, type: String, mode: String): String? = repository.getCustomPromptModuleOrNull(module, type, mode)
    override fun savePromptModule(module: String, type: String, mode: String, value: String, version: Int) =
        repository.savePromptModule(module, type, mode, value, version)
    override fun removePromptModule(module: String, type: String, mode: String) = repository.removePromptModule(module, type, mode)
    override fun isPromptModuleCustom(module: String, type: String, mode: String): Boolean = repository.isPromptModuleCustom(module, type, mode)
}


/**
 * commonTest 内存 settings 实现（等价 androidUnitTest 的 InMemoryObservableSettings，
 * multiplatform-settings 1.2.0 的 MapSettings 在 -test 构件，为最小 diff 手写）。
 */
class PromptTestSettings : ObservableSettings {

    private val store = mutableMapOf<String, Any>()
    fun raw(): Map<String, Any> = store.toMap()

    override val keys: Set<String> get() = store.keys
    override val size: Int get() = store.size
    override fun clear() = store.clear()
    override fun remove(key: String) { store.remove(key) }
    override fun hasKey(key: String): Boolean = store.containsKey(key)

    override fun putInt(key: String, value: Int) { store[key] = value }
    override fun getInt(key: String, defaultValue: Int): Int = store[key] as? Int ?: defaultValue
    override fun getIntOrNull(key: String): Int? = store[key] as? Int

    override fun putLong(key: String, value: Long) { store[key] = value }
    override fun getLong(key: String, defaultValue: Long): Long = store[key] as? Long ?: defaultValue
    override fun getLongOrNull(key: String): Long? = store[key] as? Long

    override fun putString(key: String, value: String) { store[key] = value }
    override fun getString(key: String, defaultValue: String): String = store[key] as? String ?: defaultValue
    override fun getStringOrNull(key: String): String? = store[key] as? String

    override fun putFloat(key: String, value: Float) { store[key] = value }
    override fun getFloat(key: String, defaultValue: Float): Float = store[key] as? Float ?: defaultValue
    override fun getFloatOrNull(key: String): Float? = store[key] as? Float

    override fun putDouble(key: String, value: Double) { store[key] = value }
    override fun getDouble(key: String, defaultValue: Double): Double = store[key] as? Double ?: defaultValue
    override fun getDoubleOrNull(key: String): Double? = store[key] as? Double

    override fun putBoolean(key: String, value: Boolean) { store[key] = value }
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = store[key] as? Boolean ?: defaultValue
    override fun getBooleanOrNull(key: String): Boolean? = store[key] as? Boolean

    private val noop = object : SettingsListener { override fun deactivate() {} }
    override fun addIntListener(key: String, defaultValue: Int, callback: (Int) -> Unit): SettingsListener = noop
    override fun addLongListener(key: String, defaultValue: Long, callback: (Long) -> Unit): SettingsListener = noop
    override fun addStringListener(key: String, defaultValue: String, callback: (String) -> Unit): SettingsListener = noop
    override fun addFloatListener(key: String, defaultValue: Float, callback: (Float) -> Unit): SettingsListener = noop
    override fun addDoubleListener(key: String, defaultValue: Double, callback: (Double) -> Unit): SettingsListener = noop
    override fun addBooleanListener(key: String, defaultValue: Boolean, callback: (Boolean) -> Unit): SettingsListener = noop
    override fun addIntOrNullListener(key: String, callback: (Int?) -> Unit): SettingsListener = noop
    override fun addLongOrNullListener(key: String, callback: (Long?) -> Unit): SettingsListener = noop
    override fun addStringOrNullListener(key: String, callback: (String?) -> Unit): SettingsListener = noop
    override fun addFloatOrNullListener(key: String, callback: (Float?) -> Unit): SettingsListener = noop
    override fun addDoubleOrNullListener(key: String, callback: (Double?) -> Unit): SettingsListener = noop
    override fun addBooleanOrNullListener(key: String, callback: (Boolean?) -> Unit): SettingsListener = noop
}
