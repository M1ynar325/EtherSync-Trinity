package com.etherstories.eslink.core.serialization

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * 物品序列化服务 — 编排层。
 *
 * 职责：
 *   1. 根据配置决定序列化路径（ESN1 vs ESN6）
 *   2. 处理 ESN6 失败回退到 ESN1
 *   3. 驱动熔断器状态变更
 *   4. 对外暴露统一接口
 *
 * 不负责：
 *   - NBT 序列化（由插件/模组层提供 NBT 字节）
 *   - 容器内含读取（由插件/模组层提供内含列表）
 */
class SerializationService(
    val config: SplitConfig = SplitConfig()
) {
    // ──────────────────────────────────────────────
    // 编码
    // ──────────────────────────────────────────────

    /**
     * 编码单个物品（非容器，或容器但未启用拆包）。
     * 始终使用 ESN1。
     */
    fun encodeItem(itemKey: String, amount: Int, nbtData: ByteArray): ByteArray {
        val item = SerialFormat.Esn1Item(itemKey, amount, nbtData)
        return SerialFormat.encodeEsn1(item)
    }

    /**
     * 编码容器物品。
     *
     * 决策逻辑：
     *   1. config.enabled == false → ESN1（整包）
     *   2. 不在白名单 → ESN1（整包）
     *   3. 熔断器已触发 → ESN1（整包）
     *   4. 尝试 ESN6，失败则退回 ESN1 + 记录熔断
     *
     * @param containerKey 容器物品注册名
     * @param amount 数量
     * @param displayName 容器展示名
     * @param address Create 纸箱配送地址（普通容器传空字符串）
     * @param nbtData 整包 NBT 数据（ESN1 兜底用）
     * @param innerItems 内含物品列表（每个 item 的 NBT 数据已由插件层序列化）
     * @return EncodeResult（编码字节 + 格式 + 是否拆分）
     */
    fun encodeContainer(
        containerKey: String,
        amount: Int,
        displayName: String,
        address: String,
        nbtData: ByteArray,
        innerItems: List<SerialFormat.Esn1Item>
    ): EncodeResult {
        // 检查是否应该尝试 ESN6
        if (!config.allowSplit(containerKey)) {
            // 走 ESN1
            val esn1 = encodeItem(containerKey, amount, nbtData)
            return EncodeResult(esn1, SerialFormat.Format.ESN1, false)
        }

        // 检查内含数量（配置上限不能超过协议硬上限）
        val effectiveMaxInner = minOf(config.maxInnerItems, SerialFormat.MAX_INNER_ITEMS)
        if (innerItems.size > effectiveMaxInner) {
            // 内含太多，策略性退回 ESN1，不触发熔断
            val esn1 = encodeItem(containerKey, amount, nbtData)
            return EncodeResult(esn1, SerialFormat.Format.ESN1, false)
        }

        // 尝试 ESN6
        val container = SerialFormat.Esn6Container(
            containerKey = containerKey,
            amount = amount,
            displayName = displayName,
            address = address,
            innerItems = innerItems
        )

        // 配置上限不能超过协议硬上限；encodeEsn6 内部对超限抛 Esn6PolicyException
        val effectiveMaxSize = minOf(config.maxTotalSize, SerialFormat.MAX_SPLIT_SIZE)
        val esn6 = try {
            SerialFormat.encodeEsn6(container, effectiveMaxInner, effectiveMaxSize)
        } catch (e: SerialFormat.Esn6PolicyException) {
            // 大小/数量超过限制：策略性退回 ESN1，不触发熔断
            val esn1 = encodeItem(containerKey, amount, nbtData)
            return EncodeResult(esn1, SerialFormat.Format.ESN1, false)
        }

        if (esn6 != null) {
            // ESN6 成功
            config.recordSuccess(containerKey)
            return EncodeResult(esn6, SerialFormat.Format.ESN6, true)
        }

        // ESN6 实际编码失败（非策略拒绝），记录熔断，退回 ESN1
        config.recordFailure(containerKey)
        val esn1 = encodeItem(containerKey, amount, nbtData)
        return EncodeResult(esn1, SerialFormat.Format.ESN1, false)
    }

    /**
     * 编码结果。
     */
    data class EncodeResult(
        val data: ByteArray,
        val format: SerialFormat.Format,
        val split: Boolean  // 是否走了拆包路径
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EncodeResult) return false
            return format == other.format && split == other.split && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = format.hashCode()
            result = 31 * result + split.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    // ──────────────────────────────────────────────
    // 解码
    // ──────────────────────────────────────────────

    /**
     * 解码物品数据，自动检测格式。
     *
     * @return 解码结果，包含格式和解析出的数据
     */
    fun decode(data: ByteArray): DecodeResult {
        return when (val format = SerialFormat.detect(data)) {
            SerialFormat.Format.ESN1 -> {
                val item = SerialFormat.decodeEsn1(data)
                if (item != null) {
                    DecodeResult.Single(item)
                } else {
                    DecodeResult.Failure("ESN1 解码失败")
                }
            }
            SerialFormat.Format.ESN6 -> {
                val container = SerialFormat.decodeEsn6(data)
                if (container != null) {
                    // 收到合法的 ESN6 即说明该容器类型可拆包，记录成功
                    config.recordSuccess(container.containerKey)
                    DecodeResult.Container(container)
                } else {
                    recordDecodeFailure(data)
                    DecodeResult.Failure("ESN6 解码失败")
                }
            }
            SerialFormat.Format.UNKNOWN -> {
                DecodeResult.Failure("未知格式")
            }
        }
    }

    /**
     * 解码 ESN6 容器，失败时记录熔断。
     */
    fun decodeContainer(data: ByteArray): DecodeResult {
        if (!SerialFormat.isEsn6(data)) {
            return DecodeResult.Failure("不是 ESN6 格式")
        }

        val container = SerialFormat.decodeEsn6(data)
        if (container != null) {
            config.recordSuccess(container.containerKey)
            return DecodeResult.Container(container)
        }

        recordDecodeFailure(data)
        return DecodeResult.Failure("ESN6 解码失败")
    }

    /**
     * ESN6 解码失败时尝试提取容器 key 并记录熔断。
     * 只对本地白名单中的容器类型记录熔断，避免恶意数据撑大熔断表。
     */
    private fun recordDecodeFailure(data: ByteArray) {
        // 即使解码失败，也尝试读取 containerKey（前几个字段可能完整）
        val partialKey = tryReadContainerKey(data)
        if (partialKey != null && config.isWhitelisted(partialKey)) {
            config.recordFailure(partialKey)
        }
    }

    /**
     * 尝试从部分损坏的 ESN6 数据中读取容器 key。
     */
    private fun tryReadContainerKey(data: ByteArray): String? {
        return try {
            // 跳过 magic(4) + version(1)，读取 UTF 字符串
            val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(data, 5, data.size - 5))
            dis.readUTF()
        } catch (e: Exception) {
            null
        }
    }

    // ──────────────────────────────────────────────
    // 解码结果类型
    // ──────────────────────────────────────────────

    sealed class DecodeResult {
        /** 单个物品（ESN1） */
        data class Single(val item: SerialFormat.Esn1Item) : DecodeResult()

        /** 容器（ESN6），需要插件层重建 */
        data class Container(val container: SerialFormat.Esn6Container) : DecodeResult()

        /** 解码失败 */
        data class Failure(val reason: String) : DecodeResult()
    }

    // ──────────────────────────────────────────────
    // 辅助：编码队列物品的 payload
    // ──────────────────────────────────────────────

    /**
     * 将物品编码为队列传输 payload。
     *
     * 格式：
     *   [Format: 1B] [ItemData: ...]
     *
     * Format: 0x01 = ESN1, 0x06 = ESN6
     */
    fun encodeQueuePayload(itemData: ByteArray, format: SerialFormat.Format): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.writeByte(when (format) {
            SerialFormat.Format.ESN1 -> 0x01
            SerialFormat.Format.ESN6 -> 0x06
            SerialFormat.Format.UNKNOWN -> throw IllegalArgumentException("未知格式")
        })
        dos.write(itemData)
        return bos.toByteArray()
    }

    /**
     * 从队列 payload 中解码物品。
     */
    fun decodeQueuePayload(payload: ByteArray): DecodeResult {
        if (payload.isEmpty()) return DecodeResult.Failure("空 payload")
        return try {
            val formatByte = payload[0].toInt()
            val itemData = payload.copyOfRange(1, payload.size)
            when (formatByte) {
                0x01 -> {
                    val item = SerialFormat.decodeEsn1(itemData)
                    if (item != null) DecodeResult.Single(item)
                    else DecodeResult.Failure("ESN1 解码失败")
                }
                0x06 -> {
                    val container = SerialFormat.decodeEsn6(itemData)
                    if (container != null) DecodeResult.Container(container)
                    else DecodeResult.Failure("ESN6 解码失败")
                }
                else -> DecodeResult.Failure("未知格式字节: $formatByte")
            }
        } catch (e: Exception) {
            DecodeResult.Failure("解码异常: ${e.message}")
        }
    }
}