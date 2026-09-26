package com.aicode.feature.agent.domain.mcp

/**
 * NDJSON 帧（一行一条 JSON-RPC）的读取结果。
 */
internal sealed interface FrameRead {
    data class Ok(val line: String) : FrameRead

    /** 单帧超出上限，剩余部分到换行前已丢弃。 */
    data object Oversize : FrameRead

    data object Eof : FrameRead
}

/**
 * 按行读取 NDJSON 帧，并限制单帧最大字符数。
 *
 * 不用 [java.io.BufferedReader.readLine]——它对单行长度无上限，异常 MCP server 若吐出巨型单行
 * （如把一整个大对象写成一行）会直接顶爆内存。超限时标记 [FrameRead.Oversize] 并丢弃该行剩余部分，
 * 保持流位置对齐到下一帧。纯逻辑、零 Android 依赖，便于单测。
 */
internal object McpFrameReader {

    fun readFrame(reader: java.io.Reader, limit: Int): FrameRead {
        require(limit > 0) { "limit 必须为正" }
        val sb = StringBuilder()
        var oversize = false
        while (true) {
            val c = reader.read()
            if (c == -1) {
                return when {
                    oversize -> FrameRead.Oversize
                    sb.isEmpty() -> FrameRead.Eof
                    else -> FrameRead.Ok(sb.toString())
                }
            }
            if (c == '\n'.code) {
                return if (oversize) FrameRead.Oversize else FrameRead.Ok(sb.toString().trimEnd('\r'))
            }
            if (oversize) continue
            if (sb.length >= limit) {
                oversize = true
                continue
            }
            sb.append(c.toChar())
        }
    }
}
