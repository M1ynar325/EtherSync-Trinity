package com.etherstories.eslink.core.protocol

import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ESLink Hub 服务端。
 *
 * 职责：
 *  - 鉴权（HMAC 令牌 + SHA-256）
 *  - 路由（根据 server_code 转发到对应客户端）
 *  - 中继转发（不存储传输数据）
 *
 * 监听端口：3307（默认）
 */
class HubServer(
    private val port: Int = 3307,
    private val secretKey: ByteArray
) : AutoCloseable {

    companion object {
        private const val READ_TIMEOUT_MS = 15000L
        private const val MAX_CLIENTS = 256
    }

    private val running = AtomicBoolean(true)
    @Volatile private var serverSocket: ServerSocket? = null
    private val clients = ConcurrentHashMap<String, ClientSession>()

    /**
     * 客户端会话。
     */
    data class ClientSession(
        val serverCode: String,
        val socket: Socket,
        val connectedAt: Long = System.currentTimeMillis()
    ) {
        val outputStream: OutputStream get() = socket.getOutputStream()
        val inputStream: InputStream get() = socket.getInputStream()
    }

    /** 当前在线服务器代码列表 */
    fun onlineServers(): List<String> = clients.keys.toList()

    fun clientCount(): Int = clients.size

    /**
     * 启动服务端（异步，立即返回）。
     */
    fun start() {
        running.set(true)
        Thread {
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                System.out.println("[HubServer] 监听端口 $port")

                while (running.get()) {
                    try {
                        val client = ss.accept()
                        client.soTimeout = READ_TIMEOUT_MS.toInt()
                        client.tcpNoDelay = true

                        if (clients.size >= MAX_CLIENTS) {
                            System.err.println("[HubServer] 客户端数量已达上限，拒绝连接: ${client.remoteSocketAddress}")
                            client.close()
                            continue
                        }

                        System.out.println("[HubServer] 新连接: ${client.remoteSocketAddress}")
                        handleClient(client)
                    } catch (e: SocketException) {
                        if (running.get()) {
                            System.err.println("[HubServer] 接受连接异常: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    System.err.println("[HubServer] 启动失败: ${e.message}")
                }
            }
        }.apply {
            name = "eslink-hub-accepter"
            isDaemon = true
            start()
        }
    }

    /**
     * 停止服务端，断开所有客户端。
     */
    override fun close() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        for ((_, session) in clients) {
            try { session.socket.close() } catch (_: Exception) {}
        }
        clients.clear()
    }

    // ──────────────────────────────────────────────
    // 内部：客户端处理
    // ──────────────────────────────────────────────

    private fun handleClient(socket: Socket) {
        Thread {
            var serverCode: String? = null
            try {
                val inputStream = socket.getInputStream()
                val outputStream = socket.getOutputStream()

                // 1. 握手（鉴权）
                val handshakePacket = try {
                    readPacket(inputStream)
                } catch (e: SocketTimeoutException) {
                    sendError(outputStream, "握手超时")
                    socket.close()
                    return@Thread
                }

                if (handshakePacket == null) {
                    // readPacket 返回 null 可能是 magic 不对、CRC 校验失败、或 HMAC 密钥不一致
                    sendError(outputStream, "握手失败：帧格式错误或密钥不一致")
                    socket.close()
                    return@Thread
                }

                if (handshakePacket.type != Frame.Type.HANDSHAKE) {
                    sendError(outputStream, "握手失败：期望 HANDSHAKE，收到 ${handshakePacket.type}")
                    socket.close()
                    return@Thread
                }

                serverCode = String(handshakePacket.payload, Charsets.UTF_8)
                if (serverCode.isBlank()) {
                    sendError(outputStream, "握手失败：服务器代码为空")
                    socket.close()
                    return@Thread
                }

                System.out.println("[HubServer] 客户端认证: $serverCode")

                // 检查是否已存在相同 serverCode 的连接
                val oldSession = clients.remove(serverCode)
                if (oldSession != null) {
                    try {
                        val disconnect = Frame.encode(Frame.Type.DISCONNECT,
                            "新连接已建立".toByteArray(Charsets.UTF_8), secretKey)
                        oldSession.outputStream.write(disconnect)
                        oldSession.outputStream.flush()
                        oldSession.socket.close()
                    } catch (_: Exception) {}
                    System.out.println("[HubServer] 已断开旧连接: $serverCode")
                }

                // 发送握手确认
                val ack = Frame.encode(Frame.Type.HANDSHAKE_ACK,
                    serverCode.toByteArray(Charsets.UTF_8), secretKey)
                outputStream.write(ack)
                outputStream.flush()

                // 注册客户端
                clients[serverCode] = ClientSession(serverCode, socket)
                System.out.println("[HubServer] 客户端已注册: $serverCode (当前在线: ${clients.size})")

                // 广播服务器列表更新
                broadcastServerList()

                // 2. 主循环：读取并转发包
                while (running.get()) {
                    val packet = try {
                        readPacket(inputStream)
                    } catch (e: SocketTimeoutException) {
                        continue  // 超时正常，继续等待
                    } ?: break  // EOF 或解码失败

                    // 处理心跳
                    if (packet.type == Frame.Type.HEARTBEAT) {
                        val ack = Frame.encode(Frame.Type.HEARTBEAT_ACK, ByteArray(0), secretKey)
                        outputStream.write(ack)
                        outputStream.flush()
                        continue
                    }

                    // 其他包走路由
                    routePacket(serverCode, packet, outputStream)
                }
            } catch (e: SocketException) {
                // 连接断开
            } catch (e: EOFException) {
                // 正常断开
            } catch (e: Exception) {
                if (running.get()) {
                    System.err.println("[HubServer] 客户端处理异常 (${serverCode}): ${e.message}")
                }
            } finally {
                if (serverCode != null) {
                    clients.remove(serverCode)
                    System.out.println("[HubServer] 客户端已断开: $serverCode (当前在线: ${clients.size})")
                    broadcastServerList()
                }
                try { socket.close() } catch (_: Exception) {}
            }
        }.apply {
            name = "eslink-hub-client-${socket.port}"
            isDaemon = true
            start()
        }
    }

    // ──────────────────────────────────────────────
    // 内部：路由
    // ──────────────────────────────────────────────

    /**
     * 路由包到目标客户端。
     *
     * 包 payload 格式（由发送方编码）：
     *   [targetCodeLen: 1B] [targetCode: targetCodeLen] [messagePayload: ...]
     *
     * 转发时，Hub 将 payload 改写为：
     *   [fromCodeLen: 1B] [fromCode: fromCodeLen] [messagePayload: ...]
     *
     * 特殊目标：
     *   "HUB" — Hub 内部命令
     *   "*"   — 广播给所有其他客户端
     */
    private fun routePacket(fromCode: String, packet: Frame.Packet, senderOutput: OutputStream) {
        try {
            if (packet.payload.isEmpty()) return

            val dis = DataInputStream(ByteArrayInputStream(packet.payload))
            val targetCodeLen = dis.readUnsignedByte()
            if (targetCodeLen <= 0 || targetCodeLen > 32) return

            val targetCodeBytes = ByteArray(targetCodeLen)
            dis.readFully(targetCodeBytes)
            val targetCode = String(targetCodeBytes, Charsets.UTF_8)

            val messagePayload = if (packet.payload.size > 1 + targetCodeLen) {
                packet.payload.copyOfRange(1 + targetCodeLen, packet.payload.size)
            } else {
                ByteArray(0)
            }

            when (targetCode) {
                "HUB" -> {
                    handleHubCommand(fromCode, packet, messagePayload, senderOutput)
                }
                "*" -> {
                    broadcastToOthers(fromCode, packet.type, messagePayload)
                }
                else -> {
                    val targetSession = clients[targetCode]
                    if (targetSession != null) {
                        relayTo(targetSession, fromCode, packet.type, messagePayload)
                    } else {
                        // 目标不在线
                        val errorPayload = ByteArrayOutputStream()
                        val errorDos = DataOutputStream(errorPayload)
                        errorDos.writeByte(0)  // fromCodeLen = 0 (Hub)
                        errorDos.write("TARGET_OFFLINE:$targetCode".toByteArray(Charsets.UTF_8))
                        val errorPacket = Frame.encode(Frame.Type.ERROR,
                            errorPayload.toByteArray(), secretKey)
                        senderOutput.write(errorPacket)
                        senderOutput.flush()
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[HubServer] 路由异常: ${e.message}")
        }
    }

    private fun relayTo(target: ClientSession, fromCode: String, type: Frame.Type, payload: ByteArray) {
        val relayPayload = ByteArrayOutputStream()
        val relayDos = DataOutputStream(relayPayload)
        relayDos.writeByte(fromCode.length)
        relayDos.write(fromCode.toByteArray(Charsets.UTF_8))
        relayDos.write(payload)
        val relayPacket = Frame.encode(type, relayPayload.toByteArray(), secretKey)
        try {
            target.outputStream.write(relayPacket)
            target.outputStream.flush()
        } catch (_: Exception) {}
    }

    private fun broadcastToOthers(fromCode: String, type: Frame.Type, payload: ByteArray) {
        for ((code, session) in clients) {
            if (code != fromCode) {
                relayTo(session, fromCode, type, payload)
            }
        }
    }

    /**
     * 处理 Hub 内部命令（目标为 "HUB" 的包）。
     */
    private fun handleHubCommand(
        fromCode: String,
        originalPacket: Frame.Packet,
        payload: ByteArray,
        senderOutput: OutputStream
    ) {
        when (originalPacket.type) {
            Frame.Type.SERVER_LIST -> {
                val serverList = clients.keys.toList()
                val responsePayload = ByteArrayOutputStream()
                val dos = DataOutputStream(responsePayload)
                dos.writeShort(serverList.size)
                for (code in serverList) {
                    val codeBytes = code.toByteArray(Charsets.UTF_8)
                    dos.writeByte(codeBytes.size)
                    dos.write(codeBytes)
                }
                val response = Frame.encode(Frame.Type.SERVER_LIST_RESP,
                    responsePayload.toByteArray(), secretKey)
                senderOutput.write(response)
                senderOutput.flush()
            }
            Frame.Type.SERVER_HELLO -> {
                broadcastToOthers(fromCode, Frame.Type.SERVER_HELLO, payload)
            }
            Frame.Type.SERVER_UPDATE -> {
                // 业务消息广播（聊天/通知等 targetCode="HUB" 的消息）
                broadcastToOthers(fromCode, Frame.Type.SERVER_UPDATE, payload)
            }
            else -> {
                val errorPayload = "UNKNOWN_COMMAND".toByteArray(Charsets.UTF_8)
                val errorPacket = Frame.encode(Frame.Type.ERROR, errorPayload, secretKey)
                senderOutput.write(errorPacket)
                senderOutput.flush()
            }
        }
    }

    /**
     * 广播服务器列表给所有在线客户端。
     */
    private fun broadcastServerList() {
        val serverList = clients.keys.toList()
        val responsePayload = ByteArrayOutputStream()
        val dos = DataOutputStream(responsePayload)
        dos.writeShort(serverList.size)
        for (code in serverList) {
            val codeBytes = code.toByteArray(Charsets.UTF_8)
            dos.writeByte(codeBytes.size)
            dos.write(codeBytes)
        }
        val response = Frame.encode(Frame.Type.SERVER_LIST_RESP,
            responsePayload.toByteArray(), secretKey)

        for ((_, session) in clients) {
            try {
                session.outputStream.write(response)
                session.outputStream.flush()
            } catch (_: Exception) {}
        }
    }

    // ──────────────────────────────────────────────
    // 内部：工具方法
    // ──────────────────────────────────────────────

    private fun sendError(outputStream: OutputStream, message: String) {
        try {
            val errorPacket = Frame.encode(Frame.Type.ERROR,
                message.toByteArray(Charsets.UTF_8), secretKey)
            outputStream.write(errorPacket)
            outputStream.flush()
        } catch (_: Exception) {}
    }

    /**
     * 从输入流读取一个完整的帧。
     * 超时时抛 SocketTimeoutException，EOF 或解码失败返回 null。
     */
    private fun readPacket(inputStream: InputStream): Frame.Packet? {
        val header = ByteArray(Frame.HEADER_SIZE)
        readFully(inputStream, header)

        val dis = DataInputStream(ByteArrayInputStream(header))
        val magic = dis.readUnsignedShort()
        if (magic != Frame.MAGIC.toInt()) return null

        val typeId = dis.readByte()
        val type = Frame.Type.fromId(typeId) ?: return null

        val payloadLen = dis.readInt()
        if (payloadLen < 0 || payloadLen > Frame.MAX_PAYLOAD) return null

        val restLen = payloadLen + Frame.CRC32_SIZE + Frame.HMAC_SIZE
        val rest = ByteArray(restLen)
        readFully(inputStream, rest)

        return Frame.decode(header + rest, secretKey)
    }

    /**
     * 读取精确字节数。遇到 EOF 抛 EOFException，超时抛 SocketTimeoutException。
     */
    private fun readFully(inputStream: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = inputStream.read(buffer, offset, buffer.size - offset)
            if (n < 0) throw EOFException("Unexpected EOF")
            offset += n
        }
    }
}