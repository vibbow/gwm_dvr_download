package net.vsean.gwm_dvr_download

import java.io.ByteArrayOutputStream
import java.util.Base64

data class HotspotConfig(
    val ssid: String,
    val password: String,
    val host: String,
    val port: Int,
)

object QrParser {

    /**
     * 支持三种输入：
     *  - 完整二维码链接 https://app-down.gwm.com.cn/...#/?haval_hotspot=<base64>
     *  - 解码后的 haval://app%2Fserver%2Fhotspot?hotspotName=...
     *  - 单独的 base64 串
     */
    fun parse(text: String): HotspotConfig {
        val raw = text.trim()
        val haval = when {
            raw.startsWith("haval://") -> raw
            else -> {
                val encoded = Regex("haval_hotspot=([^&#\\s]+)").find(raw)?.groupValues?.get(1) ?: raw
                // base64 本身可能含 '+'，这里只解 %XX，不能把 '+' 当空格
                decodeBase64(percentDecode(encoded))
            }
        }
        if (!haval.startsWith("haval://")) {
            throw IllegalArgumentException("无法识别的二维码内容")
        }

        val params = haval.substringAfter('?', "")
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to decodeValue(it.substringAfter('=', "")) }

        val ssid = params["hotspotName"]?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("缺少 hotspotName")
        val password = params["hotspotPassword"] ?: ""
        val host = params["socketAddress"] ?: throw IllegalArgumentException("缺少 socketAddress")
        val port = params["port"]?.trim()?.toIntOrNull() ?: throw IllegalArgumentException("缺少 port")
        return HotspotConfig(ssid, password, host.trim(), port)
    }

    /**
     * 原车 App 对参数值做了两次 URL 编码（例如密码里的 % 变成了 %2525）。
     * Java URLEncoder 会把空格编码成 '+'，两次编码后空格变成 %2B、'+' 变成 %252B，
     * 所以两次都要按表单规则解码（'+' 当空格）。对 encodeURIComponent 风格（空格为 %20）同样适用。
     */
    private fun decodeValue(v: String): String = formDecode(formDecode(v))

    private fun formDecode(s: String): String = percentDecode(s.replace('+', ' '))

    private fun decodeBase64(s: String): String {
        val normalized = s.filterNot { it.isWhitespace() }
            .replace('-', '+')
            .replace('_', '/')
            .trimEnd('=')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val bytes = try {
            Base64.getDecoder().decode(padded)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("haval_hotspot 不是有效的 base64")
        }
        return String(bytes, Charsets.UTF_8)
    }

    /** 只解码 %XX（按 UTF-8 字节拼接），不处理 '+'；非法的 %XX 原样保留。 */
    fun percentDecode(s: String): String {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    out.write(v)
                    i += 3
                    continue
                }
            }
            val cp = s.codePointAt(i)
            out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
            i += Character.charCount(cp)
        }
        return out.toString("UTF-8")
    }
}
