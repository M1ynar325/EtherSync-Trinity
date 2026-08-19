# EtherSync-Trinity（ESLink 三轮重构）

> **三轮架构**：独立核心进程 + NeoForge 模组 + Paper 插件，替代旧版共享 MySQL 架构。

EtherSync-Trinity 是 [EtherSync-Link](https://github.com/EVGA2048/EtherSync-Link) 的架构重构版本，目标是构建一个**更安全、更灵活、更易维护**的跨服互通方案。

---

## 架构概览

```
┌────────────────────────────────────────────────────────────┐
│                    Hub（云服务器，公网 IP）                   │
│  TCP 3307 · 自定义二进制协议 · FRP 可穿透 · 无需 ICP 备案    │
│  鉴权 → 路由 → 中继转发                                    │
│  SQLite（仅存元数据：服务器列表、节点配对、注册表缓存、市场挂单）│
└──────────────────────┬─────────────────────────────────────┘
                       │ TCP 长连接
          ┌────────────┼────────────┐
          ▼            ▼            ▼
   eslink-core    eslink-mod   eslink-plugin
   （独立进程）   （NeoForge）   （Paper，可选）
   零 MC 依赖     方块/GUI      向后兼容
```

### 三层项目

| 模块 | 语言 | 职责 | 更新方式 |
|------|------|------|----------|
| **eslink-core** | Kotlin | Hub 通信协议、物品序列化（ESN6）、本地 SQLite 存储 | 重启进程（0.5 秒） |
| **eslink-mod** | Kotlin | NeoForge 方块、GUI、Capability、薄桥接层 | DCEVM 热加载（秒级） |
| **eslink-plugin** | Java | Paper 适配、箱子/红石节点、指令、GUI（向后兼容） | DCEVM 热加载（秒级） |

### 关键设计决策

1. **不用共享 MySQL**：安全模型从根本上坏了，公开发布不可行
2. **不用 HTTPS**：FRP 要求 HTTP 隧道必须 ICP 备案，中国大陆大部分服主没有
3. **Hub 不存储传输数据**：只做管道，物品数据不过 Hub 的磁盘
4. **UUID 标识节点**：替代坐标，解决 Sable/Aeronautics 移动结构下坐标映射失效
5. **core 独立进程**：支持热更新，可用任何 JVM 语言
6. **插件和模组共享 core**：混合端下只有一个 Hub 连接，节点可跨类型配对
7. **序列化只保留 ESN6**：砍掉旧格式兼容代码，减少维护负担

---

## 当前状态

> 🔧 **重构进行中** — 从旧版 Paper 插件向三轮架构迁移

| 阶段 | 进度 |
|------|------|
| ✅ 仓库 fork 与重命名 | 完成 |
| ✅ 第一步：eslink-core 模型层提取 | 完成 |
| ✅ 第二步：eslink-core 存储层（SQLite） | 完成 |
| ✅ 第三步：eslink-core 协议层（TCP 二进制帧） | 完成 |
| ⬜ 第四步：序列化层拆分（ESN6 进入 mod） | 待开始 |
| ⬜ 第五步：eslink-plugin 适配 core 进程 | 待开始 |
| ⬜ 第六步：eslink-mod（NeoForge 原生） | 待开始 |

---

## 原版功能（继承自 EtherSync-Link）

当前仓库仍保留完整的旧版 Paper 插件代码，支持以下功能：

| 能力 | 说明 |
|------|------|
| **互通大厅** | GUI 查看各服状态；跨服上架 / 购买物品（可选 Vault 经济与税率） |
| **TX / RX 运输箱** | 配对发送箱与接收箱，按扫描周期经队列把物品运到对端服 |
| **跨服红石 IO** | 事件时间戳回放：电平变化写入 `link_io_events`，接收端按原间隔还原 |
| **跨服聊天** | 本服 / 全服频道、私聊、屏蔽；可附带物品展示 |
| **通知与运维** | 上架广播、建箱提醒、`/link diag` 诊断、说明书、超级管理清理脏数据等 |

---

## 构建

### 旧版插件（当前可用）

```bash
mvn -q package
```
产物：`target/ESLink-<version>.jar`

### eslink-core（重构中）

```bash
cd eslink-core
mvn -q package
```
产物：`eslink-core/target/eslink-core-<version>.jar`

---

## 许可证

[LICENSE](LICENSE)
