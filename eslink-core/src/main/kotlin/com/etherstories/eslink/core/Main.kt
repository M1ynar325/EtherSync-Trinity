package com.etherstories.eslink.core

import com.etherstories.eslink.core.model.LinkLog
import com.etherstories.eslink.core.protocol.Frame
import com.etherstories.eslink.core.protocol.HubServer
import com.etherstories.eslink.core.protocol.ProtocolClient
import com.etherstories.eslink.core.serialization.SerializationService
import com.etherstories.eslink.core.serialization.SerialFormat
import com.etherstories.eslink.core.serialization.SplitConfig
import com.etherstories.eslink.core.storage.Storage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

/**
 * eslink-core 入口。
 *
 * 启动方式：
 *   java -jar eslink-core.jar [--db <path>] [--hub <host:port>] [--code <serverCode>] [--key <secretKey>]
 *
 * 模式：
 *   1. 如果指定 --hub，作为客户端连接到 Hub
 *   2. 如果指定 --server，启动 Hub 服务端
 *   3. 否则运行测试/演示模式
 */
fun main(args: Array<String>) {
    println("=== ESLink Core v0.3.0 ===")
    println("Starting...")

    // 解析命令行参数
    val dbPath = argValue(args, "--db", "eslink-core.db")
    val hubHost = optionalArgValue(args, "--hub")
    val serverCode = argValue(args, "--code", "TEST")
    val secretKey = argValue(args, "--key", "default-secret-key").toByteArray(Charsets.UTF_8)
    val isServer = args.contains("--server")

    println("Database: $dbPath")
    println("Server Code: $serverCode")

    if (isServer) {
        // Hub 服务端模式
        val port = argValue(args, "--port", "3307").toInt()
        println("启动 Hub 服务端，端口: $port")
        val hub = HubServer(port, secretKey)
        hub.start()
        println("Hub 服务端已启动，按 Ctrl+C 停止")
        Runtime.getRuntime().addShutdownHook(Thread {
            hub.close()
            println("Hub 服务端已停止")
        })
        // 保持主线程运行
        Thread.currentThread().join()
        return
    }

    // 初始化存储
    val storage = Storage(dbPath)
    if (!storage.connect()) {
        System.err.println("无法连接数据库，退出")
        return
    }
    println("数据库连接成功")

    if (hubHost != null) {
        // 客户端模式：连接到 Hub
        val parts = hubHost.split(':')
        val host = parts[0]
        val port = parts.getOrElse(1) { "3307" }.toInt()
        println("连接到 Hub: $host:$port")

        val client = ProtocolClient(host, port, serverCode, secretKey)
        client.addListener(object : ProtocolClient.ProtocolListener {
            override fun onConnected() {
                println("已连接到 Hub")
            }

            override fun onDisconnected(reason: String) {
                println("与 Hub 断开连接: $reason")
            }

            override fun onPacketReceived(packet: Frame.Packet) {
                println("收到包: type=${packet.type}, payloadSize=${packet.payload.size}")
            }

            override fun onHeartbeatTimeout() {
                println("心跳超时")
            }

            override fun onError(error: String) {
                println("协议错误: $error")
            }
        })
        client.start()

        // 测试：发送 SERVER_HELLO（目标 HUB，由 Hub 广播给其他服务器）
        Thread.sleep(1000)
        if (client.isConnected()) {
            val helloPayload = ByteArrayOutputStream()
            val dos = DataOutputStream(helloPayload)
            // 路由前缀：targetCode = "HUB"
            dos.writeByte(3)  // "HUB".length
            dos.write("HUB".toByteArray(Charsets.UTF_8))
            // 实际 payload：服务器名称
            val nameBytes = "测试服务器".toByteArray(Charsets.UTF_8)
            dos.writeByte(nameBytes.size)
            dos.write(nameBytes)
            val helloPacket = Frame.Packet(
                type = Frame.Type.SERVER_HELLO,
                payload = helloPayload.toByteArray(),
                crc32 = 0,
                hmac = ByteArray(Frame.HMAC_SIZE)
            )
            client.send(helloPacket)
            println("已发送 SERVER_HELLO")
        } else {
            println("未连接，跳过 SERVER_HELLO")
        }

        // 保持运行
        Runtime.getRuntime().addShutdownHook(Thread {
            client.close()
            storage.close()
            println("已关闭")
        })
        Thread.currentThread().join()
        return
    }

    // 测试/演示模式
    println("— 运行存储层测试 —")

    // 测试：插入节点
    val nodeId = UUID.randomUUID().toString()
    storage.insertNode(Storage.NodeRow(
        id = nodeId,
        type = "chest",
        role = "TX",
        serverCode = "ES2",
        world = "world",
        x = 100, y = 64, z = -200,
        ownerUuid = UUID.randomUUID().toString(),
        ownerName = "TestPlayer",
        status = "idle",
        pairCode = null,
        pairNodeId = null,
        extra = null,
        created = System.currentTimeMillis(),
        updated = System.currentTimeMillis()
    ))
    println("节点已插入: $nodeId")

    // 查询节点
    val node = storage.getNode(nodeId)
    println("查询节点: ${node?.let { "${it.type} ${it.role} @ ${it.world} ${it.x},${it.y},${it.z}" } ?: "未找到"}")

    // 列表节点
    val nodes = storage.listNodes("ES2")
    println("服务器 ES2 共有 ${nodes.size} 个节点")

    // 测试：插入队列
    val queueId = storage.insertQueue(Storage.QueueRow(
        id = 0,
        fromCode = "ES2",
        toCode = "SNC",
        pairCode = "ES2-SNC",
        itemKey = "minecraft:diamond",
        itemName = "钻石",
        amount = 64,
        status = "pending",
        blobB64 = null,
        nestedKeys = null,
        batchId = null,
        parentId = null,
        rowIndex = null,
        rowSha256 = null,
        returnSlots = 1,
        created = System.currentTimeMillis(),
        delivered = 0
    ))
    println("队列项已插入: $queueId")

    // 查询待发送
    val pending = storage.pendingTo("SNC")
    println("待发送到 SNC: ${pending.size} 项")

    // 测试：服务器缓存
    storage.upsertServerCache(Storage.ServerCacheRow(
        code = "SNC",
        displayName = "Sister Network Center",
        blurb = "姐妹服",
        color = "RED",
        icon = "CONCRETE",
        lastHeartbeat = System.currentTimeMillis()
    ))
    val cached = storage.getServerCache("SNC")
    println("服务器缓存: ${cached?.displayName} (${cached?.code})")

    // 测试：配置键值
    storage.setConfig("server.code", "ES2")
    storage.setConfig("server.name", "以太物语")
    val code = storage.getConfig("server.code")
    val name = storage.getConfig("server.name")
    println("配置: $code / $name")

    // 验证日志
    LinkLog.add("I 存储测试完成")
    LinkLog.add("I 节点: ${nodes.size} 个, 队列: ${pending.size} 项")
    println("日志条目: ${LinkLog.size()}")

    // 清理测试数据
    storage.deleteNode(nodeId)
    storage.deleteQueue(queueId ?: 0)
    println("测试数据已清理")

    // ──────────────────────────────────────────────
    // Step 4: 序列化层测试
    // ──────────────────────────────────────────────
    println("\n— 运行序列化层测试 —")
    testSerialization()

    // 关闭
    storage.close()
    println("=== 完成 ===")
}

/**
 * 序列化层功能验证。
 * 模拟插件层的 NBT 数据（用随机字节代替真实 NBT）。
 */
private fun testSerialization() {
    val service = SerializationService()

    // ── 测试 1: ESN1 单品往返 ──
    println("\n[1] ESN1 单品往返")
    val fakeNbt = ByteArray(128) { it.toByte() }
    val esn1 = service.encodeItem("minecraft:diamond_sword", 1, fakeNbt)
    println("  编码: ${esn1.size} bytes, magic=${esn1.copyOf(4).joinToString { "%02X".format(it) }}")
    val decoded = service.decode(esn1)
    when (decoded) {
        is SerializationService.DecodeResult.Single -> {
            println("  解码: ${decoded.item.itemKey} x${decoded.item.amount}, nbt=${decoded.item.nbtData.size} bytes")
            check(decoded.item.itemKey == "minecraft:diamond_sword") { "key 不匹配" }
            check(decoded.item.amount == 1) { "amount 不匹配" }
            check(decoded.item.nbtData.contentEquals(fakeNbt)) { "nbt 不匹配" }
            println("  ✓ 通过")
        }
        else -> println("  ✗ 失败: $decoded")
    }

    // ── 测试 2: ESN1 空 NBT ──
    println("\n[2] ESN1 空 NBT（key-only 降级）")
    val emptyNbt = ByteArray(0)
    val esn1Empty = service.encodeItem("minecraft:stone", 64, emptyNbt)
    println("  编码: ${esn1Empty.size} bytes")
    val decodedEmpty = service.decode(esn1Empty)
    when (decodedEmpty) {
        is SerializationService.DecodeResult.Single -> {
            println("  解码: ${decodedEmpty.item.itemKey} x${decodedEmpty.item.amount}, nbt=${decodedEmpty.item.nbtData.size} bytes")
            check(decodedEmpty.item.nbtData.isEmpty()) { "nbt 应为空" }
            println("  ✓ 通过")
        }
        else -> println("  ✗ 失败: $decodedEmpty")
    }

    // ── 测试 3: ESN6 容器拆包（模拟潜影盒） ──
    println("\n[3] ESN6 容器拆包（潜影盒，2 个内含）")
    // 配置：开启拆包，加白名单
    val config = service.config
    config.enabled = true
    config.addWhitelist("minecraft:shulker_box")
    config.addWhitelist("create:*")

    val innerItems = listOf(
        SerialFormat.Esn1Item("minecraft:diamond", 32, ByteArray(64) { (it + 1).toByte() }),
        SerialFormat.Esn1Item("minecraft:oak_planks", 16, ByteArray(48) { (it + 2).toByte() })
    )
    val containerNbt = ByteArray(256) { it.toByte() }  // 整包兜底 NBT
    val result = service.encodeContainer(
        containerKey = "minecraft:shulker_box",
        amount = 1,
        displayName = "宝物箱",
        address = "",
        nbtData = containerNbt,
        innerItems = innerItems
    )
    println("  格式: ${result.format}, 拆分: ${result.split}, 大小: ${result.data.size} bytes")
    check(result.format == SerialFormat.Format.ESN6) { "应为 ESN6" }
    check(result.split) { "应为拆分" }

    // 解码 ESN6
    val decodedContainer = service.decode(result.data)
    when (decodedContainer) {
        is SerializationService.DecodeResult.Container -> {
            val c = decodedContainer.container
            println("  解码: ${c.containerKey} x${c.amount}, name='${c.displayName}', addr='${c.address}'")
            println("  内含: ${c.innerItems.size} 项")
            for ((i, item) in c.innerItems.withIndex()) {
                println("    [$i] ${item.itemKey} x${item.amount}, nbt=${item.nbtData.size} bytes")
            }
            check(c.containerKey == "minecraft:shulker_box") { "容器 key 不匹配" }
            check(c.innerItems.size == 2) { "内含数量不匹配" }
            check(c.innerItems[0].itemKey == "minecraft:diamond") { "内含[0] key 不匹配" }
            check(c.innerItems[1].itemKey == "minecraft:oak_planks") { "内含[1] key 不匹配" }
            println("  ✓ 通过")
        }
        else -> println("  ✗ 失败: $decodedContainer")
    }

    // ── 测试 4: 不在白名单 → 退回 ESN1 ──
    println("\n[4] 不在白名单（精妙背包）→ 退回 ESN1")
    val backpackNbt = ByteArray(512) { it.toByte() }
    val backpackResult = service.encodeContainer(
        containerKey = "sophisticatedbackpacks:copper_backpack",
        amount = 1,
        displayName = "精妙背包",
        address = "",
        nbtData = backpackNbt,
        innerItems = emptyList()  // 即使能读内含也不拆
    )
    println("  格式: ${backpackResult.format}, 拆分: ${backpackResult.split}")
    check(backpackResult.format == SerialFormat.Format.ESN1) { "不在白名单应为 ESN1" }
    check(!backpackResult.split) { "不应拆分" }
    println("  ✓ 通过")

    // ── 测试 5: 熔断器 ──
    println("\n[5] 熔断器：连续失败触发")
    config.circuitBreakerMaxFailures = 2
    config.circuitBreakerCooldownSeconds = 1  // 1 秒冷却
    config.resetAllCircuits()

    val failKey = "minecraft:shulker_box"
    // 第一次失败
    val tripped1 = config.recordFailure(failKey)
    println("  失败 1: 熔断=${tripped1}, 状态=${config.circuitStatus(failKey)}")
    check(!tripped1) { "第 1 次不应触发" }

    // 第二次失败 → 触发熔断
    val tripped2 = config.recordFailure(failKey)
    println("  失败 2: 熔断=${tripped2}, 状态=${config.circuitStatus(failKey)}")
    check(tripped2) { "第 2 次应触发熔断" }
    check(!config.allowSplit(failKey)) { "熔断后应拒绝拆分" }

    // 等待冷却
    println("  等待冷却 (1.5s)...")
    Thread.sleep(1500)
    check(config.allowSplit(failKey)) { "冷却后应允许拆分" }
    println("  ✓ 通过（冷却后恢复）")

    // ── 测试 6: 配置持久化 ──
    println("\n[6] 配置持久化")
    val configFile = File("eslink-split-test.properties")
    configFile.deleteOnExit()
    val persistentConfig = SplitConfig(configFile)
    persistentConfig.enabled = true
    persistentConfig.addWhitelist("minecraft:shulker_box")
    persistentConfig.addWhitelist("minecraft:bundle")
    persistentConfig.save()

    val loadedConfig = SplitConfig(configFile)
    loadedConfig.load()
    println("  enabled: ${loadedConfig.enabled}")
    println("  whitelist: ${loadedConfig.getWhitelist()}")
    check(loadedConfig.enabled) { "enabled 应为 true" }
    check(loadedConfig.isWhitelisted("minecraft:shulker_box")) { "应有 shulker_box" }
    check(loadedConfig.isWhitelisted("minecraft:bundle")) { "应有 bundle" }
    check(!loadedConfig.isWhitelisted("sophisticatedbackpacks:copper_backpack")) { "不应有背包" }
    println("  ✓ 通过")

    // ── 测试 7: 队列 payload 编码 ──
    println("\n[7] 队列 payload 编码/解码")
    val qPayload = service.encodeQueuePayload(esn1, SerialFormat.Format.ESN1)
    println("  队列 payload: ${qPayload.size} bytes, format=0x%02X".format(qPayload[0]))
    val qDecoded = service.decodeQueuePayload(qPayload)
    when (qDecoded) {
        is SerializationService.DecodeResult.Single -> {
            println("  解码: ${qDecoded.item.itemKey} x${qDecoded.item.amount}")
            println("  ✓ 通过")
        }
        else -> println("  ✗ 失败: $qDecoded")
    }

    // ── 测试 8: 格式检测 ──
    println("\n[8] 格式检测")
    check(SerialFormat.isEsn1(esn1)) { "应检测为 ESN1" }
    check(SerialFormat.isEsn6(result.data)) { "应检测为 ESN6" }
    check(SerialFormat.detect(ByteArray(10) { 0 }) == SerialFormat.Format.UNKNOWN) { "未知数据应为 UNKNOWN" }
    println("  ✓ 通过")

    // ── 测试 9: 损坏数据 ──
    println("\n[9] 损坏数据解码")
    val corrupted = esn1.copyOf(10)  // 截断
    val badDecode = service.decode(corrupted)
    when (badDecode) {
        is SerializationService.DecodeResult.Failure -> println("  ✓ 正确返回失败: ${badDecode.reason}")
        else -> println("  ✗ 应失败却返回: $badDecode")
    }

    // ── 测试 10: ESN6 超过大小限制退回 ESN1（策略拒绝不触发熔断） ──
    println("\n[10] ESN6 超大容器退回 ESN1（策略拒绝不触发熔断）")
    config.circuitBreakerMaxFailures = 3
    config.circuitBreakerCooldownSeconds = 60  // 足够长，不会在测试中自然冷却
    config.maxTotalSize = 256  // 设很小
    config.resetAllCircuits()

    val hugeNbt = ByteArray(512) { it.toByte() }
    val hugeResult = service.encodeContainer(
        containerKey = "minecraft:shulker_box",
        amount = 1,
        displayName = "超大箱",
        address = "",
        nbtData = hugeNbt,
        innerItems = listOf(
            SerialFormat.Esn1Item("minecraft:stone", 64, ByteArray(200) { it.toByte() })
        )
    )
    println("  格式: ${hugeResult.format}, 拆分: ${hugeResult.split}")
    check(hugeResult.format == SerialFormat.Format.ESN1) { "超大容器应退回 ESN1" }
    val hugeStatus = config.circuitStatus("minecraft:shulker_box")
    println("  熔断状态: tripped=${hugeStatus.tripped}, failures=${hugeStatus.failures}")
    check(!hugeStatus.tripped) { "策略拒绝不应触发熔断" }
    check(hugeStatus.failures == 0) { "策略拒绝不应累计失败" }
    println("  ✓ 通过")

    // ── 测试 11: 熔断冷却后需重新累计 N 次才再次触发 ──
    println("\n[11] 熔断冷却后需重新累计 N 次才再次触发")
    config.circuitBreakerMaxFailures = 2
    config.circuitBreakerCooldownSeconds = 1
    config.maxTotalSize = SplitConfig.DEFAULT_MAX_SIZE
    config.resetAllCircuits()

    // 触发一次真实熔断（直接调用 recordFailure 模拟编码失败）
    config.recordFailure("minecraft:shulker_box")
    config.recordFailure("minecraft:shulker_box")
    println("  熔断状态: ${config.circuitStatus("minecraft:shulker_box")}")
    check(!config.allowSplit("minecraft:shulker_box")) { "熔断后应拒绝拆分" }

    // 等待冷却
    println("  等待冷却 (1.5s)...")
    Thread.sleep(1500)
    check(config.allowSplit("minecraft:shulker_box")) { "冷却后应允许拆分" }

    // 冷却后第一次失败不应立即再次熔断（计数已清零）
    val afterCooldownFail1 = config.recordFailure("minecraft:shulker_box")
    println("  冷却后第 1 次失败: 熔断=${afterCooldownFail1}")
    check(!afterCooldownFail1) { "冷却后第 1 次失败不应触发熔断" }
    val afterCooldownFail2 = config.recordFailure("minecraft:shulker_box")
    println("  冷却后第 2 次失败: 熔断=${afterCooldownFail2}")
    check(afterCooldownFail2) { "冷却后第 2 次失败应触发熔断" }
    println("  ✓ 通过")

    // 恢复配置
    config.circuitBreakerMaxFailures = SplitConfig.DEFAULT_CB_MAX_FAILURES
    config.circuitBreakerCooldownSeconds = SplitConfig.DEFAULT_CB_COOLDOWN
    config.maxTotalSize = SplitConfig.DEFAULT_MAX_SIZE
    config.enabled = false
    config.resetAllCircuits()

    // 摘要
    println("\n— 序列化层测试全部通过 —")
    println(config.summary())
}

private fun argValue(args: Array<String>, name: String, default: String?): String {
    val idx = args.indexOf(name)
    if (idx >= 0 && idx + 1 < args.size) return args[idx + 1]
    return default ?: throw IllegalArgumentException("缺少参数: $name")
}

private fun optionalArgValue(args: Array<String>, name: String): String? {
    val idx = args.indexOf(name)
    if (idx >= 0 && idx + 1 < args.size) return args[idx + 1]
    return null
}