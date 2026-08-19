package com.etherstories.eslink.core.model

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 核心进程日志 — 环形缓冲区，无 Minecraft 依赖。
 * 保留近期日志供调试，不依赖 Bukkit 或任何 Minecraft 类。
 */
object LinkLog {

    private const val MAX = 120
    private val FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val lines = ConcurrentLinkedDeque<String>()
    private var debug = false

    /** 是否开启详细日志。 */
    fun isDebug(): Boolean = debug

    /** 切换详细日志。 */
    fun toggleDebug(): Boolean {
        debug = !debug
        add("I debug ${if (debug) "开" else "关"}")
        return debug
    }

    /** 清空日志。 */
    fun clear() {
        lines.clear()
        add("I 已清空")
    }

    /** 添加一条日志。 */
    fun add(raw: String?) {
        val body = (raw ?: "").replace('\n', ' ').replace('\r', ' ')
        var line = LocalTime.now().format(FMT) + " " + body
        if (line.length > 220) line = line.substring(0, 217) + "..."
        lines.addLast(line)
        while (lines.size > MAX) lines.pollFirst()
    }

    /** 添加一条调试日志（仅 debug 模式生效）。 */
    fun debug(msg: String?) {
        if (!debug || msg.isNullOrEmpty()) return
        add("D $msg")
    }

    /** 获取最近 N 条日志。 */
    fun recent(count: Int = 12): List<String> {
        val all = lines.toList()
        val from = (all.size - count).coerceAtLeast(0)
        return all.subList(from, all.size)
    }

    /** 获取所有日志。 */
    fun all(): List<String> = lines.toList()

    /** 获取日志总数。 */
    fun size(): Int = lines.size
}