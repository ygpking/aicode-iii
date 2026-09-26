package com.aicode.feature.agent.domain.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ArgNormalizerTest {

    private val readSpec = ToolSpec("readFile", setOf("path"), setOf("path", "start_line"))

    @Test
    fun aliasIsRewrittenToDeclaredName() {
        val out = ArgNormalizer.normalize(readSpec, mapOf("file_path" to JsonPrimitive("/a/b")))
        assertEquals(mapOf<String, kotlinx.serialization.json.JsonElement>("path" to JsonPrimitive("/a/b")), out)
    }

    @Test
    fun camelAndLowerAliasesAlsoMap() {
        assertEquals(mapOf<String, kotlinx.serialization.json.JsonElement>("path" to JsonPrimitive("x")), ArgNormalizer.normalize(readSpec, mapOf("filePath" to JsonPrimitive("x"))))
        assertEquals(mapOf<String, kotlinx.serialization.json.JsonElement>("path" to JsonPrimitive("y")), ArgNormalizer.normalize(readSpec, mapOf("filepath" to JsonPrimitive("y"))))
    }

    @Test
    fun flatUnderscoreKeyIsNested() {
        val spec = ToolSpec("task", emptySet(), setOf("config"))
        val out = ArgNormalizer.normalize(spec, mapOf("config__port" to JsonPrimitive(8080)))
        assertEquals(JsonObject(mapOf("port" to JsonPrimitive(8080))), out["config"])
    }

    @Test
    fun flatDotKeyIsNested() {
        val spec = ToolSpec("task", emptySet(), setOf("config"))
        val out = ArgNormalizer.normalize(spec, mapOf("config.port" to JsonPrimitive(8080)))
        assertEquals(JsonObject(mapOf("port" to JsonPrimitive(8080))), out["config"])
    }

    @Test
    fun siblingFlatKeysMergeIntoOneNestedObject() {
        val spec = ToolSpec("task", emptySet(), setOf("config"))
        val out = ArgNormalizer.normalize(
            spec,
            linkedMapOf("config__host" to JsonPrimitive("h"), "config__port" to JsonPrimitive(80)),
        )
        assertEquals(
            JsonObject(mapOf("host" to JsonPrimitive("h"), "port" to JsonPrimitive(80))),
            out["config"],
        )
    }

    @Test
    fun flatKeyWithUndeclaredHeadIsLeftAsIs() {
        // 顶层键未声明 → 不嵌套，避免把合法参数改坏。
        val out = ArgNormalizer.normalize(readSpec, mapOf("foo__bar" to JsonPrimitive(1)))
        assertEquals(JsonPrimitive(1), out["foo__bar"])
    }

    @Test
    fun plainKeysPassThroughUnchanged() {
        val out = ArgNormalizer.normalize(
            readSpec,
            mapOf("path" to JsonPrimitive("/a"), "start_line" to JsonPrimitive(3)),
        )
        assertEquals(
            mapOf<String, kotlinx.serialization.json.JsonElement>("path" to JsonPrimitive("/a"), "start_line" to JsonPrimitive(3)),
            out,
        )
    }

    @Test
    fun singleKeyPackageIsUnwrappedWhenInnerKeysDeclared() {
        val spec = ToolSpec("readFile", setOf("path"), setOf("path", "start_line"))
        val wrapped = mapOf<String, kotlinx.serialization.json.JsonElement>(
            "arguments" to JsonObject(mapOf("path" to JsonPrimitive("/a"), "start_line" to JsonPrimitive(2))),
        )
        val out = ArgNormalizer.normalize(spec, wrapped)
        assertEquals(JsonPrimitive("/a"), out["path"])
        assertEquals(JsonPrimitive(2), out["start_line"])
    }

    @Test
    fun singleKeyPackageIsNotUnwrappedWhenInnerKeysUndeclared() {
        val spec = ToolSpec("readFile", setOf("path"), setOf("path"))
        val wrapped = mapOf<String, kotlinx.serialization.json.JsonElement>(
            "arguments" to JsonObject(mapOf("nope" to JsonPrimitive("/a"))),
        )
        val out = ArgNormalizer.normalize(spec, wrapped)
        assertEquals(wrapped, out)
    }

    @Test
    fun mcpToolIsShortCircuited() {
        val spec = ToolSpec("mcp__files__read", emptySet(), emptySet())
        val args = mapOf<String, kotlinx.serialization.json.JsonElement>(
            "file_path" to JsonPrimitive("/keep/verbatim"),
            "config__x" to JsonPrimitive(1),
        )
        assertSame(args, ArgNormalizer.normalize(spec, args))
    }

    @Test
    fun emptyArgsStayEmpty() {
        assertEquals(emptyMap<String, kotlinx.serialization.json.JsonElement>(), ArgNormalizer.normalize(readSpec, emptyMap()))
    }
}
