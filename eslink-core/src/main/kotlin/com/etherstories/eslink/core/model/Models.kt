package com.etherstories.eslink.core.model

import java.util.UUID

/**
 * 跨服数据模型 — 纯记录，零 Minecraft 依赖。
 */
object Models {

    data class ServerRow(
        val code: String,
        val name: String,
        val blurb: String,
        val color: String,
        val icon: String,
        val heartbeat: Long,
        val clock: Long
    ) {
        fun online(offlineAfterMs: Long): Boolean {
            if (heartbeat <= 0) return false
            val age = clock - heartbeat
            return age >= 0 && age < offlineAfterMs
        }
    }

    data class Listing(
        val id: Long,
        val seller: UUID,
        val sellerName: String,
        val serverCode: String,
        val itemKey: String,
        val itemName: String,
        val amount: Int,
        val price: Double,
        val created: Long,
        val blob: ByteArray?,
        val nestedKeys: String?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Listing) return false
            return id == other.id &&
                    seller == other.seller &&
                    sellerName == other.sellerName &&
                    serverCode == other.serverCode &&
                    itemKey == other.itemKey &&
                    itemName == other.itemName &&
                    amount == other.amount &&
                    price == other.price &&
                    created == other.created &&
                    blob.contentEquals(other.blob) &&
                    nestedKeys == other.nestedKeys
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + seller.hashCode()
            result = 31 * result + sellerName.hashCode()
            result = 31 * result + serverCode.hashCode()
            result = 31 * result + itemKey.hashCode()
            result = 31 * result + itemName.hashCode()
            result = 31 * result + amount
            result = 31 * result + price.hashCode()
            result = 31 * result + created.hashCode()
            result = 31 * result + (blob?.contentHashCode() ?: 0)
            result = 31 * result + (nestedKeys?.hashCode() ?: 0)
            return result
        }
    }

    data class QueueRow(
        val id: Long,
        val fromCode: String,
        val toCode: String,
        val pairCode: String,
        val itemKey: String,
        val itemName: String,
        val amount: Int,
        val status: String,
        val blob: ByteArray?,
        val nestedKeys: String?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is QueueRow) return false
            return id == other.id &&
                    fromCode == other.fromCode &&
                    toCode == other.toCode &&
                    pairCode == other.pairCode &&
                    itemKey == other.itemKey &&
                    itemName == other.itemName &&
                    amount == other.amount &&
                    status == other.status &&
                    blob.contentEquals(other.blob) &&
                    nestedKeys == other.nestedKeys
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + fromCode.hashCode()
            result = 31 * result + toCode.hashCode()
            result = 31 * result + pairCode.hashCode()
            result = 31 * result + itemKey.hashCode()
            result = 31 * result + itemName.hashCode()
            result = 31 * result + amount
            result = 31 * result + status.hashCode()
            result = 31 * result + (blob?.contentHashCode() ?: 0)
            result = 31 * result + (nestedKeys?.hashCode() ?: 0)
            return result
        }
    }

    data class WatchRow(
        val kind: String,
        val nodeId: Int,
        val unit: String,
        val role: String,
        val serverCode: String,
        val pairCode: String,
        val status: String,
        val ownerName: String
    )

    data class ChestRow(
        val id: Int,
        val serial: String?,
        val pairCode: String?,
        val serverCode: String,
        val role: String,
        val world: String,
        val x: Int, val y: Int, val z: Int,
        val owner: UUID,
        val status: String,
        val ownerName: String?,
        val peerSerial: String?,
        val itemFilter: String?,
        val bounceId: Int,
        val signFace: String?
    ) {
        fun unit(): String = Units.or(serial, id)
        fun peerUnit(): String = peerSerial?.trim() ?: ""
        fun itemFilter(): String = itemFilter?.trim() ?: ""
        fun signFace(): String = signFace?.trim() ?: ""

        fun withStatus(st: String): ChestRow {
            return copy(status = st)
        }
    }

    data class IoRow(
        val id: Int,
        val serial: String?,
        val pairCode: String?,
        val serverCode: String,
        val role: String,
        val world: String,
        val x: Int, val y: Int, val z: Int,
        val owner: UUID,
        val status: String,
        val ownerName: String?,
        val level: Int,
        val updatedMs: Long,
        val peerSerial: String?,
        val peerLevel: Int,
        val peerUpdatedMs: Long,
        val peerServer: String?,
        val dbNow: Long,
        val logic: String?
    ) {
        fun unit(): String = Units.or(serial, id)
        fun peerUnit(): String = peerSerial?.trim() ?: ""
        fun logic(): String = if (logic.isNullOrBlank()) "normal" else logic

        fun withStatus(st: String): IoRow {
            return copy(status = st)
        }
    }

    data class AlertRow(
        val id: Long,
        val kind: String,
        val fromCode: String,
        val fromName: String,
        val playerName: String,
        val detail: String,
        val created: Long
    )

    data class ChatRow(
        val id: Long,
        val fromCode: String,
        val fromName: String,
        val playerUuid: UUID,
        val playerName: String,
        val message: String,
        val itemKey: String?,
        val itemName: String?,
        val itemAmount: Int,
        val itemBlob: ByteArray?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ChatRow) return false
            return id == other.id &&
                    fromCode == other.fromCode &&
                    fromName == other.fromName &&
                    playerUuid == other.playerUuid &&
                    playerName == other.playerName &&
                    message == other.message &&
                    itemKey == other.itemKey &&
                    itemName == other.itemName &&
                    itemAmount == other.itemAmount &&
                    itemBlob.contentEquals(other.itemBlob)
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + fromCode.hashCode()
            result = 31 * result + fromName.hashCode()
            result = 31 * result + playerUuid.hashCode()
            result = 31 * result + playerName.hashCode()
            result = 31 * result + message.hashCode()
            result = 31 * result + (itemKey?.hashCode() ?: 0)
            result = 31 * result + (itemName?.hashCode() ?: 0)
            result = 31 * result + itemAmount
            result = 31 * result + (itemBlob?.contentHashCode() ?: 0)
            return result
        }
    }

    data class IoEvent(
        val id: Long,
        val level: Int,
        val timeMs: Long
    )
}