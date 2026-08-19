package com.etherstories.eslink.core.model

/**
 * 节点序列号：6 位字母+数字，由数据库 id 稳定算出。
 * 纯算法，零外部依赖。
 */
object Units {

    private val L = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private val D = "23456789"

    fun code(id: Int): String {
        var n = id.coerceAtLeast(1)
        val c = CharArray(6)
        for (i in 5 downTo 0) {
            if ((i and 1) == 1) {
                c[i] = D[n % D.length]
                n /= D.length
            } else {
                c[i] = L[n % L.length]
                n /= L.length
            }
        }
        return String(c)
    }

    fun or(serial: String?, id: Int): String {
        if (serial != null && serial.trim().length == 6) return serial.trim().uppercase()
        return code(id)
    }
}