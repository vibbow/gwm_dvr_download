package net.vsean.gwm_dvr_download

import android.os.Build

/**
 * 取手机的市场名称（例如 "vivo X300s"）。
 *
 * Build.MODEL 拿到的是内部代号（vivo X300s 是 "V2548A"），车机上显示很难辨认。
 * 各厂商把市场名放在自己的 system property 里，Android 没有统一 API，
 * 只能按厂商逐个尝试，都读不到就退回 Build.MODEL。
 */
object DeviceName {

    private val MARKET_NAME_PROPS = listOf(
        "ro.vivo.market.name",          // vivo / iQOO
        "ro.product.marketname",        // 小米 / Redmi（较新机型）、部分 realme
        "ro.product.odm.marketname",    // 小米 / Redmi（部分机型）
        "ro.vendor.oplus.market.name",  // OPPO / 一加 / realme（ColorOS 13+）
        "ro.oppo.market.name",          // OPPO / 一加（旧）
        "ro.config.marketing_name",     // 华为 / 荣耀
    )

    val value: String by lazy { resolve() }

    private fun resolve(): String {
        val market = MARKET_NAME_PROPS.firstNotNullOfOrNull { prop ->
            systemProperty(prop)?.takeIf { it.isNotBlank() && !it.equals("null", true) }
        }?.trim() ?: return Build.MODEL

        // 小米等厂商的值可能不含品牌（例如 "15 Pro"），补上品牌更好认。
        val brand = Build.BRAND?.trim().orEmpty()
        return if (brand.isNotEmpty() && !market.contains(brand, ignoreCase = true)) {
            "$brand $market"
        } else {
            market
        }
    }

    /**
     * SystemProperties 是 hidden API，只能反射调用；被系统拦截时返回 null。
     */
    private fun systemProperty(key: String): String? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        get.invoke(null, key) as? String
    } catch (_: Throwable) {
        null
    }
}
