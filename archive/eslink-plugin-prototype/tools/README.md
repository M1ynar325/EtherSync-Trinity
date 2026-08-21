# HotswapAgent 本地部署

HotswapAgent 2.0.0 已部署到本目录，用于 JVM 类热更新（无需重启进程）。

## 文件

| 文件 | 说明 |
|------|------|
| `hotswap-agent-2.0.0.jar` | HotswapAgent 主 JAR（1.9 MB） |
| `hotswap-agent.properties` | 全局配置（自动热重载、监听 resources） |
| `run-hub-hotswap.bat` | 带 javaagent 启动 eslink-core Hub |

## 使用方式

### 1. 启动 eslink-core Hub（热更新支持）

```bash
tools\hotswap-agent\run-hub-hotswap.bat
```

### 2. 启动 Arc 服务器

已在 D:\ES2P\Arc\user_jvm_args.txt 追加：
```
-javaagent:D:\ESLink_neoforged\EtherSync-Trinity\tools\hotswap-agent\hotswap-agent-2.0.0.jar
```

下次通过 run.bat 启动即生效。

### 3. 启动 Youer 服务器

已在 D:\ES2P\Youer\run.bat 的 java 命令中加入相同的 javaagent。

## 热更新流程

1. 在 IDE 中修改 Java/Kotlin 源码
2. 执行 Maven 编译（mvn compile）
3. HotswapAgent 检测到 class 文件变化，自动热重载到运行中的 JVM
4. 无需重启服务器

## 限制

- 标准 JVM 的 HotSwap 仅支持修改方法体（JDK 21 支持有限的新增方法）
- 新增/删除字段、新增类等完整热更新需要 DCEVM
- 本机未安装 DCEVM（需要单独下载 DCEVM 补丁版 JVM），当前使用 HotswapAgent 的标准模式

## DCEVM 补充说明

完整热更新（新增字段/方法/类）需要 DCEVM 增强版 JVM：

1. 下载 DCEVM 对应 JDK 21 的安装包（TravaOpenJDK 或 DCEVM installer）
2. 安装后替换 JAVA_HOME 指向 DCEVM JVM
3. HotswapAgent 自动检测 DCEVM 并启用完整热更新
