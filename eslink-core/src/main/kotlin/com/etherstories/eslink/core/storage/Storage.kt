package com.etherstories.eslink.core.storage

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * eslink-core 本地 SQLite 存储。
 *
 * 职责：
 *  - 节点注册表（本地箱子/红石节点）
 *  - 待发送队列（物品数据）
 *  - 服务器缓存（对端信息）
 *
 * 不存：聊天记录、市场挂单、通知（这些由 Hub 的 SQLite 管理）。
 */
class Storage(private val dbPath: String) : AutoCloseable {

    private var conn: Connection? = null

    // ──────────────────────────────────────────────
    // 初始化
    // ──────────────────────────────────────────────

    /**
     * 打开/创建数据库，初始化表结构。
     */
    fun connect(): Boolean {
        return try {
            Class.forName("org.sqlite.JDBC")
            val c = DriverManager.getConnection("jdbc:sqlite:$dbPath")
            initSchema(c)
            conn = c
            true
        } catch (e: Exception) {
            System.err.println("[Storage] 连接失败: ${e.message}")
            false
        }
    }

    fun ready(): Boolean = conn != null && !conn!!.isClosed

    /** 暴露底层 JDBC 连接，供插件层扩展自定义表。 */
    fun getConnection(): Connection? = if (ready()) conn else null

    override fun close() {
        try { conn?.close() } catch (_: Exception) {}
        conn = null
    }

    // ──────────────────────────────────────────────
    // 表结构
    // ──────────────────────────────────────────────

    private fun initSchema(c: Connection) {
        c.createStatement().use { stmt ->
            // 启用 WAL 模式（并发读性能更好）
            try { stmt.executeUpdate("PRAGMA journal_mode=WAL") } catch (_: Exception) {}
            // 启用外键
            try { stmt.executeUpdate("PRAGMA foreign_keys=ON") } catch (_: Exception) {}

            // 节点注册表
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS nodes (
                    id TEXT PRIMARY KEY,
                    type TEXT NOT NULL,
                    role TEXT NOT NULL,
                    server_code TEXT NOT NULL,
                    world TEXT,
                    x INTEGER NOT NULL DEFAULT 0,
                    y INTEGER NOT NULL DEFAULT 0,
                    z INTEGER NOT NULL DEFAULT 0,
                    owner_uuid TEXT NOT NULL,
                    owner_name TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL DEFAULT 'idle',
                    pair_code TEXT,
                    pair_node_id TEXT,
                    extra TEXT,
                    created INTEGER NOT NULL,
                    updated INTEGER NOT NULL
                )
            """.trimIndent())
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_nodes_server ON nodes(server_code)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_nodes_type ON nodes(type, role)")

            // 队列（待发送/待接收的物品）
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS queue (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    from_code TEXT NOT NULL,
                    to_code TEXT NOT NULL,
                    pair_code TEXT NOT NULL,
                    item_key TEXT NOT NULL,
                    item_name TEXT NOT NULL,
                    amount INTEGER NOT NULL DEFAULT 1,
                    status TEXT NOT NULL DEFAULT 'pending',
                    blob_b64 TEXT,
                    nested_keys TEXT,
                    batch_id TEXT,
                    parent_id INTEGER,
                    row_index INTEGER,
                    row_sha256 TEXT,
                    return_slots INTEGER NOT NULL DEFAULT 1,
                    created INTEGER NOT NULL,
                    delivered INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_queue_status ON queue(status, to_code)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_queue_batch ON queue(batch_id)")

            // 批次校验
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS batches (
                    batch_id TEXT PRIMARY KEY,
                    from_code TEXT NOT NULL,
                    to_code TEXT NOT NULL,
                    item_count INTEGER NOT NULL,
                    payload_sha256 TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'open',
                    created INTEGER NOT NULL
                )
            """.trimIndent())
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_batches_status ON batches(status, created)")

            // 服务器缓存（对端信息）
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS server_cache (
                    code TEXT PRIMARY KEY,
                    display_name TEXT NOT NULL DEFAULT '',
                    blurb TEXT NOT NULL DEFAULT '',
                    color TEXT NOT NULL DEFAULT 'LIGHT_BLUE',
                    icon TEXT NOT NULL DEFAULT 'TERRACOTTA',
                    last_heartbeat INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())

            // 物品注册表缓存
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS registry_cache (
                    server_code TEXT PRIMARY KEY,
                    digest TEXT NOT NULL,
                    item_count INTEGER NOT NULL DEFAULT 0,
                    payload BLOB,
                    updated INTEGER NOT NULL
                )
            """.trimIndent())

            // 配置键值存储
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS config (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """.trimIndent())
        }
    }

    // ──────────────────────────────────────────────
    // 节点注册表
    // ──────────────────────────────────────────────

    data class NodeRow(
        val id: String,
        val type: String,
        val role: String,
        val serverCode: String,
        val world: String?,
        val x: Int, val y: Int, val z: Int,
        val ownerUuid: String,
        val ownerName: String,
        val status: String,
        val pairCode: String?,
        val pairNodeId: String?,
        val extra: String?,
        val created: Long,
        val updated: Long
    )

    fun insertNode(row: NodeRow): Boolean {
        val c = conn ?: return false
        return try {
            c.prepareStatement("""
                INSERT OR REPLACE INTO nodes
                (id, type, role, server_code, world, x, y, z, owner_uuid, owner_name, status, pair_code, pair_node_id, extra, created, updated)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { stmt ->
                stmt.setString(1, row.id)
                stmt.setString(2, row.type)
                stmt.setString(3, row.role)
                stmt.setString(4, row.serverCode)
                stmt.setString(5, row.world)
                stmt.setInt(6, row.x)
                stmt.setInt(7, row.y)
                stmt.setInt(8, row.z)
                stmt.setString(9, row.ownerUuid)
                stmt.setString(10, row.ownerName)
                stmt.setString(11, row.status)
                stmt.setString(12, row.pairCode)
                stmt.setString(13, row.pairNodeId)
                stmt.setString(14, row.extra)
                stmt.setLong(15, row.created)
                stmt.setLong(16, row.updated)
                stmt.executeUpdate()
            }
            true
        } catch (e: Exception) {
            System.err.println("[Storage] insertNode 失败: ${e.message}")
            false
        }
    }

    fun getNode(id: String): NodeRow? {
        val c = conn ?: return null
        return try {
            c.prepareStatement("SELECT * FROM nodes WHERE id = ?").use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) nodeFromRs(rs) else null
                }
            }
        } catch (e: Exception) {
            System.err.println("[Storage] getNode 失败: ${e.message}")
            null
        }
    }

    fun getNodeByLocation(serverCode: String, world: String, x: Int, y: Int, z: Int): NodeRow? {
        val c = conn ?: return null
        return try {
            c.prepareStatement("SELECT * FROM nodes WHERE server_code = ? AND world = ? AND x = ? AND y = ? AND z = ?").use { stmt ->
                stmt.setString(1, serverCode)
                stmt.setString(2, world)
                stmt.setInt(3, x)
                stmt.setInt(4, y)
                stmt.setInt(5, z)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) nodeFromRs(rs) else null
                }
            }
        } catch (e: Exception) {
            System.err.println("[Storage] getNodeByLocation 失败: ${e.message}")
            null
        }
    }

    fun listNodes(serverCode: String? = null, type: String? = null): List<NodeRow> {
        val c = conn ?: return emptyList()
        val sql = buildString {
            append("SELECT * FROM nodes WHERE 1=1")
            if (serverCode != null) append(" AND server_code = ?")
            if (type != null) append(" AND type = ?")
            append(" ORDER BY created DESC")
        }
        return try {
            c.prepareStatement(sql).use { stmt ->
                var idx = 1
                if (serverCode != null) { stmt.setString(idx, serverCode); idx++ }
                if (type != null) { stmt.setString(idx, type); idx++ }
                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<NodeRow>()
                    while (rs.next()) list.add(nodeFromRs(rs))
                    list
                }
            }
        } catch (e: Exception) {
            System.err.println("[Storage] listNodes 失败: ${e.message}")
            emptyList()
        }
    }

    fun deleteNode(id: String): Boolean {
        val c = conn ?: return false
        return try {
            c.prepareStatement("DELETE FROM nodes WHERE id = ?").use { stmt ->
                stmt.setString(1, id)
                stmt.executeUpdate() > 0
            }
        } catch (e: Exception) {
            System.err.println("[Storage] deleteNode 失败: ${e.message}")
            false
        }
    }

    fun updateNodeStatus(id: String, status: String, pairCode: String? = null, pairNodeId: String? = null): Boolean {
        val c = conn ?: return false
        val sql = buildString {
            append("UPDATE nodes SET status = ?, updated = ?")
            if (pairCode != null) append(", pair_code = ?")
            if (pairNodeId != null) append(", pair_node_id = ?")
            append(" WHERE id = ?")
        }
        return try {
            c.prepareStatement(sql).use { stmt ->
                var idx = 1
                stmt.setString(idx, status); idx++
                stmt.setLong(idx, System.currentTimeMillis()); idx++
                if (pairCode != null) { stmt.setString(idx, pairCode); idx++ }
                if (pairNodeId != null) { stmt.setString(idx, pairNodeId); idx++ }
                stmt.setString(idx, id)
                stmt.executeUpdate() > 0
            }
        } catch (e: Exception) {
            System.err.println("[Storage] updateNodeStatus 失败: ${e.message}")
            false
        }
    }

    fun updateNodeLocation(id: String, world: String, x: Int, y: Int, z: Int): Boolean {
        val c = conn ?: return false
        return try {
            c.prepareStatement("UPDATE nodes SET world = ?, x = ?, y = ?, z = ?, updated = ? WHERE id = ?").use { stmt ->
                stmt.setString(1, world)
                stmt.setInt(2, x)
                stmt.setInt(3, y)
                stmt.setInt(4, z)
                stmt.setLong(5, System.currentTimeMillis())
                stmt.setString(6, id)
                stmt.executeUpdate() > 0
            }
        } catch (e: Exception) {
            System.err.println("[Storage] updateNodeLocation 失败: ${e.message}")
            false
        }
    }

    private fun nodeFromRs(rs: ResultSet): NodeRow = NodeRow(
        id = rs.getString("id"),
        type = rs.getString("type"),
        role = rs.getString("role"),
        serverCode = rs.getString("server_code"),
        world = rs.getString("world"),
        x = rs.getInt("x"),
        y = rs.getInt("y"),
        z = rs.getInt("z"),
        ownerUuid = rs.getString("owner_uuid"),
        ownerName = rs.getString("owner_name") ?: "",
        status = rs.getString("status") ?: "idle",
        pairCode = rs.getString("pair_code"),
        pairNodeId = rs.getString("pair_node_id"),
        extra = rs.getString("extra"),
        created = rs.getLong("created"),
        updated = rs.getLong("updated")
    )

    // ──────────────────────────────────────────────
    // 队列
    // ──────────────────────────────────────────────

    data class QueueRow(
        val id: Long,
        val fromCode: String,
        val toCode: String,
        val pairCode: String,
        val itemKey: String,
        val itemName: String,
        val amount: Int,
        val status: String,
        val blobB64: String?,
        val nestedKeys: String?,
        val batchId: String?,
        val parentId: Long?,
        val rowIndex: Int?,
        val rowSha256: String?,
        val returnSlots: Int,
        val created: Long,
        val delivered: Long
    )

    fun insertQueue(row: QueueRow): Long? {
        val c = conn ?: return null
        return try {
            c.prepareStatement("""
                INSERT INTO queue
                (from_code, to_code, pair_code, item_key, item_name, amount, status, blob_b64, nested_keys, batch_id, parent_id, row_index, row_sha256, return_slots, created, delivered)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { stmt ->
                stmt.setString(1, row.fromCode)
                stmt.setString(2, row.toCode)
                stmt.setString(3, row.pairCode)
                stmt.setString(4, row.itemKey)
                stmt.setString(5, row.itemName)
                stmt.setInt(6, row.amount)
                stmt.setString(7, row.status)
                stmt.setString(8, row.blobB64)
                stmt.setString(9, row.nestedKeys)
                stmt.setString(10, row.batchId)
                if (row.parentId != null) stmt.setLong(11, row.parentId) else stmt.setNull(11, java.sql.Types.BIGINT)
                if (row.rowIndex != null) stmt.setInt(12, row.rowIndex) else stmt.setNull(12, java.sql.Types.INTEGER)
                stmt.setString(13, row.rowSha256)
                stmt.setInt(14, row.returnSlots)
                stmt.setLong(15, row.created)
                stmt.setLong(16, row.delivered)
                stmt.executeUpdate()
            }
            // 获取自增 ID
            c.prepareStatement("SELECT last_insert_rowid()").use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong(1) else null
                }
            }
        } catch (e: Exception) {
            System.err.println("[Storage] insertQueue 失败: ${e.message}")
            null
        }
    }

    fun pendingTo(toCode: String): List<QueueRow> {
        val c = conn ?: return emptyList()
        return try {
            c.prepareStatement("SELECT * FROM queue WHERE status = 'pending' AND to_code = ? ORDER BY created ASC").use { stmt ->
                stmt.setString(1, toCode)
                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<QueueRow>()
                    while (rs.next()) list.add(queueFromRs(rs))
                    list
                }
            }
        } catch (e: Exception) {
            System.err.println("[Storage] pendingTo 失败: ${e.message}")
            emptyList()
        }
    }

    fun bounceTo(toCode: String): List<QueueRow> {
        val c = conn ?: return emptyList()
        return try {
            c.prepareStatement("SELECT * FROM queue WHERE status = 'bounce' AND to_code = ? ORDER BY created ASC").use { stmt ->
                stmt.setString(1, toCode)
                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<QueueRow>()
                    while (rs.next()) list.add(queueFromRs(rs))
                    list
                }
            }
        } catch (e: Exception) {
            System.err.println("[Storage] bounceTo 失败: ${e.message}")
            emptyList()
        }
    }

    fun updateQueueStatus(id: Long, status: String): Boolean {
        val c = conn ?: return false
        return try {
            c.prepareStatement("UPDATE queue SET status = ?, delivered = ? WHERE id = ?").use { stmt ->
                stmt.setString(1, status)
                stmt.setLong(2, if (status == "delivered") System.currentTimeMillis() else 0)
                stmt.setLong(3, id)
                stmt.executeUpdate() > 0
            }
        } catch (e: Exception) {
            System.err.println("[Storage] updateQueueStatus 失败: ${e.message}")
            false
        }
    }

    fun deleteQueue(id: Long): Boolean {
        val c = conn ?: return false
        return try {
            c.prepareStatement("DELETE FROM queue WHERE id = ?").use { stmt ->
                stmt.setLong(1, id)
                stmt.executeUpdate() > 0
            }
        } catch (e: Exception) {
            System.err.println("[Storage] deleteQueue 失败: ${e.message}")
            false
        }
    }

    private fun queueFromRs(rs: ResultSet): QueueRow = QueueRow(
        id = rs.getLong("id"),
        fromCode = rs.getString("from_code"),
        toCode = rs.getString("to_code"),
        pairCode = rs.getString("pair_code"),
        itemKey = rs.getString("item_key"),
        itemName = rs.getString("item_name") ?: "",
        amount = rs.getInt("amount"),
        status = rs.getString("status") ?: "pending",
        blobB64 = rs.getString("blob_b64"),
        nestedKeys = rs.getString("nested_keys"),
        batchId = rs.getString("batch_id"),
        parentId = rs.getLong("parent_id").let { if (rs.wasNull()) null else it },
        rowIndex = rs.getInt("row_index").let { if (rs.wasNull()) null else it },
        rowSha256 = rs.getString("row_sha256"),
        returnSlots = rs.getInt("return_slots"),
        created = rs.getLong("created"),
        delivered = rs.getLong("delivered")
    )

    // ──────────────────────────────────────────────
    // 批次
    // ──────────────────────────────────────────────

    data class BatchRow(
        val batchId: String,
        val fromCode: String,
        val toCode: String,
        val itemCount: Int,
        val payloadSha256: String,
        val status: String,
        val created: Long
    )

    fun insertBatch(row: BatchRow): Boolean {
        val c = conn ?: return false
        return try {
            c.prepareStatement("""
                INSERT OR REPLACE INTO batches
                (batch_id, from_code, to_code, item_count, payload_sha256, status, created)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { stmt ->
                stmt.setString(1, row.batchId)
                stmt.setString(2, row.fromCode)
                stmt.setString(3, row.toCode)
                stmt.setInt(4, row.itemCount)
                stmt.setString(5, row.payloadSha256)
                stmt.setString(6, row.status)
                stmt.setLong(7, row.created)
                stmt.executeUpdate()
            }
            true
        } catch (e: Exception) {
            System.err.println("[Storage] insertBatch 失败: ${e.message}")
            false
        }
    }

    fun updateBatchStatus(batchId: String, status: String): Boolean {
        val c = conn ?: return false
        return try {
            c.prepareStatement("UPDATE batches SET status = ? WHERE batch_id = ?").use { stmt ->
                stmt.setString(1, status)
                stmt.setString(2, batchId)
                stmt.executeUpdate() > 0
            }
        } catch (e: Exception) {
            System.err.println("[Storage] updateBatchStatus 失败: ${e.message}")
            false
        }
    }

    // ──────────────────────────────────────────────
    // 服务器缓存
    // ──────────────────────────────────────────────

    data class ServerCacheRow(
        val code: String,
        val displayName: String,
        val blurb: String,
        val color: String,
        val icon: String,
        val lastHeartbeat: Long
    )

    fun upsertServerCache(row: ServerCacheRow): Boolean {
        val c = conn ?: return false
        return try {
            c.prepareStatement("""
                INSERT OR REPLACE INTO server_cache
                (code, display_name, blurb, color, icon, last_heartbeat)
                VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { stmt ->
                stmt.setString(1, row.code)
                stmt.setString(2, row.displayName)
                stmt.setString(3, row.blurb)
                stmt.setString(4, row.color)
                stmt.setString(5, row.icon)
                stmt.setLong(6, row.lastHeartbeat)
                stmt.executeUpdate()
            }
            true
        } catch (e: Exception) {
            System.err.println("[Storage] upsertServerCache 失败: ${e.message}")
            false
        }
    }

    fun getServerCache(code: String): ServerCacheRow? {
        val c = conn ?: return null
        return try {
            c.prepareStatement("SELECT * FROM server_cache WHERE code = ?").use { stmt ->
                stmt.setString(1, code)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) ServerCacheRow(
                        code = rs.getString("code"),
                        displayName = rs.getString("display_name") ?: "",
                        blurb = rs.getString("blurb") ?: "",
                        color = rs.getString("color") ?: "LIGHT_BLUE",
                        icon = rs.getString("icon") ?: "TERRACOTTA",
                        lastHeartbeat = rs.getLong("last_heartbeat")
                    ) else null
                }
            }
        } catch (e: Exception) {
            System.err.println("[Storage] getServerCache 失败: ${e.message}")
            null
        }
    }

    fun listServerCache(): List<ServerCacheRow> {
        val c = conn ?: return emptyList()
        return try {
            c.createStatement().use { stmt ->
                stmt.executeQuery("SELECT * FROM server_cache ORDER BY code").use { rs ->
                    val list = mutableListOf<ServerCacheRow>()
                    while (rs.next()) list.add(ServerCacheRow(
                        code = rs.getString("code"),
                        displayName = rs.getString("display_name") ?: "",
                        blurb = rs.getString("blurb") ?: "",
                        color = rs.getString("color") ?: "LIGHT_BLUE",
                        icon = rs.getString("icon") ?: "TERRACOTTA",
                        lastHeartbeat = rs.getLong("last_heartbeat")
                    ))
                    list
                }
            }
        } catch (e: Exception) {
            System.err.println("[Storage] listServerCache 失败: ${e.message}")
            emptyList()
        }
    }

    fun deleteServerCache(code: String): Boolean {
        val c = conn ?: return false
        return try {
            c.prepareStatement("DELETE FROM server_cache WHERE code = ?").use { stmt ->
                stmt.setString(1, code)
                stmt.executeUpdate() > 0
            }
        } catch (e: Exception) {
            System.err.println("[Storage] deleteServerCache 失败: ${e.message}")
            false
        }
    }

    // ──────────────────────────────────────────────
    // 注册表缓存
    // ──────────────────────────────────────────────

    data class RegistryCacheRow(
        val serverCode: String,
        val digest: String,
        val itemCount: Int,
        val payload: ByteArray?,
        val updated: Long
    )

    fun upsertRegistryCache(row: RegistryCacheRow): Boolean {
        val c = conn ?: return false
        return try {
            c.prepareStatement("""
                INSERT OR REPLACE INTO registry_cache
                (server_code, digest, item_count, payload, updated)
                VALUES (?, ?, ?, ?, ?)
            """.trimIndent()).use { stmt ->
                stmt.setString(1, row.serverCode)
                stmt.setString(2, row.digest)
                stmt.setInt(3, row.itemCount)
                if (row.payload != null) stmt.setBytes(4, row.payload) else stmt.setNull(4, java.sql.Types.BLOB)
                stmt.setLong(5, row.updated)
                stmt.executeUpdate()
            }
            true
        } catch (e: Exception) {
            System.err.println("[Storage] upsertRegistryCache 失败: ${e.message}")
            false
        }
    }

    fun getRegistryCache(serverCode: String): RegistryCacheRow? {
        val c = conn ?: return null
        return try {
            c.prepareStatement("SELECT * FROM registry_cache WHERE server_code = ?").use { stmt ->
                stmt.setString(1, serverCode)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) RegistryCacheRow(
                        serverCode = rs.getString("server_code"),
                        digest = rs.getString("digest"),
                        itemCount = rs.getInt("item_count"),
                        payload = rs.getBytes("payload"),
                        updated = rs.getLong("updated")
                    ) else null
                }
            }
        } catch (e: Exception) {
            System.err.println("[Storage] getRegistryCache 失败: ${e.message}")
            null
        }
    }

    fun listRegistryDigests(): Map<String, String> {
        val c = conn ?: return emptyMap()
        return try {
            c.createStatement().use { stmt ->
                stmt.executeQuery("SELECT server_code, digest FROM registry_cache").use { rs ->
                    val map = mutableMapOf<String, String>()
                    while (rs.next()) map[rs.getString("server_code")] = rs.getString("digest")
                    map
                }
            }
        } catch (e: Exception) {
            System.err.println("[Storage] listRegistryDigests 失败: ${e.message}")
            emptyMap()
        }
    }

    // ──────────────────────────────────────────────
    // 配置键值
    // ──────────────────────────────────────────────

    fun setConfig(key: String, value: String): Boolean {
        val c = conn ?: return false
        return try {
            c.prepareStatement("INSERT OR REPLACE INTO config (key, value) VALUES (?, ?)").use { stmt ->
                stmt.setString(1, key)
                stmt.setString(2, value)
                stmt.executeUpdate()
            }
            true
        } catch (e: Exception) {
            System.err.println("[Storage] setConfig 失败: ${e.message}")
            false
        }
    }

    fun getConfig(key: String): String? {
        val c = conn ?: return null
        return try {
            c.prepareStatement("SELECT value FROM config WHERE key = ?").use { stmt ->
                stmt.setString(1, key)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString("value") else null
                }
            }
        } catch (e: Exception) {
            System.err.println("[Storage] getConfig 失败: ${e.message}")
            null
        }
    }
}