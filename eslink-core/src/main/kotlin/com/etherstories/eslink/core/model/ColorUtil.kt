package com.etherstories.eslink.core.model

/**
 * 颜色工具 — 纯字符串处理，不依赖 Minecraft 的 ChatColor。
 * 用于将 & 码和十六进制颜色转换为 Minecraft 格式代码。
 * 核心进程不直接渲染颜色，但需要保留颜色字符串的格式供下游使用。
 */
object ColorUtil {

    private val HEX_PATTERN = Regex("&#([A-Fa-f0-9]{6})")

    /**
     * 将 & 颜色码和 &#RRGGBB 十六进制码转换为 Minecraft 样式代码。
     * 输出格式：§x§R§R§G§G§B§B（十六进制）或 §<code>（标准色）。
     */
    fun colorize(s: String?): String {
        if (s == null) return ""
        var result = s
        // 替换 &#RRGGBB → §x§R§R§G§G§B§B
        result = HEX_PATTERN.replace(result) { match ->
            val hex = match.groupValues[1]
            "§x" + hex.map { "§$it" }.joinToString("")
        }
        // 替换 &0-9a-f → §0-9a-f
        result = result.replace(Regex("&([0-9a-fk-orA-FK-OR])")) { "§${it.groupValues[1]}" }
        return result
    }

    /**
     * 大厅陶瓦色 → & 码。
     * 纯映射，无外部依赖。
     */
    fun dye(color: String?): String {
        if (color == null) return "b"
        return when (color.uppercase()) {
            "WHITE" -> "f"
            "LIGHT_GRAY", "SILVER" -> "7"
            "GRAY" -> "8"
            "BLACK" -> "0"
            "BROWN", "ORANGE" -> "6"
            "RED" -> "c"
            "YELLOW" -> "e"
            "LIME" -> "a"
            "GREEN" -> "2"
            "CYAN", "LIGHT_BLUE" -> "b"
            "BLUE" -> "9"
            "PURPLE" -> "5"
            "MAGENTA", "PINK" -> "d"
            else -> "b"
        }
    }

    /**
     * 去除 Minecraft 颜色代码。
     */
    fun stripColor(s: String?): String {
        if (s == null) return ""
        return s.replace(Regex("§[0-9a-fk-orA-FK-OR]"), "")
            .replace(Regex("§x(§[0-9a-fA-F]){6}"), "")
    }
}