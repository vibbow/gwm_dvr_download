package net.vsean.gwm_dvr_download

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64

class QrParserTest {

    @Test
    fun realQrCode() {
        val cfg = QrParser.parse(
            "https://app-down.gwm.com.cn/CarConnect/connect.html#/?haval_hotspot=" +
                "aGF2YWw6Ly9hcHAlMkZzZXJ2ZXIlMkZob3RzcG90P2hvdHNwb3ROYW1lPUdXTV8wNjJDJmhvdHNwb3RQYXNzd29yZD1HNjRlJTI1MjVoQ3ZQM3FKJnBvcnQ9NTkzNTImc29ja2V0QWRkcmVzcz0xOTIuMTY4LjE3Ni4yMTQ%3D"
        )
        assertEquals(HotspotConfig("GWM_062C", "G64e%hCvP3qJ", "192.168.176.214", 59352), cfg)
    }

    @Test
    fun chineseSsidWithSpaces_urlEncoderStyle() {
        val ssid = "长城 哈弗 H6+ 的热点"
        val password = "p a+s%s&w=o/rd"
        val cfg = QrParser.parse(buildQr(ssid, password) { formEncode(formEncode(it)) })
        assertEquals(ssid, cfg.ssid)
        assertEquals(password, cfg.password)
        assertEquals(59352, cfg.port)
    }

    @Test
    fun chineseSsidWithSpaces_encodeUriComponentStyle() {
        val ssid = "长城 哈弗 H6+ 的热点"
        val password = "p a+s%s&w=o/rd"
        val cfg = QrParser.parse(buildQr(ssid, password) { uriEncode(uriEncode(it)) })
        assertEquals(ssid, cfg.ssid)
        assertEquals(password, cfg.password)
    }

    @Test
    fun havalUrlDirectly() {
        val ssid = "我的 车"
        val qr = "haval://app%2Fserver%2Fhotspot?hotspotName=${formEncode(formEncode(ssid))}" +
            "&hotspotPassword=abc&port=1234&socketAddress=192.168.1.2"
        assertEquals(ssid, QrParser.parse(qr).ssid)
    }

    private fun buildQr(ssid: String, password: String, encode: (String) -> String): String {
        val haval = "haval://app%2Fserver%2Fhotspot?hotspotName=${encode(ssid)}" +
            "&hotspotPassword=${encode(password)}&port=59352&socketAddress=192.168.176.214"
        val b64 = Base64.getEncoder().encodeToString(haval.toByteArray(Charsets.UTF_8))
        return "https://app-down.gwm.com.cn/CarConnect/connect.html#/?haval_hotspot=${formEncode(b64)}"
    }

    private fun formEncode(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun uriEncode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
