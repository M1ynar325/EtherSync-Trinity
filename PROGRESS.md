# ESLink-Trinity — 开发进度记录

> 三轮架构重构：独立核心(eslink-core) + NeoForge模组(eslink-mod) + Paper插件(eslink-plugin)

---

## 仓库信息

| 项目 | 值 |
|------|-----|
| 上游仓库 | https://github.com/EVGA2048/EtherSync-Link |
| 本仓库 | https://github.com/M1ynar325/EtherSync-Trinity |
| 本地路径 | `D:\ESLink_neoforged\EtherSync-Trinity` |
| 描述 | ESLink 三轮架构重构：独立核心(eslink-core) + NeoForge模组(eslink-mod) + Paper插件(eslink-plugin)，替代旧版共享MySQL架构 |

---

## 当前阶段：Step 1-4 完成

### ✅ 已完成

| 阶段 | 文件 | 说明 |
|------|------|------|
| **Step 0: 仓库准备** | — | 从上游 fork，重命名为 EtherSync-Trinity，同步主分支 |
| **Step 0: README** | `README.md` | 重写为三轮架构描述 |
| **Step 1: 模型层** | `eslink-core/src/main/kotlin/.../model/` | 5 个纯 Kotlin 文件，零 Minecraft 依赖 |
| **Step 2: 存储层** | `eslink-core/src/main/kotlin/.../storage/Storage.kt` | SQLite 存储，6 张表 |
| **Step 2: 入口** | `eslink-core/src/main/kotlin/.../Main.kt` | 测试入口 + 功能验证 |
| **Step 3: 协议层** | `eslink-core/src/main/kotlin/.../protocol/Frame.kt` | 自定义二进制帧格式（magic 0x4553, type, payload, CRC32, HMAC-SHA256） |
| **Step 3: 协议客户端** | `eslink-core/src/main/kotlin/.../protocol/ProtocolClient.kt` | TCP 客户端（连接管理、心跳、重连、鉴权） |
| **Step 3.5: Hub 服务端** | `eslink-core/src/main/kotlin/.../protocol/HubServer.kt` | 鉴权 + 路由 + 中继转发（TCP 3307） |
| **Step 4: 序列化层** | `eslink-core/src/main/kotlin/.../serialization/` | ESN1（通用整包）+ ESN6（可选白名单拆包），配置文件 + 熔断器 |
| **Step 5: 插件适配（基础）** | `eslink-plugin/` | 新建模块，CoreBridge 桥接层，Store 从 MySQL 迁移到 SQLite，ESLinkPlugin 集成 Hub 连接 |
| **Step 5: 插件适配（深入）** | `eslink-plugin/src/main/java/` | ItemCodec 增加 ESN1/ESN6 编解码，ChestNet/ChatBridge/AlertNet 增加 Hub 直传路径，CoreBridge 消息路由 |

### ⬜ 待完成

| 阶段 | 路径 | 说明 |
|------|------|------|
| **Step 5: IoNet 适配** | `eslink-plugin/` | IoNet 红石事件改为通过 Hub 通信 |
| **Step 6: 模组开发** | — | eslink-mod NeoForge 模组（方块/GUI） |

---

## 开发环境（已装好）

| 工具 | 版本 | 路径 | 备注 |
|------|------|------|------|
| JDK | 21.0.12 (Zulu) | `C:\Program Files\Zulu\zulu-21` | Kotlin 2.0 不支持 JDK 25，构建必须用 21 |
| Maven | 3.9.16 | `C:\ProgramData\chocolatey\lib\maven\apache-maven-3.9.16` | 已建 `~/bin/mvn` 包装，默认 JDK 21 |
| Kotlin 编译器 | 2.0.0 | `C:\ProgramData\kotlin\kotlinc` | 已建 `~/bin/kotlinc` 包装，默认 JDK 21 |

构建/测试命令：

```bash
cd eslink-core
mvn -DskipTests package
java -jar target/eslink-core-0.1.0.jar
```

---

## 本次会话进度：Step 4 序列化层实现

### 2025-04 会话内容

1. **深入分析旧版序列化体系**
   - 旧版有 7 种 ESN 子格式（ESN0-ESN6）+ ItemEnvelope（EST1/ESR1）+ ExtraKeys
   - ESN0（key-only）：丢数据静默成功，不可靠
   - ESN1（NBT tag）：核心格式，gzip 压缩 CompoundTag，跨端最稳定
   - ESN2（STREAM_CODEC）：混合端数字注册 ID 不稳定，可丢弃
   - ESN3/ESN4/ESN5：已被 ESN6 取代
   - ESN6（容器拆包）：仅对潜影盒/Create纸箱有意义

2. **架构决策修订**
   - ~~序列化只保留 ESN6~~ → **ESN1（必选）+ ESN6（可选）双格式**
   - ESN1 是通用兜底格式：单品、容器、AE磁盘、剪贴板、蓝图全部适用
   - ESN6 仅解决跨模组环境容器部分投递，默认关闭
   - 精妙背包、AE磁盘等不在白名单 → 一律走 ESN1 整包 NBT

3. **实现 eslink-core 序列化层（Step 4）**
   - `serialization/SerialFormat.kt`：ESN1/ESN6 二进制格式定义，magic bytes，编解码，gzip 工具
   - `serialization/SplitConfig.kt`：配置文件驱动（eslink-split.properties），白名单通配符，熔断器（连续失败 N 次自动禁用，冷却后恢复）
   - `serialization/SerializationService.kt`：编排层，自动格式选择，ESN6 失败回退 ESN1，队列 payload 封装
   - `Main.kt`：10 项序列化功能验证（往返、空NBT、ESN6拆包、白名单拒绝、熔断触发/恢复、配置持久化、队列payload、格式检测、损坏数据、超大退回）

### 序列化层架构

```
物品序列化决策：
  1. config.enabled == false → ESN1（整包 NBT）
  2. 不在白名单          → ESN1（整包 NBT）
  3. 熔断器已触发        → ESN1（整包 NBT）
  4. 尝试 ESN6，失败     → ESN1（整包 NBT）+ 记录熔断
```

### ESN1 格式（通用）

```
[Magic: 4B "ESN1"] [Version: 1B] [ItemKey: UTF-8] [Amount: 4B] [NbtLen: 4B] [NbtData: NbtLen]
```

### ESN6 格式（容器拆包，可选）

```
[Magic: 4B "ESN6"] [Version: 1B]
[ContainerKey: UTF-8] [Amount: 4B] [DisplayName: UTF-8] [Address: UTF-8]
[InnerCount: 4B] [InnerItem0: ESN1 blob] [InnerItem1: ESN1 blob] ...
```

### 配置文件（eslink-split.properties）

```properties
split.enabled = false
split.whitelist = minecraft:shulker_box,minecraft:bundle,create:*
split.max_inner_items = 81
split.max_total_size_bytes = 524288
split.circuit_breaker.cooldown_seconds = 300
split.circuit_breaker.max_failures = 3
```

示例文件：`eslink-core/eslink-split.properties.example`（复制为 `eslink-split.properties` 使用）

### 游戏内控制（GUI 预留接口）

> core 已提供 `SplitConfig` API（开关/白名单/熔断状态/重置），
> 实际 GUI 按钮绑定在 Step 5（插件）或 Step 6（模组）的调试互通箱界面中实现。

```
/link container split on|off         # 开关
/link container split add <id>       # 加白名单
/link container split remove <id>    # 移除
/link container split list           # 查看
/link container split status         # 熔断器状态
```

---

### Step 4 代码复查（2025-04 第二轮）

复查发现并修复以下问题：

| 级别 | 问题 | 修复 |
|------|------|------|
| 🔴 | `ESN6.estimatedSize()` 把 InnerCount 按 2B 算、漏算内层 ESN1 magic（每项少 4B） | 改用 `utfSize()` 精确计算，补充 `[InnerCount: 4B]` 文档 |
| 🔴 | 配置 `maxInnerItems`/`maxTotalSize` 可超过协议硬上限，导致 `encodeEsn6` 静默返回 null 误触发熔断 | `encodeEsn6` 增加参数并钳制到协议上限；服务层传 `minOf(config, 协议上限)` |
| 🔴 | 超大容器（策略拒绝）被当成编码失败计入熔断，连续几个大箱子就熔断 | 新增 `Esn6PolicyException`，策略拒绝直接退回 ESN1，不触发熔断 |
| 🟡 | 熔断冷却结束后未清零失败计数，冷却后第一次失败立即再次熔断 | `allowSplit()` 冷却结束后重置 `failures=0`，需重新累计 N 次 |
| 🟡 | `whitelist` 是普通 `mutableSetOf`，网络线程/GUI 并发读写可能异常 | 改为 `ConcurrentHashMap.newKeySet()` |
| 🟡 | ESN1/ESN6 解码未校验 amount 范围，损坏数据可传入负数 | 解码时校验 `amount in 1..127` |
| 🟡 | `decodeEsn6` 未限制总大小，可能被超大数据撑爆 | 增加 `data.size > MAX_SPLIT_SIZE` 拒绝 |
| 🟡 | `gunzip` 未关闭流、无解压大小上限 | 改用 `finally` 关闭，默认 10 MB 上限 |
| 🟡 | ESN6 解码失败时对非白名单 key 也记录熔断，可能被恶意数据撑大 | 仅对本地白名单中的容器类型记录熔断 |
| 🟡 | ESN6 内含单个物品无法编码（过大/键过长）时被当编码失败 | 捕获并转为 `Esn6PolicyException` 策略性退回 |

测试新增：
- 测试 10 验证：超大容器策略拒绝**不触发熔断**
- 测试 11 验证：熔断冷却后需重新累计 N 次才再次触发

### Step 5 联调修复（2025-04 第三轮）

1. **SQLite 连接生命周期修复**
   - Store.java 69 处 `try (Connection c = conn; ...)` 会在方法结束关闭唯一连接 → 全部改为局部引用 + 只关 Statement
   - 另发现 6 处 `try (Connection c = conn)` 单独关闭连接 → 改为 `Connection c = conn; try { ... } finally { }`
   - 症状：`database connection closed` 刷屏

2. **SQLite 语法迁移收尾**
   - 内联 `CREATE INDEX` 从 CREATE TABLE 中移出（SQLite 不支持）
   - `heartbeat/servers/writeIoLevel` 的 `FLOOR(UNIX_TIMESTAMP(NOW(3))*1000)` → `strftime('%s','now')*1000`
   - 4 处 `ON DUPLICATE KEY UPDATE` → SQLite `ON CONFLICT ... DO UPDATE`

3. **Hub 消息路由修复**
   - HubServer 只处理 SERVER_HELLO，把 SERVER_UPDATE 当 UNKNOWN_COMMAND 丢弃 → 聊天/通知广播全断
   - 修复：SERVER_UPDATE 也走 broadcastToOthers

4. **ChestNet Hub 直传激活**
   - `sendBatchViaHub()` 定义了但从未调用 → 在 enqueueBatch 后补上调用

5. **ChatBridge 修复**
   - `sendChatViaHub()` 从未被调用 → 补上（公聊/私聊）
   - Hub 接收端读 2 个布尔但发送端只写 1 个 → 流错位 → 改为 `hasItem` 单布尔

6. **节点发现（配对互通）**
   - 旧版靠共享 MySQL 看对端箱子 → SQLite 本地化后看不到
   - 新增 Hub 节点协议：MSG_NODE_REGISTER / UNREGISTER / SYNC / SYNC_RESP
   - ESLinkPlugin 维护 remoteChests/remoteIoNodes 缓存，连接后自动同步
   - ChestNet 创建/删除时广播节点变化
   - LinkGui 配对界面合并本地 + 远程节点

7. **编码修复**
   - Arc/Youer 启动参数加 `-Dfile.encoding=UTF-8`
   - bat 加 `chcp 65001` 解决控制台中文乱码

### 已知遗留
- 配对成功后，对端节点状态更新（pairCode 同步）尚未通过 Hub 广播
- IoNet 配对同样依赖节点同步（已有协议支持，未接 GUI 验证）

### Step 5 深入适配（2025-04 第二轮）

1. **ItemCodec ESN1/ESN6 集成**
   - `encodeEsn1(ItemStack)` → ESN1 字节（NBT 快照 + gzip + ESN1 帧）
   - `decodeEsn1(byte[])` → ItemStack
   - `encodeContainer(ItemStack, List<ItemStack>, SerializationService)` → ESN1/ESN6
   - `decodeEsn(byte[], SerializationService)` → DecodedItem（自动检测格式）

2. **CoreBridge 消息路由**
   - 定义业务消息类型常量（MSG_CHEST_ITEM, MSG_CHAT, MSG_ALERT, MSG_IO_EVENT 等）
   - `MessageHandler` 接口 + `onMessage()` 注册
   - Hub 收包自动分发到注册的处理器
   - 便利发送方法：`sendChestItem/Ack/Bounce`, `sendChat`, `sendAlert`, `sendIoEvent`

3. **ChestNet Hub 直传**
   - `drainTx` 编码物品后同时通过 Hub 发送（`sendBatchViaHub`）
   - 注册 MSG_CHEST_ITEM 处理器，接收方直接投递到 RX 箱子
   - 支持 ACK 确认和 BOUNCE 退回
   - 本地 SQLite 队列仍保留作为持久化备份

4. **ChatBridge Hub 通信**
   - `send()` 和 `whisper()` 同时通过 Hub 广播
   - 注册 MSG_CHAT 处理器，接收方直接 `broadcast()`
   - 支持物品展示（itemKey/itemName/itemBlob）

5. **AlertNet Hub 通信**
   - `listingLocal()` 和 `nodeFault()` 同时通过 Hub 广播
   - 注册 MSG_ALERT 处理器，接收方直接 `show()`

### Step 5 基础适配（2025-04）

1. **创建 eslink-plugin 模块**
   - 新建 `eslink-plugin/pom.xml`，依赖 `eslink-core` + Paper API + Vault
   - 移除 MySQL/HikariCP 依赖，改用 eslink-core 内置 SQLite
   - Maven shade 打包，产出 `ESLink-0.2.0.jar`（15.9 MB）

2. **CoreBridge 桥接层**
   - `core/CoreBridge.java`：管理 eslink-core 生命周期
   - 暴露 `storage()`、`serialization()`、`splitConfig()`、`hubClient()`
   - `connectHub()` 连接到 Hub 中继服务器
   - `sendToHub()` 通过 Hub 发送消息到指定目标服务器

3. **Store.java 改造**
   - MySQL/HikariCP → SQLite（直接 JDBC 连接）
   - 同一数据库文件 `eslink.db`，与 eslink-core 共享
   - 公共 API 保持不变，40 个调用方无需修改

4. **ESLinkPlugin.java 集成**
   - 新增 `CoreBridge core` 字段
   - `onEnable` 中初始化 CoreBridge
   - 从 config.yml 读取 `hub.host`/`hub.port`/`hub.secret-key` 自动连接 Hub
   - `reloadLink` 支持重连 Hub
   - `onDisable` 关闭 CoreBridge

5. **eslink-core Storage 增强**
   - 新增 `getConnection()` 方法暴露底层 JDBC 连接
   - 供插件层在同一 SQLite 文件中创建扩展表

### 构建验证（2025-04 第三轮）

已部署 Maven 3.9.16 + Kotlin 2.0.0（通过 kotlin-maven-plugin），使用 JDK 21（Kotlin 2.0 不支持 JDK 25）。

发现并修复 **3 个此前未编译过的存量问题**：

| 级别 | 文件 | 问题 | 修复 |
|------|------|------|------|
| 🔴 | `storage/Storage.kt` | `connect()` 用 `c.use { initSchema(it) }` 把连接 `use` 关闭后才赋值给 `conn`，导致所有数据库操作报 "database connection closed" | 改为 `initSchema(c)` 后直接保存连接 |
| 🔴 | `protocol/Frame.kt` | `ERROR(0xFF)` 超出 Byte 范围，Kotlin 编译失败 | 改为 `ERROR(0xFF.toByte())` |
| 🔴 | `protocol/Frame.kt` | `Byte xor Byte` 在 Kotlin 2.0 无 `xor` 引用 | 改为 `((a.toInt() xor b.toInt()) and 0xFF).toByte()` |
| 🟡 | `protocol/ProtocolClient.kt` | 缺少 `import java.net.SocketException` | 补 import |
| 🟡 | `Main.kt` | 无 `--hub` 参数时默认测试模式会因 `argValue(..., null)` 抛异常 | 新增 `optionalArgValue()`，`--hub` 可省略 |

执行结果：
```
mvn -DskipTests package  →  BUILD SUCCESS
java -jar target/eslink-core-0.1.0.jar → 存储层测试通过 + 序列化层 11 项测试全部通过
```

环境部署：
- **Maven 3.9.16**：`C:\ProgramData\chocolatey\lib\maven\apache-maven-3.9.16`，已建 `~/bin/mvn` 包装（默认 JDK 21）
- **Kotlin 编译器 2.0.0**：`C:\ProgramData\kotlin\kotlinc`，已建 `~/bin/kotlinc` 包装（默认 JDK 21）
- 注意：Kotlin 2.0 不支持 JDK 25，构建请用 JDK 21

---

## 项目结构

```
D:\ESLink_neoforged\EtherSync-Trinity\
├── PROGRESS.md                         # 本文件
├── README.md                           # 项目简介
├── 架构方案.txt                          # 架构设计文档
├── pom.xml                             # 旧版插件 Maven 构建（保留兼容）
├── LICENSE
├── src/main/java/.../                  # 旧版 Paper 插件（40+ 文件，保留兼容）
│
├── eslink-core/                        # 独立核心进程
│   ├── pom.xml
│   └── src/main/kotlin/...
│
└── eslink-plugin/                      # Paper 插件（新版，调用 eslink-core）
    ├── pom.xml
    └── src/main/java/...
    ├── pom.xml                         # Kotlin + SQLite 构建
    └── src/main/kotlin/
        └── com/etherstories/eslink/core/
            ├── Main.kt                 # 入口 + 功能测试
            └── model/
            │   ├── Models.kt           # 数据模型（ServerRow, ChestRow, IoRow 等）
            │   ├── Units.kt            # 序列号生成器（6 位字母+数字）
            │   ├── ColorUtil.kt        # 颜色工具（纯字符串，无 Minecraft 依赖）
            │   ├── Reflect.kt          # 反射缓存工具
            │   └── LinkLog.kt          # 环形缓冲区日志（无 Bukkit 依赖）
            ├── protocol/               # Hub 通信协议
            │   ├── Frame.kt           # 二进制帧格式（magic 0x4553, CRC32, HMAC-SHA256）
            │   ├── ProtocolClient.kt   # TCP 客户端（连接管理、心跳、重连、鉴权）
            │   └── HubServer.kt       # Hub 服务端（鉴权、路由、中继转发）
            ├── serialization/          # 物品序列化
            │   ├── SerialFormat.kt    # ESN1/ESN6 格式定义 + 编解码
            │   ├── SplitConfig.kt     # 拆包配置 + 白名单 + 熔断器
            │   └── SerializationService.kt  # 编排层（格式选择、回退、队列封装）
            └── storage/
                └── Storage.kt          # SQLite 存储（6 张表）
```

---

## 已确认的架构决策

1. **不用共享 MySQL**：安全模型从根本上坏了，公开发布不可行
2. **不用 HTTPS**：FRP 要求 HTTP 隧道必须 ICP 备案，中国大陆大部分服主没有
3. **Hub 不存储传输数据**：只做管道，物品数据不过 Hub 的磁盘
4. **UUID 标识节点**：替代坐标，解决 Sable/Aeronautics 移动结构下坐标映射失效
5. **core 独立进程**：支持热更新，可用任何 JVM 语言（Kotlin 优先）
6. **插件和模组共享 core**：混合端下只有一个 Hub 连接，节点可跨类型配对
7. **序列化：ESN1（必选）+ ESN6（可选）**：ESN1 通用整包 NBT 兜底一切；ESN6 仅白名单容器拆包，默认关闭，配置文件 + 游戏内 GUI 可切换，带自动熔断回退
8. **SQLite 替代 MySQL**：零配置、免备案、安全模型从共享数据库改为端到端加密
9. **通信协议**：TCP 自定义二进制帧，magic=0x4553，type+payload+CRC32+HMAC
10. **鉴权**：HMAC 令牌 + SHA-256 校验

---

## 技术选型

| 组件 | 语言 | 构建 | 依赖 |
|------|------|------|------|
| eslink-core | Kotlin | Maven + kotlin-maven-plugin | sqlite-jdbc, kotlin-stdlib |
| eslink-mod (待建) | Kotlin | — | NeoForge API |
| eslink-plugin (旧版) | Java | Maven | PaperAPI, HikariCP, MySQL, Vault |

---

## 下一轮对话建议开场

> "继续 ESLink-Trinity 项目。当前已完成 Step 1-5（含深入适配），ItemCodec 已集成 ESN1/ESN6，ChestNet/ChatBridge/AlertNet 已增加 Hub 直传路径，CoreBridge 消息路由已就绪。详见 PROGRESS.md。继续 Step 5 剩余：IoNet 红石事件改为 Hub 通信，或开始 Step 6 模组开发。"
