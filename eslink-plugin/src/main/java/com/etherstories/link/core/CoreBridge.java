package com.etherstories.link.core;

import com.etherstories.eslink.core.protocol.Frame;
import com.etherstories.eslink.core.protocol.ProtocolClient;
import com.etherstories.eslink.core.serialization.SerializationService;
import com.etherstories.eslink.core.serialization.SerialFormat;
import com.etherstories.eslink.core.serialization.SplitConfig;
import com.etherstories.eslink.core.storage.Storage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * eslink-core 桥接层 — 管理 core 生命周期，暴露 Storage / Serialization / Hub 连接。
 *
 * 插件通过此类调用 eslink-core 的全部能力：
 *   - storage()   → SQLite 本地持久化（节点、队列、配置、服务器缓存）
 *   - serialization() → ESN1/ESN6 物品序列化
 *   - splitConfig()   → ESN6 拆包配置（白名单、熔断器）
 *   - connectHub()    → 连接到 Hub 中继服务器
 */
public final class CoreBridge {

    // ── Hub 消息类型（业务层） ──
    public static final byte MSG_CHEST_ITEM   = 0x10;  // 箱子物品推送
    public static final byte MSG_CHEST_ACK    = 0x11;  // 箱子物品确认
    public static final byte MSG_CHEST_BOUNCE = 0x12;  // 箱子物品退回
    public static final byte MSG_CHAT         = 0x20;  // 聊天消息
    public static final byte MSG_ALERT        = 0x21;  // 通知/警报
    public static final byte MSG_IO_EVENT     = 0x30;  // 红石事件
    public static final byte MSG_NODE_REGISTER = 0x50;  // 节点注册（创建箱子/红石节点时广播）
    public static final byte MSG_NODE_UNREGISTER = 0x51; // 节点注销（删除时广播）
    public static final byte MSG_NODE_SYNC   = 0x52;  // 请求全量节点同步
    public static final byte MSG_NODE_SYNC_RESP = 0x53; // 节点同步响应
    public static final byte MSG_SERVER_INFO  = 0x40;  // 服务器信息

    private final File dataFolder;
    private final Storage storage;
    private final SerializationService serialization;
    private final SplitConfig splitConfig;
    private ProtocolClient hubClient;
    private final CopyOnWriteArrayList<HubListener> hubListeners = new CopyOnWriteArrayList<>();
    private final Map<Byte, List<MessageHandler>> messageHandlers = new HashMap<>();

    public CoreBridge(File dataFolder) {
        this.dataFolder = dataFolder;
        dataFolder.mkdirs();

        // SQLite 存储
        this.storage = new Storage(new File(dataFolder, "eslink.db").getAbsolutePath());

        // 拆包配置
        File splitFile = new File(dataFolder, "eslink-split.properties");
        this.splitConfig = new SplitConfig(splitFile);
        this.splitConfig.load();

        // 序列化服务
        this.serialization = new SerializationService(splitConfig);
    }

    // ── 生命周期 ──

    /** 初始化存储（打开 SQLite，建表）。 */
    public boolean startStorage() {
        return storage.connect();
    }

    /**
     * 连接到 Hub 中继服务器。
     *
     * @param host      Hub 主机名
     * @param port      Hub 端口（默认 3307）
     * @param serverCode 本服务器代号
     * @param secretKey  共享密钥
     */
    public void connectHub(String host, int port, String serverCode, byte[] secretKey) {
        if (hubClient != null) {
            hubClient.close();
        }
        hubClient = new ProtocolClient(host, port, serverCode, secretKey);
        hubClient.addListener(new ProtocolClient.ProtocolListener() {
            @Override
            public void onConnected() {
                for (HubListener l : hubListeners) l.onHubConnected();
            }

            @Override
            public void onDisconnected(String reason) {
                for (HubListener l : hubListeners) l.onHubDisconnected(reason);
            }

            @Override
            public void onPacketReceived(Frame.Packet packet) {
                if (packet.getType() == Frame.Type.SERVER_UPDATE) {
                    dispatchMessage(packet);
                }
                for (HubListener l : hubListeners) l.onHubPacket(packet);
            }

            @Override
            public void onHeartbeatTimeout() {
                for (HubListener l : hubListeners) l.onHubHeartbeatTimeout();
            }

            @Override
            public void onError(String error) {
                for (HubListener l : hubListeners) l.onHubError(error);
            }
        });
        hubClient.start();
    }

    /** 断开 Hub 连接并关闭存储。 */
    public void shutdown() {
        if (hubClient != null) {
            hubClient.close();
            hubClient = null;
        }
        storage.close();
    }

    // ── 访问器 ──

    public Storage storage() { return storage; }
    public SerializationService serialization() { return serialization; }
    public SplitConfig splitConfig() { return splitConfig; }
    public ProtocolClient hubClient() { return hubClient; }
    public boolean isHubConnected() { return hubClient != null && hubClient.isConnected(); }

    // ── Hub 事件监听 ──

    public interface HubListener {
        default void onHubConnected() {}
        default void onHubDisconnected(String reason) {}
        default void onHubPacket(Frame.Packet packet) {}
        default void onHubHeartbeatTimeout() {}
        default void onHubError(String error) {}
    }

    public void addHubListener(HubListener listener) {
        hubListeners.add(listener);
    }

    public void removeHubListener(HubListener listener) {
        hubListeners.remove(listener);
    }

    // ── 便利方法：发送 Hub 消息 ──

    /**
     * 通过 Hub 发送数据到指定目标服务器。
     *
     * @param targetCode 目标服务器代号（"HUB" 表示广播到所有服务器）
     * @param packetType 包类型
     * @param payload    业务数据
     */
    public void sendToHub(String targetCode, Frame.Type packetType, byte[] payload) {
        if (hubClient == null || !hubClient.isConnected()) return;
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);
            // 路由前缀
            byte[] targetBytes = targetCode.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            dos.writeByte(targetBytes.length);
            dos.write(targetBytes);
            // 实际 payload
            dos.write(payload);
            dos.flush();
            Frame.Packet packet = new Frame.Packet(
                packetType,
                bos.toByteArray(),
                0,
                new byte[Frame.HMAC_SIZE]
            );
            hubClient.send(packet);
        } catch (Exception e) {
            System.err.println("[CoreBridge] 发送失败: " + e.getMessage());
        }
    }

    // ── 业务消息路由 ──

    /** 业务消息处理器 */
    public interface MessageHandler {
        /**
         * @param fromCode  发送方服务器代号
         * @param msgType   消息类型（MSG_* 常量）
         * @param payload   消息体
         */
        void handle(String fromCode, byte msgType, byte[] payload);
    }

    /** 注册消息处理器 */
    public void onMessage(byte msgType, MessageHandler handler) {
        messageHandlers.computeIfAbsent(msgType, k -> new ArrayList<>()).add(handler);
    }

    /** 分发收到的 Hub 消息到业务处理器 */
    private void dispatchMessage(Frame.Packet packet) {
        try {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(packet.getPayload()));
            int fromLen = dis.readByte() & 0xFF;
            byte[] fromBytes = new byte[fromLen];
            dis.readFully(fromBytes);
            String fromCode = new String(fromBytes, StandardCharsets.UTF_8);

            byte msgType = dis.readByte();
            byte[] payload = new byte[dis.available()];
            dis.readFully(payload);

            List<MessageHandler> handlers = messageHandlers.get(msgType);
            if (handlers != null) {
                for (MessageHandler h : handlers) {
                    try { h.handle(fromCode, msgType, payload); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            System.err.println("[CoreBridge] 消息分发失败: " + e + " payloadSize=" + packet.getPayload().length);
        }
    }

    // ── 便利发送方法 ──

    /** 发送箱子物品到目标服务器 */
    public void sendChestItem(String targetCode, byte[] esnData) {
        sendBusinessMsg(targetCode, MSG_CHEST_ITEM, esnData);
    }

    /** 发送箱子物品确认 */
    public void sendChestAck(String targetCode, long queueId) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            dos.writeLong(queueId);
            sendBusinessMsg(targetCode, MSG_CHEST_ACK, bos.toByteArray());
        } catch (Exception e) { /* ignore */ }
    }

    /** 发送箱子物品退回 */
    public void sendChestBounce(String targetCode, long queueId, String reason) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            dos.writeLong(queueId);
            dos.writeUTF(reason != null ? reason : "");
            sendBusinessMsg(targetCode, MSG_CHEST_BOUNCE, bos.toByteArray());
        } catch (Exception e) { /* ignore */ }
    }

    /** 发送聊天消息 */
    public void sendChat(String targetCode, byte[] chatPayload) {
        sendBusinessMsg(targetCode, MSG_CHAT, chatPayload);
    }

    /** 发送通知/警报 */
    public void sendAlert(String targetCode, byte[] alertPayload) {
        sendBusinessMsg(targetCode, MSG_ALERT, alertPayload);
    }

    /** 发送红石事件 */
    public void sendIoEvent(String targetCode, byte[] ioPayload) {
        sendBusinessMsg(targetCode, MSG_IO_EVENT, ioPayload);
    }

    /** 发送服务器信息 */
    public void sendServerInfo(String targetCode, byte[] infoPayload) {
        sendBusinessMsg(targetCode, MSG_SERVER_INFO, infoPayload);
    }

    /** 内部：打包业务消息并通过 Hub 发送 */

    /** 广播节点注册（创建箱子/红石节点时） */
    public void sendNodeRegister(String serverCode, String type, String role, int id, String serial,
                                  String pairCode, String world, int x, int y, int z, String ownerName) {
        if (hubClient == null || !hubClient.isConnected()) return;
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);
            dos.writeUTF(serverCode);
            dos.writeUTF(type);  // "chest" or "io"
            dos.writeUTF(role);  // "TX" or "RX"
            dos.writeInt(id);
            dos.writeUTF(serial != null ? serial : "");
            dos.writeUTF(pairCode != null ? pairCode : "");
            dos.writeUTF(world != null ? world : "");
            dos.writeInt(x);
            dos.writeInt(y);
            dos.writeInt(z);
            dos.writeUTF(ownerName != null ? ownerName : "");
            dos.flush();
            sendBusinessMsg("HUB", MSG_NODE_REGISTER, bos.toByteArray());
            System.out.println("[CoreBridge] 广播节点注册: " + type + " " + id + " " + role);
        } catch (Exception e) { /* ignore */ }
    }

    /** 广播节点注销 */
    public void sendNodeUnregister(String type, int id) {
        if (hubClient == null || !hubClient.isConnected()) return;
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);
            dos.writeUTF(type);
            dos.writeInt(id);
            dos.flush();
            sendBusinessMsg("HUB", MSG_NODE_UNREGISTER, bos.toByteArray());
        } catch (Exception e) { /* ignore */ }
    }

    /** 发送节点同步请求 */
    public void sendNodeSync() {
        System.out.println("[CoreBridge] 发送节点同步请求");
        sendBusinessMsg("HUB", MSG_NODE_SYNC, new byte[0]);
    }

    public void sendBusinessMsg(String targetCode, byte msgType, byte[] payload) {
        if (hubClient == null || !hubClient.isConnected()) return;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            // 路由前缀：目标服务器代号
            byte[] targetBytes = targetCode.getBytes(StandardCharsets.UTF_8);
            dos.writeByte(targetBytes.length);
            dos.write(targetBytes);
            // 业务消息类型
            dos.writeByte(msgType);
            // 业务 payload
            dos.write(payload);
            dos.flush();
            Frame.Packet packet = new Frame.Packet(
                Frame.Type.SERVER_UPDATE,
                bos.toByteArray(),
                0,
                new byte[Frame.HMAC_SIZE]
            );
            hubClient.send(packet);
        } catch (Exception e) {
            System.err.println("[CoreBridge] 发送业务消息失败: " + e.getMessage());
        }
    }

}