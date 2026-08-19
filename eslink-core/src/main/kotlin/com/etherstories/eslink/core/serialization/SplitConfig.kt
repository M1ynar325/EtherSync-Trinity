package com.etherstories.eslink.core.serialization

import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ESN6 容器拆包配置。
 *
 * 三层控制：
 *   1. 总开关 enabled — 默认 false（关闭），需在配置文件中开启
 *   2. 白名单 whitelist — 仅匹配的容器可拆包，支持通配符 "*"（如 "create:*"）
 *   3. 熔断器 — 同一容器类型连续失败 N 次后自动禁用，冷却后重试
 *
 * 配置来源（优先级从高到低）：
 *   1. 游戏内运行时修改（通过 /link container split 命令）
 *   2. 配置文件 eslink-split.properties
 *   3. 硬编码默认值
 *
 * 注意：eslink-core 本身不依赖 Minecraft，此配置由插件/模组层通过
 * Storage.setConfig() 持久化，或直接读写 properties 文件。
 */
class SplitConfig(private val configFile: File? = null) {

    // ── 配置键常量 ──
    companion object {
        const val KEY_ENABLED = "split.enabled"
        const val KEY_WHITELIST = "split.whitelist"
        const val KEY_MAX_INNER = "split.max_inner_items"
        const val KEY_MAX_SIZE = "split.max_total_size_bytes"
        const val KEY_CB_COOLDOWN = "split.circuit_breaker.cooldown_seconds"
        const val KEY_CB_MAX_FAILURES = "split.circuit_breaker.max_failures"

        // 默认值
        const val DEFAULT_ENABLED = false
        const val DEFAULT_MAX_INNER = 81
        const val DEFAULT_MAX_SIZE = 512 * 1024  // 512 KB
        const val DEFAULT_CB_COOLDOWN = 300      // 5 分钟
        const val DEFAULT_CB_MAX_FAILURES = 3
    }

    // ── 运行时状态 ──
    @Volatile var enabled: Boolean = DEFAULT_ENABLED
    @Volatile var maxInnerItems: Int = DEFAULT_MAX_INNER
    @Volatile var maxTotalSize: Int = DEFAULT_MAX_SIZE
    @Volatile var circuitBreakerCooldownSeconds: Int = DEFAULT_CB_COOLDOWN
    @Volatile var circuitBreakerMaxFailures: Int = DEFAULT_CB_MAX_FAILURES

    /** 白名单：元素为 "namespace:path" 或 "namespace:*" 模式（线程安全） */
    private val whitelist: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // ── 熔断器状态 ──
    private data class CircuitState(
        val failures: AtomicInteger = AtomicInteger(0),
        val lastFailure: AtomicLong = AtomicLong(0),
        val trippedUntil: AtomicLong = AtomicLong(0)
    )

    private val circuits = ConcurrentHashMap<String, CircuitState>()

    // ──────────────────────────────────────────────
    // 初始化
    // ──────────────────────────────────────────────

    /**
     * 从配置文件加载。如果文件不存在则使用默认值并写出默认配置。
     */
    fun load() {
        val props = Properties()
        val file = configFile
        if (file != null && file.exists()) {
            try {
                FileReader(file).use { props.load(it) }
            } catch (e: Exception) {
                System.err.println("[SplitConfig] 读取配置文件失败: ${e.message}，使用默认值")
            }
        }

        enabled = props.getProperty(KEY_ENABLED, DEFAULT_ENABLED.toString()).toBooleanStrictOrNull() ?: DEFAULT_ENABLED
        maxInnerItems = (props.getProperty(KEY_MAX_INNER, DEFAULT_MAX_INNER.toString()).toIntOrNull() ?: DEFAULT_MAX_INNER)
            .coerceAtLeast(1)
        maxTotalSize = (props.getProperty(KEY_MAX_SIZE, DEFAULT_MAX_SIZE.toString()).toIntOrNull() ?: DEFAULT_MAX_SIZE)
            .coerceAtLeast(1)
        circuitBreakerCooldownSeconds = (props.getProperty(KEY_CB_COOLDOWN, DEFAULT_CB_COOLDOWN.toString()).toIntOrNull() ?: DEFAULT_CB_COOLDOWN)
            .coerceAtLeast(1)
        circuitBreakerMaxFailures = (props.getProperty(KEY_CB_MAX_FAILURES, DEFAULT_CB_MAX_FAILURES.toString()).toIntOrNull() ?: DEFAULT_CB_MAX_FAILURES)
            .coerceAtLeast(1)

        val rawWhitelist = props.getProperty(KEY_WHITELIST, "")
        whitelist.clear()
        if (rawWhitelist.isNotBlank()) {
            rawWhitelist.split(",").forEach { entry ->
                val trimmed = entry.trim().lowercase()
                if (trimmed.isNotEmpty()) {
                    whitelist.add(trimmed)
                }
            }
        }

        // 写出默认配置（如果文件不存在）
        if (file != null && !file.exists()) {
            save()
        }
    }

    /**
     * 保存当前配置到文件。
     */
    fun save() {
        val file = configFile ?: return
        try {
            file.parentFile?.mkdirs()
            val props = Properties()
            props.setProperty(KEY_ENABLED, enabled.toString())
            props.setProperty(KEY_WHITELIST, whitelist.joinToString(","))
            props.setProperty(KEY_MAX_INNER, maxInnerItems.toString())
            props.setProperty(KEY_MAX_SIZE, maxTotalSize.toString())
            props.setProperty(KEY_CB_COOLDOWN, circuitBreakerCooldownSeconds.toString())
            props.setProperty(KEY_CB_MAX_FAILURES, circuitBreakerMaxFailures.toString())
            FileWriter(file).use { props.store(it, "ESLink ESN6 Split Configuration") }
        } catch (e: Exception) {
            System.err.println("[SplitConfig] 保存配置文件失败: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // 白名单
    // ──────────────────────────────────────────────

    /**
     * 检查物品 key 是否在白名单中。
     * 支持通配符：末尾 "*" 匹配前缀，如 "create:*" 匹配 "create:cardboard_package_12x12"
     */
    fun isWhitelisted(itemKey: String): Boolean {
        val key = itemKey.lowercase()
        for (pattern in whitelist) {
            if (pattern.endsWith("*")) {
                val prefix = pattern.dropLast(1)
                if (key.startsWith(prefix)) return true
            } else {
                if (key == pattern) return true
            }
        }
        return false
    }

    /**
     * 添加白名单模式。
     */
    fun addWhitelist(pattern: String): Boolean {
        val trimmed = pattern.trim().lowercase()
        if (trimmed.isEmpty()) return false
        return whitelist.add(trimmed)
    }

    /**
     * 移除白名单模式。
     */
    fun removeWhitelist(pattern: String): Boolean {
        return whitelist.remove(pattern.trim().lowercase())
    }

    /**
     * 获取白名单副本。
     */
    fun getWhitelist(): Set<String> = whitelist.toSet()

    // ──────────────────────────────────────────────
    // 熔断器
    // ──────────────────────────────────────────────

    /**
     * 检查是否允许对此容器类型使用 ESN6。
     * 如果熔断器已触发且在冷却期内，返回 false。
     */
    fun allowSplit(itemKey: String): Boolean {
        if (!enabled) return false
        if (!isWhitelisted(itemKey)) return false

        val state = circuits[itemKey.lowercase()] ?: return true
        val tripped = state.trippedUntil.get()
        if (tripped > 0 && System.currentTimeMillis() < tripped) {
            return false  // 还在冷却期
        }
        // 冷却期已过，重置为全新状态，避免冷却后第一次失败立即再次熔断
        if (tripped > 0 && System.currentTimeMillis() >= tripped) {
            state.failures.set(0)
            state.trippedUntil.set(0)
        }
        return true
    }

    /**
     * 记录 ESN6 编码/解码成功，重置该容器类型的失败计数。
     */
    fun recordSuccess(itemKey: String) {
        val state = circuits[itemKey.lowercase()] ?: return
        state.failures.set(0)
        state.trippedUntil.set(0)
    }

    /**
     * 记录 ESN6 编码/解码失败。
     * 如果连续失败次数达到阈值，触发熔断。
     *
     * @return 如果熔断器刚被触发，返回 true
     */
    fun recordFailure(itemKey: String): Boolean {
        val key = itemKey.lowercase()
        val state = circuits.computeIfAbsent(key) { CircuitState() }
        val failures = state.failures.incrementAndGet()
        state.lastFailure.set(System.currentTimeMillis())

        if (failures >= circuitBreakerMaxFailures) {
            val cooldownMs = circuitBreakerCooldownSeconds * 1000L
            state.trippedUntil.set(System.currentTimeMillis() + cooldownMs)
            System.err.println("[SplitConfig] 熔断器触发: $itemKey 连续失败 $failures 次，冷却 ${circuitBreakerCooldownSeconds}s")
            return true
        }
        return false
    }

    /**
     * 手动重置指定容器类型的熔断器。
     */
    fun resetCircuit(itemKey: String) {
        circuits.remove(itemKey.lowercase())
    }

    /**
     * 重置所有熔断器。
     */
    fun resetAllCircuits() {
        circuits.clear()
    }

    /**
     * 获取熔断器状态（供调试/GUI 使用）。
     */
    fun circuitStatus(itemKey: String): CircuitStatus {
        val state = circuits[itemKey.lowercase()]
        if (state == null) return CircuitStatus(false, 0, 0, 0)

        val failures = state.failures.get()
        val tripped = state.trippedUntil.get()
        val remaining = if (tripped > 0) {
            maxOf(0, tripped - System.currentTimeMillis())
        } else 0

        return CircuitStatus(
            tripped = remaining > 0,
            failures = failures,
            remainingCooldownMs = remaining,
            lastFailureMs = state.lastFailure.get()
        )
    }

    data class CircuitStatus(
        val tripped: Boolean,
        val failures: Int,
        val remainingCooldownMs: Long,
        val lastFailureMs: Long
    )

    /**
     * 获取所有熔断中的容器类型。
     */
    fun trippedItems(): List<String> {
        val now = System.currentTimeMillis()
        return circuits.filter { (_, state) ->
            val tripped = state.trippedUntil.get()
            tripped > 0 && now < tripped
        }.keys.toList()
    }

    // ──────────────────────────────────────────────
    // 摘要（供 GUI 展示）
    // ──────────────────────────────────────────────

    fun summary(): String {
        val sb = StringBuilder()
        sb.appendLine("split.enabled = $enabled")
        sb.appendLine("split.whitelist = ${whitelist.joinToString(",")}")
        sb.appendLine("split.max_inner = $maxInnerItems")
        sb.appendLine("split.max_size = ${maxTotalSize / 1024} KB")
        sb.appendLine("split.cb.cooldown = ${circuitBreakerCooldownSeconds}s")
        sb.appendLine("split.cb.max_failures = $circuitBreakerMaxFailures")
        val tripped = trippedItems()
        if (tripped.isNotEmpty()) {
            sb.appendLine("split.cb.tripped = ${tripped.joinToString(",")}")
        }
        return sb.toString().trimEnd()
    }
}