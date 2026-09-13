package cn.mini.teldpile.api

import android.content.Context
import android.provider.Settings
import com.google.gson.JsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 特来电网关客户端（协议/签名/加密全部来自官方 APP v7.16.0 反编译源码）。
 *
 * 请求形式（与官方一致）：
 *  - URL: https://sg.teld.cn/api/invoke?SID=<SID>&operatorID=&version=v2
 *  - 方法: POST, body = form-urlencoded；敏感字段值 = Base64(DES-CBC-PKCS7(json, SG_DES_KEY, SG_DES_IV))
 *  - 请求头: 见 RequestSigner.buildHeaders（AVER/ATS 签名等）
 *  - 响应信封: {"state":"1","data":...,"errcode":...,"errmsg":...}
 *    data 为字符串时可能是 DES 加密的业务 JSON（用 SG_DES 密钥解密）
 */
class TeldApiClient(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("teld", Context.MODE_PRIVATE)

    /** 设备 UID（官方 = MD5(ANDROID_ID)，异常时用随机持久 UUID） */
    val deviceUid: String by lazy {
        val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrBlank() && androidId.length > 2 && androidId != "000000000000000" && androidId != "9774d56d682e549c") {
            md5(androidId)
        } else {
            var u = prefs.getString("uid", null)
            if (u == null) {
                u = UUID.randomUUID().toString().replace("-", "")
                prefs.edit().putString("uid", u).apply()
            }
            u
        }
    }

    /** 官方 ToolUtils.getMD5Value 输出大写十六进制（cArr = '0'..'9','A'..'F'），
     *  设备号大小写敏感，必须与官方一致（否则风控判为新设备 → BOSS-2003）。 */
    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02X".format(it) }

    // ---------- 真实设备信息（官方 Device 头用的是这些，不是写死的 Android） ----------

    /** 官方 MobileInfoUtils.MOBILE_BRADE（= Build.BRAND，空则取 MANUFACTURER） */
    private val deviceBrand: String by lazy {
        val b = android.os.Build.BRAND ?: ""
        if (b.isNotEmpty()) b else (android.os.Build.MANUFACTURER ?: "")
    }

    /** 官方 MobileInfoUtils.MOBILE_MODEL（= Build.MODEL） */
    private val deviceModel: String by lazy {
        val m = android.os.Build.MODEL ?: ""
        if (m.isNotEmpty()) m else deviceBrand
    }

    /** 官方 MobileInfoUtils.MOBILE_OS_VERSION（= Build.VERSION.RELEASE） */
    private val osVersion: String by lazy { android.os.Build.VERSION.RELEASE ?: "" }

    /** 官方 GetDeviceNameUtils.getDeviceName：蓝牙名 → Settings.Secure("device_name") → 品牌+型号 */
    private val deviceNickname: String by lazy {
        try {
            val bt = Settings.Secure.getString(appContext.contentResolver, "bluetooth_name")
            if (!bt.isNullOrEmpty()) return@lazy bt
        } catch (_: Exception) {
        }
        try {
            val dn = Settings.Secure.getString(appContext.contentResolver, "device_name")
            if (!dn.isNullOrEmpty()) return@lazy dn
        } catch (_: Exception) {
        }
        val man = android.os.Build.MANUFACTURER ?: ""
        val model = android.os.Build.MODEL ?: ""
        if (model.isEmpty()) return@lazy man
        if (model.startsWith(man)) return@lazy model.replaceFirstChar { it.uppercaseChar() }
        val m = if (man.isEmpty()) "" else man.replaceFirstChar { it.uppercaseChar() } + " "
        m + model
    }

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) {
                if (c.name == "TELDSID") {
                    prefs.edit().putString("teldsid", c.value).apply()
                }
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val sid = prefs.getString("teldsid", null) ?: return emptyList()
            return listOf(
                Cookie.Builder().name("TELDSID").value(sid).domain(url.host).path("/").build()
            )
        }
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    var host: String = "sg.teld.cn"

    /** 最近一次调用的服务码（诊断用） */
    var lastSid: String = ""

    /** 当前 AccessToken（X-Token 头） */
    var accessToken: String
        get() = prefs.getString("access_token", "") ?: ""
        private set(v) = prefs.edit().putString("access_token", v).apply()

    var sessionId: String
        get() = prefs.getString("session_id", "") ?: ""
        private set(v) = prefs.edit().putString("session_id", v).apply()

    var userId: String
        get() = prefs.getString("user_id", "") ?: ""
        private set(v) = prefs.edit().putString("user_id", v).apply()

    /** 长期有效的刷新令牌（官方 TokenStoreUtils.userToken 用它自动续期） */
    var refreshToken: String
        get() = prefs.getString("refresh_token", "") ?: ""
        private set(v) = prefs.edit().putString("refresh_token", v).apply()

    /** 服务端时间与本机时间之差（秒），来自 token 的 TimeSpan（官方 setTIME_PADDING） */
    private var timePadding: Long
        get() = prefs.getLong("time_padding", 0L)
        set(v) = prefs.edit().putLong("time_padding", v).apply()

    /** 最近一次自动续期的结果（诊断用） */
    var lastRefreshInfo: String
        get() = prefs.getString("last_refresh", "(尚未续期)") ?: "(尚未续期)"
        private set(v) = prefs.edit().putString("last_refresh", v).apply()

    @Volatile
    private var refreshing = false

    /** AccessToken（JWT）里的 exp（秒）；解析失败返回 0 */
    private fun tokenExp(): Long {
        val p = jwtPayloadDump(accessToken) ?: return 0L
        return try {
            com.google.gson.JsonParser.parseString(p).asJsonObject.get("exp")?.asLong ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /** 快过期（<2 分钟）就先刷新，避免每次请求都撞 TTP-SG-1011 */
    private fun ensureFreshToken() {
        if (accessToken.isEmpty() || refreshToken.isEmpty() || refreshing) return
        val exp = tokenExp()
        if (exp > 0 && exp - System.currentTimeMillis() / 1000 < 120) {
            netlog("## AccessToken 将于 ${exp - System.currentTimeMillis() / 1000}s 后过期，先自动续期")
            refreshUserToken()
        }
    }

    /** 用 RefreshToken 换新 AccessToken（对应官方 UserAPI-APP-SRefreshToken / CUS-APP-SRefreshToken） */
    fun refreshUserToken(): Boolean {
        if (refreshToken.isEmpty() || refreshing) return false
        refreshing = true
        try {
            val ts = (System.currentTimeMillis() / 1000).toString()
            val uver = RequestSigner.aesCbcPkcs7B64(ts, RequestSigner.UTS_AES_KEY, RequestSigner.UTS_AES_IV).take(16)
            val dataJson = JsonObject().apply {
                addProperty("DeviceId", deviceUid)
                addProperty("DeviceType", "APP")
                addProperty("ReqSource", "0")
                addProperty("RefreshToken", refreshToken)
            }
            val payload = JsonObject().apply {
                addProperty("UTS", ts)
                addProperty("UVER", uver)
                addProperty("Data", RequestSigner.aesCbcPkcs7B64(dataJson.toString(), ts.padEnd(16, '0'), uver))
            }
            val env = invoke(host, "UserAPI-APP-SRefreshToken",
                mapOf("refreshToken" to payload.toString()), withAppId = true, allowRefresh = false,
                tokenOverride = "")
            if (!env.ok) {
                netlog("## 续期失败: ${env.errcode} ${env.errmsg}")
                return false
            }
            val tokenJson = decryptTokenTwoStage(env)
            if (tokenJson == null) {
                netlog("## 续期失败：token 解密为空")
                return false
            }
            val okSaved = saveTokenJson(tokenJson)
            if (okSaved) lastRefreshInfo = "%s 续期成功" .format(
                java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
            netlog("## 续期${if (okSaved) "成功" else "失败"}${if (okSaved) "（${lastRefreshInfo}）" else ""}")
            return okSaved
        } catch (e: Exception) {
            netlog("## 续期异常: ${e.javaClass.simpleName}: ${e.message}")
            return false
        } finally {
            refreshing = false
        }
    }

    /** 保存 token JSON（登录与续期共用） */
    private fun saveTokenJson(tokenJson: String): Boolean {
        return try {
            val t = com.google.gson.JsonParser.parseString(tokenJson).asJsonObject
            val at = str(t, "AccessToken")
            if (at.isEmpty()) return false
            accessToken = at
            str(t, "SessionID").ifEmpty { null }?.let { sessionId = it }
            str(t, "userID").ifEmpty { null }?.let { userId = it }
            str(t, "RefreshToken").ifEmpty { null }?.let { refreshToken = it }
            // TimeSpan = 服务端当前秒级时间，用于 ATS 校准（官方 setTIME_PADDING）
            val svr = str(t, "TimeSpan").toLongOrNull()
            if (svr != null && svr > 1000000000L) {
                timePadding = svr - System.currentTimeMillis() / 1000
            }
            prefs.edit().putString("token_info", tokenJson).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------------- 底层调用 ----------------

    /** 网关 POST 调用（form-urlencoded，与官方 RequestNetInterfaceStrCallBack.postStringResult 一致）。
     *  ★ TELDAppID 表单字段【必须存在且为空串】：缺失 → TTP-SG-1004 参数【TELDAppID】；
     *    填成 H5 的 GUID → BOSS-2003 系统异常。 */
    fun invoke(sid: String, fields: Map<String, String>, withAppId: Boolean = true,
               tokenOverride: String? = null) =
        invoke(host, sid, fields, withAppId, allowRefresh = true, tokenOverride = tokenOverride)

    private fun invoke(host: String, sid: String, fields: Map<String, String>, withAppId: Boolean = true,
                       allowRefresh: Boolean = false, tokenOverride: String? = null): TeldEnvelope {
        lastSid = sid
        if (allowRefresh) ensureFreshToken()
        val allFields = fields + ("TELDAppID" to "")
        val url = RequestSigner.buildUrl(host, sid)
        val deviceInfo = RequestSigner.buildDeviceInfo(
            deviceId = deviceUid,
            network = "wifi",
            osVersion = osVersion,
            deviceNickname = deviceNickname,
            deviceBrand = deviceBrand,
            deviceModel = deviceModel,
        )
        val headers = RequestSigner.buildHeaders(
            // tokenOverride = "" 表示显式不带 token（登录/续期这类接口不能带过期 token，否则会被网关直接 1011 拦掉）
            token = (tokenOverride ?: accessToken.ifEmpty { visitorToken }).ifEmpty { null },
            deviceInfo = deviceInfo,
            requestId = "",
            rpcId = "",
            userAgent = RequestSigner.androidUserAgent(),
            locationCity = RequestSigner.DEFAULT_LOCATION_CITY,
            timePaddingSeconds = timePadding,
        )
        val bodyBuilder = FormBody.Builder()
        for ((k, v) in allFields) bodyBuilder.add(k, v)
        val body: RequestBody = bodyBuilder.build()

        // 完整诊断日志：SID/URL/设备号/表单字段/关键请求头/响应，全部可复制导出
        netlog("==============================================")
        netlog(">> SID=$sid")
        netlog(">> URL=$url")
        netlog(">> deviceUid=$deviceUid")
        for ((k, v) in allFields) netlog(">> FORM $k=$v")
        netlog(">> HDR ASDI=${headers["ASDI"]}")
        netlog(">> HDR Device=${headers["Device"]}")
        netlog(">> HDR X-Token=${(headers["X-Token"] ?: "").take(40)}")
        netlog(">> HDR ATS=${headers["ATS"]} AVER=${headers["AVER"]}")
        if (sessionId.isNotEmpty()) netlog(">> Cookie TELDSID=${sessionId.take(16)}...")

        val builder = Request.Builder().url(url)
        for ((k, v) in headers) builder.header(k, v)
        // TELDSID 会话 Cookie = SessionID（网关会话校验）
        if (sessionId.isNotEmpty()) {
            builder.header("Cookie", "TELDSID=$sessionId")
        }

        val request = builder.post(body).build()
        try {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                netlog("<< HTTP ${resp.code} $text")
                if (!resp.isSuccessful) {
                    throw ApiException(resp.code.toString(), "HTTP ${resp.code}: $text")
                }
                val env = parseEnvelope(text)
                // token 过期/失效 → 自动续期一次并重放该请求
                if (allowRefresh && !env.ok && (env.errcode == "TTP-SG-1011" || env.errcode == "TTP-SG-1013")) {
                    netlog("## $sid 返回 ${env.errcode}，尝试自动续期后重试")
                    if (refreshUserToken()) {
                        return invoke(host, sid, fields, withAppId, allowRefresh = false, tokenOverride = tokenOverride)
                    }
                }
                return env
            }
        } catch (e: Exception) {
            netlog("<< EX ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    /** 追加网络日志（会话内文件，最多约 300KB），每行带时间戳便于排查 */
    private fun netlog(s: String) {
        val line = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + s
        try {
            val f = appContext.getFileStreamPath("netlog.txt")
            if (f.exists() && f.length() > 300 * 1024) {
                f.delete()
            }
            appContext.openFileOutput("netlog.txt", Context.MODE_APPEND).use {
                it.write((line + "\n").toByteArray())
            }
        } catch (_: Exception) {
        }
        // 同步一份到外部目录（/sdcard/Android/data/cn.mini.teldpile/files/netlog.txt），
        // 方便用 adb 直接查看，无需 root
        try {
            val dir = appContext.getExternalFilesDir(null) ?: return
            if (!dir.exists()) dir.mkdirs()
            val ext = java.io.File(dir, "netlog.txt")
            if (ext.exists() && ext.length() > 300 * 1024) ext.delete()
            ext.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    /** 登录状态一句话描述（界面直接显示，方便确认「不用重新登录」） */
    fun loginStateText(): String {
        if (accessToken.isEmpty()) return "未登录"
        val exp = tokenExp()
        val refresh = if (refreshToken.isEmpty()) "无长期令牌" else "长期令牌已保存"
        if (exp <= 0) return "已登录（$refresh）"
        val left = exp - System.currentTimeMillis() / 1000
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        return if (left > 0) "已登录 · 当前令牌 ${fmt.format(java.util.Date(exp * 1000))} 到期（剩 ${left / 60} 分钟）· $refresh"
        else "已登录 · 当前令牌已过期，下次请求会自动续期 · $refresh"
    }

    /** 供界面层记录异常用（写进同一份 netlog） */
    fun log(s: String) = netlog(s)

    private fun readLogFile(name: String): String {
        return try {
            val f = appContext.getFileStreamPath(name)
            if (!f.exists()) "(空)" else f.readText()
        } catch (e: Exception) {
            "(读取失败: ${e.message})"
        }
    }

    /** 解码 JWT 负载（去 B01 前缀），返回可读字符串；失败返回 null */
    private fun jwtPayloadDump(token: String): String? {
        return try {
            val t = token.removePrefix("B01")
            val parts = t.split(".")
            if (parts.size < 2) return null
            val raw = android.util.Base64.decode(
                parts[1],
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            )
            String(raw, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** 导出完整诊断报告（可直接复制发给开发者定位问题） */
    fun diagnostics(): String {
        val androidId = try {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) { "(读取失败)" }
        var verName = ""
        try {
            verName = appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
        }
        val sb = StringBuilder()
        sb.appendLine("========== TeldPileMini 诊断报告 ==========")
        sb.appendLine("时间: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
        sb.appendLine("APP 版本: $verName")
        sb.appendLine("ANDROID_ID: $androidId")
        sb.appendLine("deviceUid(MD5 大写): $deviceUid")
        sb.appendLine("已登录: ${isLoggedIn()}")
        sb.appendLine("AccessToken 到期: " + run {
            val exp = tokenExp()
            if (exp <= 0) "(解析失败)" else {
                val left = exp - System.currentTimeMillis() / 1000
                java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(exp * 1000)) +
                        "（剩余 ${left}s）"
            }
        })
        sb.appendLine("RefreshToken: " + if (refreshToken.isEmpty()) "缺失（下次会要求重新登录）"
        else "已保存（长度 ${refreshToken.length}，可长期免登录）")
        sb.appendLine("最近自动续期: $lastRefreshInfo")
        sb.appendLine("userId: $userId")
        sb.appendLine("sessionId: ${sessionId.ifEmpty { "(空)" }}")
        val at = accessToken
        sb.appendLine("accessToken 前缀40: ${at.take(40)}")
        val payload = if (at.isEmpty()) null else jwtPayloadDump(at)
        sb.appendLine("accessToken 负载(含 dev_id): ${payload ?: "(解析失败)"}")
        sb.appendLine("visitorToken 前缀40: ${visitorToken.take(40)}")
        sb.appendLine()
        sb.appendLine("========== 网络日志 netlog.txt ==========")
        sb.appendLine(readLogFile("netlog.txt"))
        sb.appendLine("========== 最近响应 last_response.txt ==========")
        sb.appendLine(readLogFile("last_response.txt"))
        return sb.toString()
    }

    /** 安全取字符串字段：null/JsonNull 一律返回 ""，避免 Gson 抛 "JsonNull" */
    private fun str(o: com.google.gson.JsonObject?, key: String): String {
        val e = o?.get(key) ?: return ""
        return if (e.isJsonNull || !e.isJsonPrimitive) "" else e.asString
    }

    /** 按多个候选键名取字符串 */
    private fun strAny(o: com.google.gson.JsonObject?, vararg keys: String): String {
        for (k in keys) {
            val v = str(o, k)
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private fun parseEnvelope(text: String): TeldEnvelope {
        val o = com.google.gson.JsonParser.parseString(text).asJsonObject
        val state = str(o, "state")
        val errcode = str(o, "errcode")
        val errmsg = str(o, "errmsg")
        val data = o.get("data")?.takeIf { !it.isJsonNull }
        // 调试：保存最近一次网关原始响应
        try {
            appContext.openFileOutput("last_response.txt", Context.MODE_PRIVATE).use { fw ->
                fw.write(text.toByteArray())
            }
        } catch (_: Exception) {
        }
        return TeldEnvelope(state == "1", state, errcode, errmsg, data)
    }

    /** data 若是 DES 加密的业务 JSON 字符串，则解密返回明文 JSON 字符串 */
    private fun decryptData(env: TeldEnvelope): String? {
        val d = env.data ?: return null
        if (d.isJsonPrimitive && d.asJsonPrimitive.isString) {
            val s = d.asString
            return try {
                desDecode(s)
            } catch (e: Exception) {
                s
            }
        }
        return d.toString()
    }

    private fun desEncode(plain: String): String =
        RequestSigner.desCbcPkcs7B64(plain, RequestSigner.SG_DES_KEY, RequestSigner.SG_DES_IV)

    private fun desDecode(b64: String): String {
        val cipher = javax.crypto.Cipher.getInstance("DES/CBC/PKCS7Padding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(RequestSigner.SG_DES_KEY.toByteArray(Charsets.UTF_8), "DES"),
            javax.crypto.spec.IvParameterSpec(RequestSigner.SG_DES_IV.toByteArray(Charsets.UTF_8))
        )
        val raw = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
        return String(cipher.doFinal(raw), Charsets.UTF_8)
    }

    // ---------------- 匿名游客 token（登录前必须先获取） ----------------

    /** 游客 AccessToken（登录前使用） */
    var visitorToken: String
        get() = prefs.getString("visitor_token", "") ?: ""
        private set(v) = prefs.edit().putString("visitor_token", v).apply()

    private var visitorRefreshToken: String
        get() = prefs.getString("visitor_refresh", "") ?: ""
        set(v) = prefs.edit().putString("visitor_refresh", v).apply()

    /** ASLogin 匿名登录，获取游客 token（与官方 TokenStoreUtils.visitorLogin 一致） */
    fun visitorLogin(): TeldEnvelope {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val uver = RequestSigner.aesCbcPkcs7B64(ts, RequestSigner.UTS_AES_KEY, RequestSigner.UTS_AES_IV)
            .take(16)
        val key16 = ts.padEnd(16, '0')
        val dataJson = JsonObject().apply {
            addProperty("DeviceId", deviceUid)
            addProperty("DeviceType", "APP")
            addProperty("ReqSource", "0")
        }
        val data = RequestSigner.aesCbcPkcs7B64(dataJson.toString(), key16, uver)
        val loginInfo = JsonObject().apply {
            addProperty("UTS", ts)
            addProperty("Data", data)
            addProperty("UVER", uver)
            addProperty("UUID", UUID.randomUUID().toString().replace("-", ""))
        }
        // 登录接口不能带任何（可能已过期的）token，否则被网关 1011 直接拦掉
        val env = invoke("UserAPI-APP-ASLogin", mapOf("loginInfo" to loginInfo.toString()), tokenOverride = "")
        if (env.ok) {
            val tokenJson = decryptTokenTwoStage(env)
            if (tokenJson != null) {
                try {
                    val token = com.google.gson.JsonParser.parseString(tokenJson).asJsonObject
                    str(token, "AccessToken").ifEmpty { null }?.let { visitorToken = it }
                    str(token, "RefreshToken").ifEmpty { null }?.let { visitorRefreshToken = it }
                    prefs.edit().putString("visitor_info", tokenJson).apply()
                } catch (e: Exception) {
                }
            }
        }
        return env
    }

    /** 官方 decryptToken：外层 AES(UTS 密钥) → 内层 AES(UTS+"000000", UVER) */
    private fun decryptTokenTwoStage(env: TeldEnvelope): String? {
        val d = env.data ?: return null
        val outer = if (d.isJsonPrimitive && d.asJsonPrimitive.isString) d.asString else return null
        return try {
            val stepJson = RequestSigner.aesCbcPkcs7Decode(outer, RequestSigner.UTS_AES_KEY, RequestSigner.UTS_AES_IV)
            val step = com.google.gson.JsonParser.parseString(stepJson).asJsonObject
            val data = str(step, "Data").ifEmpty { return null }
            val uts = str(step, "UTS").ifEmpty { return null }
            val uver = str(step, "UVER").ifEmpty { return null }
            RequestSigner.aesCbcPkcs7Decode(data, uts + "000000", uver)
        } catch (e: Exception) {
            null
        }
    }

    /** 任意 JWT（B01 前缀）的 exp（秒）；解析失败返回 0 */
    private fun tokenExpOf(jwt: String): Long {
        val p = jwtPayloadDump(jwt) ?: return 0L
        return try {
            com.google.gson.JsonParser.parseString(p).asJsonObject.get("exp")?.asLong ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /** 确保有一个「还没过期」的游客 token（过期就重新 ASLogin） */
    fun ensureVisitorToken() {
        val now = System.currentTimeMillis() / 1000
        if (visitorToken.isEmpty() || tokenExpOf(visitorToken) - now < 120) {
            netlog("## 游客 token ${if (visitorToken.isEmpty()) "缺失" else "即将过期"}，重新 ASLogin")
            visitorLogin()
        }
    }

    // ---------------- 登录（短信验证码） ----------------

    /** 发送短信验证码（未登录时自动携带游客 token） */
    fun sendSmsCode(phone: String, areaCode: String = "86"): TeldEnvelope {
        if (accessToken.isEmpty()) ensureVisitorToken()
        val json = JsonObject().apply {
            addProperty("Mobile", "$areaCode-$phone")
            addProperty("SendType", "5")
        }
        val env0 = invoke("UserAPI-APP-MSendSMSCode", mapOf("smsCodeInfo" to desEncode(json.toString())))
        if (env0.ok) return env0
        // 游客 token 失效 → 重新匿名登录后再发一次
        if (env0.errcode == "TTP-SG-1011" || env0.errcode == "TTP-SG-1013") {
            netlog("## 发验证码遇到 ${env0.errcode}，重新 ASLogin 后重试")
            visitorLogin()
            return invoke("UserAPI-APP-MSendSMSCode", mapOf("smsCodeInfo" to desEncode(json.toString())))
        }
        return env0
    }

    /** 校验验证码 */
    fun verifySmsCode(phone: String, code: String, areaCode: String = "86"): TeldEnvelope {
        val json = JsonObject().apply {
            addProperty("Mobile", "$areaCode-$phone")
            addProperty("SendType", "5")
            addProperty("VCode", code)
        }
        return invoke("UserAPI-APP-NVerifySMSCode", mapOf("verifyMobileInfo" to desEncode(json.toString())))
    }

    /** 验证码登录：先校验验证码（NVerifySMSCode），再登录（NLoginWithVCode） */
    fun loginWithSmsCode(phone: String, code: String, areaCode: String = "86"): TeldEnvelope {
        val verify = verifySmsCode(phone, code, areaCode)
        if (!verify.ok) return verify
        val json = JsonObject().apply {
            addProperty("regCity", "")
            addProperty("AccountType", "1")
            addProperty("ReqSource", "0")
            addProperty("UserName", "$areaCode-$phone")
            addProperty("VCode", code)
            addProperty("DeviceId", deviceUid)
            addProperty("ClientId", "")
            addProperty("DeviceType", "APP")
            addProperty("Extra", "{\"PromotionID\": \"\"}")
        }
        val env = invoke("UserAPI-APP-NLoginWithVCode", mapOf("loginInfo" to desEncode(json.toString())))
        if (env.ok) {
            val data = decryptData(env)
            if (data != null) {
                // 保存 AccessToken / SessionID / RefreshToken / 时间校准
                if (!saveTokenJson(data)) {
                    netlog("## 登录返回解析异常: ${data.take(200)}")
                } else {
                    netlog("## 登录成功，已保存 token（RefreshToken ${if (refreshToken.isEmpty()) "缺失" else "已保存"}）")
                }
            }
        }
        return env
    }

    // ---------------- 我的桩 ----------------

    /** 我的桩列表（AASS-APP0603_MyPiles，表单需带 TELDAppID） */
    fun getMyPiles(): List<MyPile> {
        val env = invoke("AASS-APP0603_MyPiles", emptyMap(), withAppId = true)
        if (!env.ok) throw ApiException(env.errcode, env.errmsg ?: "获取桩列表失败")
        return MyPile.fromPilesEnvelope(env)
    }

    /** 单桩详情列表（CPM-GetMySinglePileList，含 terminalCode/状态等） */
    fun getMySinglePileList(): List<MyPile> {
        val env = invoke("CPM-GetMySinglePileList", emptyMap())
        if (!env.ok) throw ApiException(env.errcode, env.errmsg ?: "获取单桩详情失败")
        return MyPile.fromSinglePileEnvelope(env)
    }

    // ---------------- 启动 / 停止（走官方路由流程） ----------------

    /** 最近一次的桩路由（实时数据接口要用它的 IDCSG 机房） */
    var lastRouting: PileRouting? = null
        private set

    /** CQS-GetStaRoutingByPileCodeV3：桩路由（决定数据中心与 V1/V2 流程） */
    fun getRouting(pileCode: String): PileRouting {
        val env = invoke("CQS-GetStaRoutingByPileCodeV3", mapOf("pileCode" to pileCode))
        if (!env.ok) throw ApiException(env.errcode, env.errmsg ?: "获取路由失败")
        val d = env.data?.takeIf { it.isJsonObject }
            ?: throw ApiException("", "路由数据为空")
        val o = d.asJsonObject
        val r = PileRouting(
            idcSg = strAny(o, "IDCSG", "idcSG", "idcSg"),
            pileCode = strAny(o, "PileCode", "pileCode"),
            cloudPlatformVer = strAny(o, "CloudPlatformVer", "cloudPlatformVer"),
            chargeServiceType = strAny(o, "ChargeServiceType", "chargeServiceType").toIntOrNull() ?: 0,
            stationId = strAny(o, "StationID", "stationID", "stationId")
        )
        lastRouting = r
        return r
    }

    /** 把 IDCSG（可能为域名或完整 URL）归一化为 hostname */
    private fun hostOf(idcSg: String): String =
        idcSg.removePrefix("https://").removePrefix("http://").trimEnd('/')
            .ifEmpty { host }

    /** 启动参数（官方 getSetStartChargeParamsSuper；IsUseGiftCard 仅 CMGen/V2 才加） */
    private fun startParamsJson(terminalCode: String, includeGiftCard: Boolean = false): String {
        val json = JsonObject().apply {
            addProperty("companyId", "")
            addProperty("thirdBalanceAccountID", "")
            addProperty("ownerCompanyID", "")
            addProperty("chargeType", 1)
            addProperty("terminalCode", terminalCode)
            addProperty("merchantCode", "")
            addProperty("thirdpartyOrderCode", "")
            addProperty("deviceID", deviceUid)
            addProperty("interconnectionId", "")
            addProperty("thridAppId", "")
            addProperty("carTypeID", "")
            addProperty("carNO", "")
            addProperty("sourceType", 1)
            addProperty("source", "app")
            // 官方恒带可解析经纬度（AMapLocation；无定位时是 0.0），绝不发空串
            addProperty("lng", RequestSigner.DEFAULT_LNG)
            addProperty("lat", RequestSigner.DEFAULT_LAT)
            addProperty("SOC", "")
            addProperty("leftRun", "")
            addProperty("carRun", "")
            addProperty("isXin", false)
            addProperty("payScore", false)
            addProperty("isAutoRefund", "0")
            addProperty("preRechargeMoney", "")
            if (includeGiftCard) addProperty("IsUseGiftCard", false)
        }
        return json.toString()
    }

    /** 立即充电（按桩路由自动选择 V1/V2 流程）。
     *
     *  与官方 APP v7.16.0 抓包（sgh2c.teld.cn / CMInd-StartChargeByAPPV1）逐字段对齐：
     *   - 表单：param=<desTeldEncode(参数JSON)> + TELDAppID=（空串，字段必须存在）
     *   - 头部：TELDAppID=（空串）、ATS/AVER 签名、ASDI 设备号、X-Token、Cookie: TELDSID
     *   - 启动后轮询 CMGen-StartChargeResultByAPPV2 直到 ChargeCmdExecResult=1
     */
    fun startChargeImmediately(terminalCode: String, pileCode: String): TeldEnvelope {
        val routing = try {
            getRouting(pileCode)
        } catch (e: Exception) {
            throw ApiException(null, "获取路由失败: ${e.message}")
        }
        val targetHost = hostOf(routing.idcSg)
        netlog("## START routing: idcSg=${routing.idcSg} type=${routing.chargeServiceType} ver=${routing.cloudPlatformVer} newRouter=${routing.isNewRouter}")

        if (!routing.isNewRouter) {
            val params = startParamsJson(terminalCode)
            netlog("## START plaintext params: $params")
            return invoke(targetHost, "BaseApi-ChargeImmediately",
                mapOf("param" to RequestSigner.desTeldEncode(params)), withAppId = true)
        }
        val sid = when (routing.chargeServiceType) {
            2 -> "CMBus-StartChargeByAPPV1"
            3 -> "CMInd-StartChargeByAPPV1"
            4 -> "CMOpen-StartChargeByAPPV1"
            else -> "CMGen-StartChargeByAPPV2"
        }
        // 官方仅 CMGen/V2 流程附加 IsUseGiftCard（StartChargeCM5Module terminaltype==1）
        val params = startParamsJson(terminalCode, includeGiftCard = sid == "CMGen-StartChargeByAPPV2")
        netlog("## START plaintext params: $params")
        // 实测：网关与数据中心都强制要求 TELDAppID 表单字段，缺失会报 TTP-SG-1004
        var env = invoke(targetHost, sid, mapOf("param" to RequestSigner.desTeldEncode(params)), withAppId = true)
        // BOSS-1197：上一笔充电业务尚未结算完（官方 App 偶发）；自动重试最多 6 次
        var attempt = 1
        while (!env.ok && env.errcode == "BOSS-1197" && attempt < 6) {
            netlog("## START 重试 $attempt（BOSS-1197 未结束业务）")
            Thread.sleep(8000)
            env = invoke(targetHost, sid, mapOf("param" to RequestSigner.desTeldEncode(params)), withAppId = true)
            attempt++
        }
        if (!env.ok) return env

        // 启动成功后官方继续轮询 CMGen-StartChargeResultByAPPV2（ChargeCmdExecResult 1=成功,2=失败）
        val billId = env.data?.let { d ->
            if (d.isJsonObject) str(d.asJsonObject, "billID")
                .ifEmpty { str(d.asJsonObject, "BillID") }
                .ifEmpty { str(d.asJsonObject, "BillId") }
                .ifEmpty { null }
            else null
        } ?: return env
        val deadline = System.currentTimeMillis() + 60_000
        var last = env
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(3000)
            val result = invoke(targetHost, "CMGen-StartChargeResultByAPPV2",
                mapOf("param" to RequestSigner.desTeldEncode("""{"BillId":"$billId"}""")), withAppId = true)
            last = result
            val data = result.data
            val exec = (data?.takeIf { it.isJsonObject }?.asJsonObject?.let {
                (str(it, "ChargeCmdExecResult") ?: "0").toIntOrNull() ?: 0
            }) ?: 0
            if (exec == 1) return result
            if (exec == 2) return result
        }
        return last
    }

    /** 结束充电（按路由走对应 SID） */
    fun stopCharge(terminalCode: String, pileCode: String): TeldEnvelope {
        val routing = try {
            getRouting(pileCode)
        } catch (e: Exception) {
            throw ApiException(null, "获取路由失败: ${e.message}")
        }
        val targetHost = hostOf(routing.idcSg)
        val json = """{"terminalCode":"$terminalCode"}"""
        if (!routing.isNewRouter) {
            return invoke(targetHost, "BaseApi-EndCharge",
                mapOf("param" to RequestSigner.desTeldEncode(json)), withAppId = true)
        }
        val sid = when (routing.chargeServiceType) {
            1 -> "CMGen-StopChargingByAPPV1"
            2 -> "CMBus-StopChargingByAPPV1"
            3 -> "CMInd-StopChargingByAPPV1"
            4 -> "CMOpen-StopChargingByAPPV1"
            else -> "CMGen-StopChargingByAPPV1"
        }
        return invoke(targetHost, sid, mapOf("param" to RequestSigner.desTeldEncode(json)), withAppId = true)
    }

    /** 订单明细（官方 ChargeBill-GetChargeBillV2，主机取桩机房 IDCSG，param 为明文 JSON） */
    fun getChargeBill(billId: String, idcSg: String): ChargeSession? {
        val targetHost = hostOf(idcSg)
        val env = invoke(targetHost, "ChargeBill-GetChargeBillV2",
            mapOf("param" to """{"BillId":"$billId","AppVersion":1}"""), withAppId = true)
        if (!env.ok) {
            netlog("## 订单明细失败 ${env.errcode} ${env.errmsg}")
            return null
        }
        return ChargeSession.fromEnvelope(env)
    }

    /**
     * 当前充电订单列表（官方 ChargeInfoNetWorkModel.loadChargeInfoList）：
     * 表单 Param = 明文 JSON {"merchantCode":""}（注意大写 P），主机 sg.teld.cn。
     */
    fun getChargeSessions(): List<ChargeSession> {
        val env = invoke("AACS-ChargeInfo", mapOf("Param" to """{"merchantCode":""}"""), withAppId = true)
        if (!env.ok) {
            netlog("## 订单信息失败 ${env.errcode} ${env.errmsg}")
            return emptyList()
        }
        return ChargeSession.listFromEnvelope(env)
    }

    /** 单次充电信息（OCQSG-GetPersonalPileChareInfo，表单 orderId） */
    fun getPileChargeInfo(orderId: String): TeldEnvelope =
        invoke("OCQSG-GetPersonalPileChareInfo", mapOf("orderId" to orderId))

    /**
     * 实时充电数据（官方 ChargeInfoNetWorkModel.getCurrentAndVoltageInfo）：
     * 表单 param = 明文 JSON {"PileCodes":["<终端号>"]}（这个接口【不】做 DES 加密），
     * 主机取桩所在机房 IDCSG。返回电压/电流/已充电量/已充时长/SOC。
     */
    fun getRealtime(terminalCode: String, idcSg: String): ChargeRealtime? {
        val targetHost = hostOf(idcSg)
        val param = """{"PileCodes":["$terminalCode"]}"""
        val env = invoke(targetHost, "AAPI-V0700-DRDC2-AppQueryRealTimeDataNew",
            mapOf("param" to param), withAppId = true)
        if (!env.ok) {
            netlog("## 实时数据失败 ${env.errcode} ${env.errmsg}")
            return null
        }
        return ChargeRealtime.fromEnvelope(env)
    }

    fun isLoggedIn(): Boolean = accessToken.isNotEmpty()

    fun logout() {
        prefs.edit()
            .remove("access_token").remove("session_id").remove("user_id")
            .remove("teldsid").remove("token_info").remove("refresh_token")
            .remove("visitor_token").remove("visitor_refresh").remove("visitor_info")
            .apply()
    }

    companion object {
        const val appVersion = "7.16.0"
    }
}
