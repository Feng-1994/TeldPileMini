package cn.mini.teldpile.api

import android.util.Base64
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 特来电请求签名器（算法与密钥全部取自官方 APP v7.16.0 反编译源码）。
 *
 *  - ATS   = TIME_PADDING + 秒级时间戳（TIME_PADDING 默认 0，可由登录响应校准）
 *  - AVER  = Base64(DES-CBC-PKCS7(ATS, "UQInaE9V", "siudqUQo"))
 *  - 业务 JSON 敏感字段 = Base64(DES-CBC-PKCS7(json, "uf1Fia9p", "f72er983"))
 */
object RequestSigner {

    const val APP_VERSION = "7.16.0"

    // TeldKeyIvUtils.SG_HEAD_DES_KEY / SG_HEAD_DES_IV
    const val SG_HEAD_DES_KEY = "UQInaE9V"
    const val SG_HEAD_DES_IV = "siudqUQo"

    // TeldKeyIvUtils.SG_DES_KEY / SG_DES_IV（业务 JSON 加密）
    const val SG_DES_KEY = "uf1Fia9p"
    const val SG_DES_IV = "f72er983"

    // TeldKeyIvUtils.UTS_AES_KEY / UTS_AES_IV（token 加解密）
    const val UTS_AES_KEY = "7fb498553e3c462988c3b9573692bd5f"
    const val UTS_AES_IV = "98d71fe589499967"

    // 定位信息默认值：不写死任何坐标/城市（本机没定位权限时，官方 APP 发的就是
    // location_city_name 空、lat/lng = 0.0，城市取默认的北京 11）。
    const val DEFAULT_LOCATION_CITY = ""
    const val DEFAULT_LOCATION_CODE = ""
    const val DEFAULT_LAT = "0.0"
    const val DEFAULT_LNG = "0.0"

    /** AES-CBC-PKCS7 加密 + Base64（对应 TeldAESUtils.encrypt） */
    fun aesCbcPkcs7B64(plain: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
        )
        return Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    fun aesCbcPkcs7Decode(b64: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
        )
        val raw = Base64.decode(b64, Base64.NO_WRAP)
        return String(cipher.doFinal(raw), Charsets.UTF_8)
    }

    /** DES-CBC-PKCS7 加密 + Base64（对应 TeldDESUtils.teldCBCPKCS7Encode） */
    fun desCbcPkcs7B64(plain: String, key: String, iv: String): String =
        desCbcPkcs7B64(plain.toByteArray(Charsets.UTF_8), key, iv)

    /**
     * 对应 TeldDESUtils.desTeldEncode：DES-ECB 加密（随机 8 字符密钥），
     * Base64（kobjects 风格：每 56 字符插入 \r\n）后把密钥 8 个字符按固定位置
     * 0,3,16,17,22,27,30,31 插入密文。服务端按相同规则拆出密钥再解密。
     */
    fun desTeldEncode(plain: String): String {
        val keyChars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val key = buildString {
            for (i in 0 until 8) append(keyChars.random())
        }
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "DES"))
        var raw = Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        // kobjects Base64：每 14 组（56 字符）插入 \r\n
        val b64 = StringBuilder()
        while (raw.length > 56) {
            b64.append(raw.substring(0, 56)).append("\r\n")
            raw = raw.substring(56)
        }
        b64.append(raw)
        var s = key[0] + b64.toString()
        s = s.substring(0, 3) + key[1] + s.substring(3)
        s = s.substring(0, 16) + key[2] + s.substring(16)
        s = s.substring(0, 17) + key[3] + s.substring(17)
        s = s.substring(0, 22) + key[4] + s.substring(22)
        s = s.substring(0, 27) + key[5] + s.substring(27)
        s = s.substring(0, 30) + key[6] + s.substring(30)
        s = s.substring(0, 31) + key[7] + s.substring(31)
        return s
    }

    fun desCbcPkcs7B64(data: ByteArray, key: String, iv: String): String {
        val cipher = Cipher.getInstance("DES/CBC/PKCS7Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "DES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
        )
        return Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
    }

    /** 计算 ATS（字符串） */
    fun ats(nowSeconds: Long, timePaddingSeconds: Long = 0L): String =
        (timePaddingSeconds + nowSeconds).toString()

    /** 官方 HeaderParamUtils.getUserAgent：系统 http.agent + 控制字符转义（非自定义 UA） */
    fun androidUserAgent(): String {
        val prop = System.getProperty("http.agent") ?: return "Dalvik/2.1.0 (Linux; U; Android)"
        val sb = StringBuilder()
        for (c in prop) {
            if (c.code <= 31 || c.code >= 127) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.toString()
    }

    /** 计算 AVER 签名 */
    fun aver(ats: String): String = desCbcPkcs7B64(ats, SG_HEAD_DES_KEY, SG_HEAD_DES_IV)

    /**
     * 构建网关请求所需头（对应官方 JsonInterceptor）。
     * @param token 登录 token（X-Token，可为空）
     * @param deviceInfo Device 头（URL 编码键值串）
     */
    fun buildHeaders(
        token: String?,
        deviceInfo: String,
        requestId: String,
        rpcId: String,
        userAgent: String,
        cityName: String = "北京",
        locationCity: String = "",
        timePaddingSeconds: Long = 0L
    ): Map<String, String> {
        val nowSeconds = System.currentTimeMillis() / 1000
        val ats = ats(nowSeconds, timePaddingSeconds)
        return mapOf(
            // ★ 关键：TELDAppID 必须为空串。官方 VersionCheckUtils.TELDAPPID 默认就是 ""，
            //   该 GUID 只用于 H5 链接的 TELDAPPID 查询参数，绝不能出现在接口头部/表单里，
            //   否则数据中心会抛「BOSS-2003 系统异常，请联系客服」。
            "TELDAppID" to "",
            "Device" to deviceInfo,
            "AppOS" to "Android",
            "AppVersion" to APP_VERSION,
            "ASDI" to deviceInfo.substringAfter("device_id=").substringBefore("&", ""),
            "ACOI" to urlEncode(cityName),
            "ACOL" to urlEncode(locationCity),
            "ARS" to "app",
            "X-Token" to (token ?: ""),
            "ATS" to ats,
            "AVER" to aver(ats),
            "DeviceTime" to nowSeconds.toString(),
            "TokenRelative" to "NetFrameworkUpgrade",
            "User-Agent" to userAgent,
            "Teld-RequestID" to requestId,
            "Teld-RpcID" to rpcId,
        )
    }

    /** 官方 Device 头格式：k=v&k=v...（字段顺序严格对齐官方 JsonInterceptor） */
    fun buildDeviceInfo(
        deviceId: String,
        network: String,
        osVersion: String,
        deviceNickname: String,
        deviceBrand: String,
        deviceModel: String,
        cityCode: String = "11",
        cityName: String = "北京",
        locationCityName: String = DEFAULT_LOCATION_CITY,
        locationCityCode: String = DEFAULT_LOCATION_CODE,
        lat: String = DEFAULT_LAT,
        lng: String = DEFAULT_LNG
    ): String {
        val sb = StringBuilder()
        sb.append("network=").append(network).append("&")
        sb.append("app_version=").append(APP_VERSION).append("&")
        sb.append("client=android&")
        sb.append("os_version=").append(osVersion).append("&")
        sb.append("device_nickname=").append(urlEncode(deviceNickname)).append("&")
        // 官方：device_name = BRAND + URLEncode(MODEL)（品牌不编码，型号编码）
        sb.append("device_name=").append(deviceBrand).append(urlEncode(deviceModel)).append("&")
        sb.append("device_id=").append(deviceId).append("&")
        sb.append("city_code=").append(cityCode).append("&")
        sb.append("city_name=").append(urlEncode(cityName)).append("&")
        sb.append("location_city_name=").append(urlEncode(locationCityName)).append("&")
        // 官方恒为可解析的数字（无定位时 AMapLocation 给 0.0，绝不是空串）
        sb.append("lat=").append(lat).append("&")
        sb.append("lng=").append(lng).append("&")
        sb.append("location_city_code=").append(locationCityCode)
        return sb.toString()
    }

    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** 构造 api/invoke URL（与官方一致：仅 SID 参数） */
    fun buildUrl(host: String, sid: String, extraParams: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder("https://").append(host).append("/api/invoke?SID=").append(sid)
        extraParams.forEach { (k, v) ->
            sb.append("&").append(urlEncode(k)).append("=").append(urlEncode(v))
        }
        return sb.toString()
    }
}
