package com.etherstories.eslink.core.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32C

/**
 * ESLink 自定义二进制帧格式。
 *
 * 帧结构：
 *   [Magic: 2B] [Type: 1B] [PayloadLen: 4B] [Payload: PayloadLen] [CRC32: 4B] [HMAC: 32B]
 *
 * Magic 固定为 0x4553 ("ES")
 * CRC32 覆盖 Magic + Type + PayloadLen + Payload（不含 CRC32 和 HMAC 自身）
 * HMAC 使用 SHA-256，覆盖整个 Packet（Magic + Type + PayloadLen + Payload + CRC32）
 */
object Frame {

    const val MAGIC: Short = 0x4553  // "ES" in ASCII
    const val HEADER_SIZE = 7       // Magic(2) + Type(1) + PayloadLen(4)
    const val CRC32_SIZE = 4        // CRC32C
    const val HMAC_SIZE = 32        // SHA-256
    const val OVERHEAD = HEADER_SIZE + CRC32_SIZE + HMAC_SIZE

    /** 最大 payload 长度（10 MB） */
    const val MAX_PAYLOAD = 10 * 1024 * 1024

    /**
     * 包类型。
     */
    enum class Type(val id: Byte) {
        // 系统
        HANDSHAKE(0x01),        // 握手 / 鉴权
        HANDSHAKE_ACK(0x02),   // 握手确认
        HEARTBEAT(0x03),        // 心跳
        HEARTBEAT_ACK(0x04),   // 心跳确认
        DISCONNECT(0x05),      // 断开连接

        // 节点
        NODE_REGISTER(0x10),   // 注册节点
        NODE_UNREGISTER(0x11), // 注销节点
        NODE_UPDATE(0x12),     // 更新节点状态
        NODE_LIST(0x13),       // 列出节点
        NODE_LIST_RESP(0x14),  // 节点列表响应

        // 队列
        QUEUE_PUSH(0x20),      // 推送物品到队列
        QUEUE_PULL(0x21),      // 拉取待接收物品
        QUEUE_PULL_RESP(0x22), // 拉取响应
        QUEUE_ACK(0x23),       // 确认接收
        QUEUE_BOUNCE(0x24),    // 退回

        // 批次
        BATCH_OPEN(0x30),      // 开启批次
        BATCH_COMMIT(0x31),    // 提交批次
        BATCH_STATUS(0x32),    // 批次状态

        // 服务器信息
        SERVER_HELLO(0x40),    // 服务器广播
        SERVER_UPDATE(0x41),   // 服务器信息更新
        SERVER_LIST(0x42),     // 请求服务器列表
        SERVER_LIST_RESP(0x43),// 服务器列表响应

        // 注册表
        REGISTRY_PUSH(0x50),   // 推送物品注册表
        REGISTRY_PULL(0x51),   // 拉取注册表
        REGISTRY_PULL_RESP(0x52), // 注册表响应

        // 错误
        ERROR(0xFF.toByte());  // 错误响应

        companion object {
            private val map = values().associateBy { it.id }
            fun fromId(id: Byte): Type? = map[id]
        }
    }

    /**
     * 解码后的帧。
     */
    data class Packet(
        val type: Type,
        val payload: ByteArray,
        val crc32: Int,
        val hmac: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Packet) return false
            return type == other.type &&
                    payload.contentEquals(other.payload) &&
                    crc32 == other.crc32 &&
                    hmac.contentEquals(other.hmac)
        }

        override fun hashCode(): Int {
            var result = type.hashCode()
            result = 31 * result + payload.contentHashCode()
            result = 31 * result + crc32
            result = 31 * result + hmac.contentHashCode()
            return result
        }
    }

    /**
     * 编码一个帧。
     *
     * @param type 包类型
     * @param payload 负载数据
     * @param hmacKey HMAC 密钥（如果为 null 或空，HMAC 填充 0）
     * @return 编码后的字节数组
     */
    fun encode(type: Type, payload: ByteArray, hmacKey: ByteArray? = null): ByteArray {
        if (payload.size > MAX_PAYLOAD) {
            throw IllegalArgumentException("Payload too large: ${payload.size}")
        }
        val bos = ByteArrayOutputStream(HEADER_SIZE + payload.size + CRC32_SIZE + HMAC_SIZE)
        val dos = DataOutputStream(bos)

        // Magic
        dos.writeShort(MAGIC.toInt())
        // Type
        dos.writeByte(type.id.toInt())
        // Payload length
        dos.writeInt(payload.size)
        // Payload
        dos.write(payload)

        // CRC32C (covers magic + type + payloadLen + payload)
        val frameData = bos.toByteArray()
        val crc = crc32(frameData)
        dos.writeInt(crc)

        // HMAC-SHA256 (covers magic + type + payloadLen + payload + CRC32)
        val signedData = bos.toByteArray()  // now includes CRC32
        val hmac = if (hmacKey != null && hmacKey.isNotEmpty()) {
            hmacSha256(signedData, hmacKey)
        } else {
            ByteArray(HMAC_SIZE) // zeros
        }
        dos.write(hmac)

        return bos.toByteArray()
    }

    /**
     * 解码一个帧。
     *
     * @param data 完整的帧数据
     * @param hmacKey HMAC 密钥（如果为 null 或空，不验证 HMAC）
     * @return 解码后的 Packet，如果数据无效返回 null
     */
    fun decode(data: ByteArray, hmacKey: ByteArray? = null): Packet? {
        if (data.size < OVERHEAD) return null

        val dis = DataInputStream(ByteArrayInputStream(data))

        return try {
            // Magic
            val magic = dis.readUnsignedShort()
            if (magic != MAGIC.toInt()) return null

            // Type
            val typeId = dis.readByte()
            val type = Type.fromId(typeId) ?: return null

            // Payload length
            val payloadLen = dis.readInt()
            if (payloadLen < 0 || data.size < HEADER_SIZE + payloadLen + CRC32_SIZE + HMAC_SIZE) {
                return null
            }

            // Payload
            val payload = ByteArray(payloadLen)
            if (payloadLen > 0) dis.readFully(payload)

            // CRC32
            val crc = dis.readInt()

            // Verify CRC32
            val headerLen = HEADER_SIZE + payloadLen
            val expectedCrc = crc32(data.copyOfRange(0, headerLen))
            if (crc != expectedCrc) return null

            // HMAC
            val hmac = ByteArray(HMAC_SIZE)
            dis.readFully(hmac)

            // Verify HMAC
            if (hmacKey != null && hmacKey.isNotEmpty()) {
                val expectedHmac = hmacSha256(data.copyOfRange(0, headerLen + CRC32_SIZE), hmacKey)
                if (!hmac.contentEquals(expectedHmac)) return null
            }

            Packet(type, payload, crc, hmac)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 计算 CRC32C 校验和。
     */
    private fun crc32(data: ByteArray): Int {
        val crc = CRC32C()
        crc.update(data)
        return crc.value.toInt()
    }

    /**
     * 计算 HMAC-SHA256。
     */
    private fun hmacSha256(data: ByteArray, key: ByteArray): ByteArray {
        // Simple HMAC-SHA256 implementation without external dependencies
        val blockSize = 64
        val innerPad = ByteArray(blockSize) { 0x36 }
        val outerPad = ByteArray(blockSize) { 0x5C }

        // If key is longer than block size, hash it
        val adjustedKey = if (key.size > blockSize) {
            MessageDigest.getInstance("SHA-256").digest(key)
        } else {
            key
        }

        // XOR key with pads
        for (i in adjustedKey.indices) {
            innerPad[i] = ((innerPad[i].toInt() xor adjustedKey[i].toInt()) and 0xFF).toByte()
            outerPad[i] = ((outerPad[i].toInt() xor adjustedKey[i].toInt()) and 0xFF).toByte()
        }

        // Inner hash: H(key XOR ipad || message)
        val innerDigest = MessageDigest.getInstance("SHA-256")
        innerDigest.update(innerPad)
        innerDigest.update(data)
        val innerHash = innerDigest.digest()

        // Outer hash: H(key XOR opad || innerHash)
        val outerDigest = MessageDigest.getInstance("SHA-256")
        outerDigest.update(outerPad)
        outerDigest.update(innerHash)
        return outerDigest.digest()
    }
}