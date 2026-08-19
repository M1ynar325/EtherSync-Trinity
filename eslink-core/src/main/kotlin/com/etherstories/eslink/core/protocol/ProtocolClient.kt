package com.etherstories.eslink.core.protocol

import java.io.*
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * ESLink 协议客户端。
 *
 * 负责与 Hub 建立 TCP 长连接，发送/接收二进制帧。
 *
 * 线程模型：
 *  - connector 线程：建立连接、握手、重连
 *  - reader 线程：读取帧并分发给 listener
 *  - writer 线程：从发送队列取帧写入 socket
 *  - heartbeat 定时器：定期发送心跳
 *
 * 所有对外方法线程安全。
 */
class ProtocolClient(
    private val host: String,
    private val port: Int,
    private val serverCode: String,
    private val secretKey: ByteArray
) : AutoCloseable {

    companion object {
        private const val RECONNECT_DELAY_MS = 3000L
        private const val HEARTBEAT_INTERVAL_MS = 5000L
        private const val HEARTBEAT_TIMEOUT_MS = 15000L
        private const val READ_TIMEOUT_MS = 10000L
        private const val MAX_RETRY = 5
    }

    // 状态（线程安全）
    private val running = AtomicBoolean(true)
    private val connected = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null
    private val authenticated = AtomicBoolean(false)

    // 队列
    private val sendQueue = LinkedBlockingQueue<Frame.Packet>()

    // 事件监听
    private val listeners = CopyOnWriteArrayList<ProtocolListener>()

    /**
     * 协议事件监听器。
     */
    interface ProtocolListener {
        fun onConnected() {}
        fun onDisconnected(reason: String) {}
        fun onPacketReceived(packet: Frame.Packet) {}
        fun onHeartbeatTimeout() {}
        fun onError(error: String) {}
    }

    fun addListener(listener: ProtocolListener) { listeners.add(listener) }
    fun removeListener(listener: ProtocolListener) { listeners.remove(listener) }

    fun isConnected(): Boolean = connected.get() && authenticated.get()

    /**
     * 启动连接（异步，立即返回）。
     */
    fun start() {
        running.set(true)
        connect()
    }

    /**
     * 停止连接，释放资源。
     */
    override fun close() {
        val wasConnected = connected.getAndSet(false)
        running.set(false)
        // 先关 socket 让读写线程退出
        try { socket?.close() } catch (_: Exception) {}
        sendQueue.clear()
        if (wasConnected) {
            for (l in listeners) l.onDisconnected("Client closed")
        }
    }

    /**
     * 发送一个帧（异步，无响应）。
     * 如果未连接，帧被丢弃。
     */
    fun send(packet: Frame.Packet) {
        if (connected.get()) {
            sendQueue.put(packet)
        }
    }

    // ──────────────────────────────────────────────
    // 内部：连接管理
    // ──────────────────────────────────────────────

    private fun connect() {
        if (!running.get()) return
        Thread {
            var retry = 0
            while (running.get() && retry < MAX_RETRY) {
                try {
                    System.out.println("[ProtocolClient] 正在连接 $host:$port ...")
                    val s = Socket(host, port)
                    s.soTimeout = READ_TIMEOUT_MS.toInt()
                    s.tcpNoDelay = true
                    socket = s
                    connected.set(true)
                    retry = 0
                    System.out.println("[ProtocolClient] TCP 已连接 $host:$port")

                    // 握手（在启用心跳和读写线程之前）
                    val handshakeOk = handshake(s)
                    if (!handshakeOk) {
                        closeSocket(s, "Handshake failed")
                        if (running.get() && retry < MAX_RETRY) {
                            retry++
                            Thread.sleep(RECONNECT_DELAY_MS)
                            continue
                        }
                        break
                    }

                    // 启动读写线程
                    val readerThread = Thread { readLoop(s) }.apply {
                        name = "eslink-reader"
                        isDaemon = true
                        start()
                    }
                    val writerThread = Thread { writeLoop(s) }.apply {
                        name = "eslink-writer"
                        isDaemon = true
                        start()
                    }

                    // 启动心跳
                    val heartbeatTimer = startHeartbeat(s)

                    // 通知已连接
                    for (l in listeners) l.onConnected()

                    // 等待读写线程结束
                    try {
                        readerThread.join()
                        writerThread.join()
                    } finally {
                        heartbeatTimer.cancel()
                    }
                    break
                } catch (e: Exception) {
                    retry++
                    System.err.println("[ProtocolClient] 连接失败 ($retry/$MAX_RETRY): ${e.message}")
                    closeSocket(socket, "Connection failed")
                    if (running.get() && retry < MAX_RETRY) {
                        try { Thread.sleep(RECONNECT_DELAY_MS) }
                        catch (_: InterruptedException) { break }
                    }
                }
            }
            if (!running.get()) {
                for (l in listeners) l.onDisconnected("Stopped")
            } else if (retry >= MAX_RETRY) {
                for (l in listeners) l.onDisconnected("Max retries exceeded")
            }
        }.apply {
            name = "eslink-connector"
            isDaemon = true
            start()
        }
    }

    private fun closeSocket(s: Socket?, reason: String) {
        val wasConnected = connected.getAndSet(false)
        authenticated.set(false)
        try { s?.close() } catch (_: Exception) {}
        socket = null
        if (wasConnected) {
            for (l in listeners) l.onDisconnected(reason)
        }
    }

    // ──────────────────────────────────────────────
    // 内部：握手
    // ──────────────────────────────────────────────

    /**
     * @return true 如果握手成功
     */
    private fun handshake(s: Socket): Boolean {
        return try {
            val payload = serverCode.toByteArray(Charsets.UTF_8)
            val handshakePacket = Frame.encode(Frame.Type.HANDSHAKE, payload, secretKey)
            s.getOutputStream().write(handshakePacket)
            s.getOutputStream().flush()

            val response = readPacket(s.getInputStream())
            if (response == null) {
                System.err.println("[ProtocolClient] 握手失败：无响应（可能密钥不一致）")
                return false
            }

            when (response.type) {
                Frame.Type.HANDSHAKE_ACK -> {
                    authenticated.set(true)
                    System.out.println("[ProtocolClient] 握手成功，已认证为 $serverCode")
                    true
                }
                Frame.Type.ERROR -> {
                    val errorMsg = String(response.payload, Charsets.UTF_8)
                    System.err.println("[ProtocolClient] 握手被拒绝: $errorMsg")
                    false
                }
                else -> {
                    System.err.println("[ProtocolClient] 握手失败：收到意外的包类型 ${response.type}")
                    false
                }
            }
        } catch (e: Exception) {
            System.err.println("[ProtocolClient] 握手异常: ${e.message}")
            false
        }
    }

    // ──────────────────────────────────────────────
    // 内部：读写循环
    // ──────────────────────────────────────────────

    private fun readLoop(s: Socket) {
        try {
            while (running.get() && connected.get()) {
                val packet = try {
                    readPacket(s.getInputStream())
                } catch (e: SocketTimeoutException) {
                    // 超时正常，继续循环
                    continue
                }
                if (packet == null) break  // EOF 或解码失败
                handlePacket(packet)
            }
        } catch (e: EOFException) {
            System.err.println("[ProtocolClient] 连接断开 (EOF)")
        } catch (e: SocketException) {
            // 连接关闭，正常
        } catch (e: Exception) {
            if (running.get()) {
                System.err.println("[ProtocolClient] 读取异常: ${e.message}")
            }
        } finally {
            closeSocket(s, "Read loop ended")
        }
    }

    private fun writeLoop(s: Socket) {
        try {
            while (running.get() && connected.get()) {
                val packet = try {
                    sendQueue.poll(1, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    break
                } ?: continue

                try {
                    val data = Frame.encode(packet.type, packet.payload, secretKey)
                    s.getOutputStream().write(data)
                    s.getOutputStream().flush()
                } catch (e: Exception) {
                    System.err.println("[ProtocolClient] 发送异常: ${e.message}")
                    break
                }
            }
        } catch (e: Exception) {
            System.err.println("[ProtocolClient] 写入循环异常: ${e.message}")
        } finally {
            closeSocket(s, "Write loop ended")
        }
    }

    /**
     * 从输入流读取一个完整的帧。超时时抛 SocketTimeoutException。
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

    // ──────────────────────────────────────────────
    // 内部：包处理
    // ──────────────────────────────────────────────

    private fun handlePacket(packet: Frame.Packet) {
        when (packet.type) {
            Frame.Type.HEARTBEAT -> {
                // 回复心跳确认
                try {
                    val ack = Frame.encode(Frame.Type.HEARTBEAT_ACK, ByteArray(0), secretKey)
                    socket?.getOutputStream()?.write(ack)
                    socket?.getOutputStream()?.flush()
                } catch (_: Exception) {}
                // 同时通知监听器（例如让 heartbeat tracker 知道对端存活）
                for (l in listeners) l.onPacketReceived(packet)
            }
            Frame.Type.DISCONNECT -> {
                val reason = try { String(packet.payload, Charsets.UTF_8) } catch (_: Exception) { "未知" }
                System.out.println("[ProtocolClient] 服务器要求断开: $reason")
                closeSocket(socket, "Server disconnect: $reason")
            }
            else -> {
                // HEARTBEAT_ACK 及其他所有包都分发给监听器
                for (l in listeners) l.onPacketReceived(packet)
            }
        }
    }

    // ──────────────────────────────────────────────
    // 内部：心跳
    // ──────────────────────────────────────────────

    private fun startHeartbeat(s: Socket): Timer {
        val timer = Timer("eslink-heartbeat", true)
        val lastAckRef = AtomicReference(System.currentTimeMillis())

        // 监听 HEARTBEAT_ACK 来更新 lastAck
        val ackListener = object : ProtocolListener {
            override fun onPacketReceived(packet: Frame.Packet) {
                if (packet.type == Frame.Type.HEARTBEAT_ACK) {
                    lastAckRef.set(System.currentTimeMillis())
                }
            }
        }
        listeners.add(ackListener)

        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (!connected.get()) return
                try {
                    val ping = Frame.encode(Frame.Type.HEARTBEAT, ByteArray(0), secretKey)
                    s.getOutputStream().write(ping)
                    s.getOutputStream().flush()

                    // 检查上次 ACK 是否超时
                    val elapsed = System.currentTimeMillis() - lastAckRef.get()
                    if (elapsed >= HEARTBEAT_TIMEOUT_MS) {
                        System.err.println("[ProtocolClient] 心跳超时 (${elapsed}ms)")
                        for (l in listeners) l.onHeartbeatTimeout()
                        closeSocket(s, "Heartbeat timeout")
                    }
                } catch (e: Exception) {
                    // 发送失败，检查是否已超时
                    val elapsed = System.currentTimeMillis() - lastAckRef.get()
                    if (elapsed >= HEARTBEAT_TIMEOUT_MS) {
                        for (l in listeners) l.onHeartbeatTimeout()
                        closeSocket(s, "Heartbeat timeout")
                    }
                }
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS)

        return timer
    }
}