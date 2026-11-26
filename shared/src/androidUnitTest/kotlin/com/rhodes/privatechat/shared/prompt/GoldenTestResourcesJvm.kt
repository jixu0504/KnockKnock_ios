package com.rhodes.privatechat.shared.prompt

internal actual fun readTestResource(path: String): String {
    val stream = checkNotNull(object {}.javaClass.classLoader?.getResourceAsStream(path)) {
        "JVM test resource missing: $path"
    }
    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
