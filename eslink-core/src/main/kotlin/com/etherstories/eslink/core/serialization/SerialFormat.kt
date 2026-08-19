package com.etherstories.eslink.core.serialization

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.util.zip.GZIPInputStream

/**
 * ESLink 物品序列化格式定义。
 *
 * 两种格式：
 *   ESN1 — 通用格式，整包 NBT 快照（单品 + 容器 + AE磁盘 + 剪贴板 + 一切）
 *   ESN6 — 可选格式，容器拆包（仅白名单内的容器，用于跨模组环境部分投递）
 *
 * ESN1 始终可用，是兜底格式。
 * ESN6 仅在配置开启 + 白名单匹配 + 熔断器未触发时使用。
 */
object SerialFormat {

    // ── Magic bytes ──
    const val MAGIC_ESN1: Int = 0x45534E31  // "ESN1"
    const val MAGIC_ESN6: Int = 0x45534E36  // "ESN6"

    private val MAGIC_BYTES_ESN1 = byteArrayOf('E'.code.toByte(), 'S'.code.toByte(), 'N'.code.toByte(), '1'.code.toByte())
    private val MAGIC_BYTES_ESN6 = byteArrayOf('E'.code.toByte(), 'S'.code.toByte(), 'N'.code.toByte(), '6'.code.toByte())

    /** 单个物品 payload 上限（1 MB） */
    const val MAX_ITEM_SIZE = 1 * 1024 * 1024

    /** ESN6 内含物品数量上限 */
    const val MAX_INNER_ITEMS = 81

    /** ESN6 序列化后总大小上限（512 KB），超过则退回 ESN1 */
    const val MAX_SPLIT_SIZE = 512 * 1024

    // ──────────────────────────────────────────────
    // ESN1 格式
    // ──────────────────────────────────────────────

    /**
     * ESN1 结构：
     *   [Magic: 4B "ESN1"] [Version: 1B] [ItemKey: UTF-8] [Amount: 4B] [NbtLen: 4B] [NbtData: NbtLen]
     *
     * NbtData 由插件/模组层提供（Minecraft NBT CompoundTag 的 gzip 压缩字节）。
     * eslink-core 不解析 NBT 内容，只负责封装。
     */
    data class Esn1Item(
        val itemKey: String,
        val amount: Int,
        val nbtData: ByteArray  // gzip 压缩的 NBT CompoundTag
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Esn1Item) return false
            return itemKey == other.itemKey && amount == other.amount && nbtData.contentEquals(other.nbtData)
        }

        override fun hashCode(): Int {
            var result = itemKey.hashCode()
            result = 31 * result + amount
            result = 31 * result + nbtData.contentHashCode()
            return result
        }
    }

    /**
     * 编码 ESN1 物品。
     */
    fun encodeEsn1(item: Esn1Item): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)

        // Magic
        dos.write(MAGIC_BYTES_ESN1)
        // Version
        dos.writeByte(1)
        // ItemKey
        dos.writeUTF(item.itemKey)
        // Amount
        dos.writeInt(item.amount.coerceIn(1, 127))
        // NbtData
        dos.writeInt(item.nbtData.size)
        if (item.nbtData.isNotEmpty()) {
            dos.write(item.nbtData)
        }

        val result = bos.toByteArray()
        if (result.size > MAX_ITEM_SIZE) {
            throw IllegalArgumentException("ESN1 encoded size ${result.size} exceeds limit $MAX_ITEM_SIZE")
        }
        return result
    }

    /**
     * 解码 ESN1 物品，返回 null 表示格式错误。
     */
    fun decodeEsn1(data: ByteArray): Esn1Item? {
        if (data.size < 4 + 1 + 2 + 4 + 4) return null
        if (data.size > MAX_ITEM_SIZE) return null
        if (!startsWith(data, MAGIC_BYTES_ESN1)) return null

        return try {
            val dis = DataInputStream(ByteArrayInputStream(data, MAGIC_BYTES_ESN1.size, data.size - MAGIC_BYTES_ESN1.size))

            val version = dis.readByte().toInt()
            if (version != 1) return null

            val itemKey = dis.readUTF()
            val amount = dis.readInt()
            val nbtLen = dis.readInt()

            if (amount < 1 || amount > 127) return null
            if (nbtLen < 0 || nbtLen > MAX_ITEM_SIZE) return null

            val nbtData = ByteArray(nbtLen)
            if (nbtLen > 0) {
                dis.readFully(nbtData)
            }

            Esn1Item(itemKey, amount, nbtData)
        } catch (e: Exception) {
            null
        }
    }

    // ──────────────────────────────────────────────
    // ESN6 格式
    // ──────────────────────────────────────────────

    /**
     * ESN6 结构：
     *   [Magic: 4B "ESN6"] [Version: 1B]
     *   [ContainerKey: UTF-8] [Amount: 4B] [DisplayName: UTF-8] [Address: UTF-8]
     *   [InnerCount: 4B]
     *   [InnerItem0: ESN1 blob] [InnerItem1: ESN1 blob] ...
     *
     * 每个 InnerItem 是完整的 ESN1 编码字节，前面带 4 字节长度前缀。
     */
    data class Esn6Container(
        val containerKey: String,
        val amount: Int,
        val displayName: String,
        val address: String,       // Create 纸箱配送地址，普通容器为空
        val innerItems: List<Esn1Item>
    ) {
        /** 计算预估序列化大小（保守估算，只允许偏大不允许偏小） */
        fun estimatedSize(): Int {
            var size = 4 + 1 + utfSize(containerKey) + 4 + utfSize(displayName) + utfSize(address) + 4
            for (item in innerItems) {
                // 外层 4 字节长度 + ESN1 magic(4) + version(1) + UTF key + amount(4) + nbtLen(4) + nbt
                size += 4 + 4 + 1 + utfSize(item.itemKey) + 4 + 4 + item.nbtData.size
            }
            return size
        }
    }

    /**
     * ESN6 编码策略拒绝异常。
     * 表示容器因超过内含数量或总大小上限而被策略性拒绝，
     * 调用方应无副作用地退回 ESN1，不应触发熔断器。
     */
    class Esn6PolicyException(message: String) : RuntimeException(message)

    /**
     * 编码 ESN6 容器。
     *
     * @param maxInnerItems 允许的最大内含数量（不超过协议上限）
     * @param maxTotalSize 允许的最大序列化后总字节数（不超过协议上限）
     * @return 编码后的字节；返回 null 表示编码过程中发生异常（非策略拒绝）
     * @throws Esn6PolicyException 当内含数量或大小超过限制时抛出
     */
    fun encodeEsn6(
        container: Esn6Container,
        maxInnerItems: Int = MAX_INNER_ITEMS,
        maxTotalSize: Int = MAX_SPLIT_SIZE
    ): ByteArray? {
        val innerLimit = maxInnerItems.coerceIn(1, MAX_INNER_ITEMS)
        val sizeLimit = maxTotalSize.coerceIn(1, MAX_SPLIT_SIZE)

        if (container.innerItems.size > innerLimit) {
            throw Esn6PolicyException(
                "ESN6 inner items ${container.innerItems.size} exceeds limit $innerLimit"
            )
        }
        if (container.estimatedSize() > sizeLimit) {
            throw Esn6PolicyException(
                "ESN6 estimated size ${container.estimatedSize()} exceeds limit $sizeLimit"
            )
        }

        return try {
            val bos = ByteArrayOutputStream()
            val dos = DataOutputStream(bos)

            // Magic
            dos.write(MAGIC_BYTES_ESN6)
            // Version
            dos.writeByte(1)
            // ContainerKey
            dos.writeUTF(container.containerKey)
            // Amount
            dos.writeInt(container.amount.coerceIn(1, 127))
            // DisplayName
            dos.writeUTF(container.displayName)
            // Address
            dos.writeUTF(container.address)
            // InnerCount
            dos.writeInt(container.innerItems.size)
            // Inner items (each as ESN1)
            for (item in container.innerItems) {
                val esn1 = try {
                    encodeEsn1(item)
                } catch (e: Exception) {
                    // 内含物品无法编码（过大/键过长等）：属于该容器不可拆分，策略性拒绝
                    throw Esn6PolicyException("ESN6 inner item cannot encode: ${item.itemKey} (${e.message})")
                }
                dos.writeInt(esn1.size)
                dos.write(esn1)
            }

            val result = bos.toByteArray()
            if (result.size > sizeLimit) {
                throw Esn6PolicyException(
                    "ESN6 encoded size ${result.size} exceeds limit $sizeLimit"
                )
            }
            result
        } catch (e: Esn6PolicyException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解码 ESN6 容器，返回 null 表示格式错误或数据损坏。
     */
    fun decodeEsn6(data: ByteArray): Esn6Container? {
        if (data.size < 4 + 1 + 2 + 4 + 2 + 2 + 4) return null
        if (data.size > MAX_SPLIT_SIZE) return null
        if (!startsWith(data, MAGIC_BYTES_ESN6)) return null

        return try {
            val dis = DataInputStream(ByteArrayInputStream(data, MAGIC_BYTES_ESN6.size, data.size - MAGIC_BYTES_ESN6.size))

            val version = dis.readByte().toInt()
            if (version != 1) return null

            val containerKey = dis.readUTF()
            val amount = dis.readInt()
            val displayName = dis.readUTF()
            val address = dis.readUTF()
            val innerCount = dis.readInt()

            if (amount < 1 || amount > 127) return null
            if (innerCount < 0 || innerCount > MAX_INNER_ITEMS) return null

            val innerItems = mutableListOf<Esn1Item>()
            for (i in 0 until innerCount) {
                val itemLen = dis.readInt()
                if (itemLen <= 0 || itemLen > MAX_ITEM_SIZE) return null
                val itemData = ByteArray(itemLen)
                dis.readFully(itemData)
                val esn1 = decodeEsn1(itemData) ?: return null
                innerItems.add(esn1)
            }

            Esn6Container(containerKey, amount, displayName, address, innerItems)
        } catch (e: Exception) {
            null
        }
    }

    // ──────────────────────────────────────────────
    // 格式检测
    // ──────────────────────────────────────────────

    /**
     * 检测 blob 属于哪种格式。
     */
    enum class Format {
        ESN1, ESN6, UNKNOWN
    }

    fun detect(data: ByteArray): Format {
        if (startsWith(data, MAGIC_BYTES_ESN1)) return Format.ESN1
        if (startsWith(data, MAGIC_BYTES_ESN6)) return Format.ESN6
        return Format.UNKNOWN
    }

    fun isEsn1(data: ByteArray): Boolean = startsWith(data, MAGIC_BYTES_ESN1)
    fun isEsn6(data: ByteArray): Boolean = startsWith(data, MAGIC_BYTES_ESN6)

    // ──────────────────────────────────────────────
    // 工具
    // ──────────────────────────────────────────────

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) {
            if (data[i] != prefix[i]) return false
        }
        return true
    }

    /**
     * 估算 DataOutputStream.writeUTF 写入后的字节数（2 字节长度前缀 + UTF-8 字节）。
     * 标准 UTF-8 对补充平面字符用 4 字节，modified UTF-8 用 6 字节；
     * 这里按 modified UTF-8 的上界计算，保证估算只大不小。
     */
    private fun utfSize(s: String): Int {
        var bytes = 2  // writeUTF 的 2 字节长度前缀
        for (ch in s) {
            val c = ch.code
            when {
                c == 0 -> bytes += 2
                c <= 0x7F -> bytes += 1
                c <= 0x7FF -> bytes += 2
                c in 0xD800..0xDFFF -> bytes += 3  // 每个 surrogate 在 modified UTF-8 中为 3 字节（共 6）
                else -> bytes += 3
            }
        }
        return bytes
    }

    /**
     * 对 NBT 数据进行 gzip 压缩（供插件/模组层调用）。
     */
    fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    /**
     * 对 gzip 压缩的数据进行解压（供插件/模组层调用）。
     * 为避免 gzip 炸弹，调用方可通过 maxSize 限制解压后大小（默认 10 MB）。
     */
    fun gunzip(data: ByteArray, maxSize: Int = 10 * 1024 * 1024): ByteArray {
        val input = GZIPInputStream(ByteArrayInputStream(data))
        try {
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxSize) {
                    throw IllegalArgumentException("gunzip output exceeds limit $maxSize")
                }
                bos.write(buf, 0, n)
            }
            return bos.toByteArray()
        } finally {
            input.close()
        }
    }

    /**
     * 计算 SHA-256 哈希（用于批次校验）。
     */
    fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    fun sha256Hex(data: ByteArray): String {
        return sha256(data).joinToString("") { "%02x".format(it) }
    }
}