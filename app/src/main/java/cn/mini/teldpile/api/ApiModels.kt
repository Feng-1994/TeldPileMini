package cn.mini.teldpile.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** TELD 网关统一响应信封: {"data":..., "errcode":"...", "errmsg":"...", "state":"..."} */
data class TeldEnvelope(
    val ok: Boolean,
    val state: String,
    val errcode: String,
    val errmsg: String?,
    val data: JsonElement?
)

/** 桩路由信息（CQS-GetStaRoutingByPileCodeV3 响应） */
data class PileRouting(
    val idcSg: String = "",
    val pileCode: String = "",
    val cloudPlatformVer: String = "",
    val chargeServiceType: Int = 0,
    val stationId: String = ""
) {
    /** 官方判定：CloudPlatformVer >= 4.0 走新路由（V2 启动流程） */
    val isNewRouter: Boolean get() = cloudPlatformVer.toDoubleOrNull()?.let { it >= 4.0 } ?: false
}

class ApiException(val code: String?, message: String) : Exception(message)

/**
 * 实时充电数据（AAPI-V0700-DRDC2-AppQueryRealTimeDataNew 响应，官方 CurrentAndVoltageInfo）。
 * 官方充电页的「功率/电压/电流/已充电量/已充时长」就来自这里。
 */
data class ChargeRealtime(
    val voltage: Double = 0.0,      // Voltage  (V)
    val ampere: Double = 0.0,       // Ampere   (A)
    val totalKwh: Double = 0.0,     // TotalWorkPower (度)
    val duration: String = "",      // ChargingLastTime
    val soc: Int = 0,               // SOC %
    val maxBatteryTemp: Int = 0,
    val raw: JsonObject = JsonObject()
) {
    /** 功率(kW) = 电压 × 电流 / 1000（与官方充电页算法一致） */
    val powerKw: Double get() = voltage * ampere / 1000.0

    companion object {
        fun fromEnvelope(env: TeldEnvelope): ChargeRealtime? {
            val arr = env.data?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            val o = arr.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            fun d(vararg keys: String): Double {
                for (k in keys) {
                    val e = o.get(k) ?: continue
                    if (e.isJsonNull) continue
                    val s = if (e.isJsonPrimitive) e.asString else continue
                    s.toDoubleOrNull()?.let { return it }
                }
                return 0.0
            }
            fun s(vararg keys: String): String {
                for (k in keys) {
                    val e = o.get(k) ?: continue
                    if (e.isJsonNull) continue
                    if (e.isJsonPrimitive) return e.asString
                }
                return ""
            }
            return ChargeRealtime(
                voltage = d("Voltage", "DirectVoltage"),
                ampere = d("Ampere", "DirectCurrent"),
                totalKwh = d("TotalWorkPower", "PositiveWorkMeter"),
                duration = s("ChargingLastTime"),
                soc = d("SOC").toInt(),
                maxBatteryTemp = d("MaxBatteryTemp").toInt(),
                raw = o
            )
        }
    }
}

/** 当前充电订单（AACS-ChargeInfo 响应，官方 ChargingStationModel 的关键子集） */
data class ChargeSession(
    val terminalCode: String = "",
    val chargeId: String = "",
    val chargeCode: String = "",
    val startTime: String = "",
    val chargeTime: String = "",
    val chargingLastTime: String = "",
    val chargeElectric: String = "",
    val electricMoney: String = "",
    val serviceMoney: String = "",
    val soc: String = "",
    val stateName: String = "",
    val idcSg: String = ""
) {
    /** 开始时间：兼容 "20260913085640" 与 "2026-09-13 08:56:40" 两种格式，输出 HH:mm:ss */
    fun startTimeText(): String {
        if (startTime.isEmpty()) return ""
        val s = startTime.trim()
        return when {
            s.length >= 14 && s.all { it.isDigit() } ->
                "${s.substring(8, 10)}:${s.substring(10, 12)}:${s.substring(12, 14)}"
            s.contains(' ') -> s.substringAfter(' ')
            else -> s
        }
    }

    /** 已充时长：优先用接口给的可读串，其次按开始时间自己算 */
    fun durationText(): String {
        val t = chargingLastTime.ifEmpty { chargeTime ?: "" }
        if (t.isNotEmpty() && t != "0" && t != "null") return t
        if (startTime.isEmpty()) return ""
        return try {
            val s = startTime.trim()
            val start = when {
                s.length >= 14 && s.all { it.isDigit() } ->
                    java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).parse(s)?.time
                else ->
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).parse(s)?.time
            } ?: return ""
            val sec = (System.currentTimeMillis() - start) / 1000
            if (sec <= 0) "" else String.format(
                java.util.Locale.US, "%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        fun listFromEnvelope(env: TeldEnvelope): List<ChargeSession> {
            val d = env.data ?: return emptyList()
            val arr = when {
                d.isJsonArray -> d.asJsonArray
                d.isJsonObject -> d.asJsonObject.getAsJsonArray("list") ?: return emptyList()
                else -> return emptyList()
            }
            fun s(o: JsonObject, vararg keys: String): String {
                for (k in keys) {
                    val e = o.get(k) ?: continue
                    if (!e.isJsonNull && e.isJsonPrimitive) return e.asString
                }
                return ""
            }
            return arr.mapNotNull { it.takeIf { x -> x.isJsonObject }?.asJsonObject }.map { o ->
                ChargeSession(
                    terminalCode = s(o, "terminalCode", "TerminalCode"),
                    chargeId = s(o, "chargeId", "ChargeId"),
                    chargeCode = s(o, "chargeCode", "ChargeCode"),
                    startTime = s(o, "startTime", "StartTime"),
                    chargeTime = s(o, "chargeTime", "ChargeTime"),
                    chargingLastTime = s(o, "chargingLastTime", "ChargingLastTime"),
                    chargeElectric = s(o, "chargeElectric", "ChargeElectric"),
                    electricMoney = s(o, "electricMoney", "ElectricMoney"),
                    serviceMoney = s(o, "serviceMoney", "ServiceMoney"),
                    soc = s(o, "soc", "SOC"),
                    stateName = s(o, "terminalStateName", "TerminalStateName"),
                    idcSg = s(o, "IDCSG", "idcsg", "dataCenterLocation")
                )
            }
        }

        /** 单条订单明细（ChargeBill-GetChargeBillV2 的 data 是对象） */
        fun fromEnvelope(env: TeldEnvelope): ChargeSession? {
            val d = env.data ?: return null
            val o = when {
                d.isJsonObject -> d.asJsonObject
                d.isJsonArray -> d.asJsonArray.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                else -> null
            } ?: return null
            fun s(vararg keys: String): String {
                for (k in keys) {
                    val e = o.get(k) ?: continue
                    if (!e.isJsonNull && e.isJsonPrimitive) return e.asString
                }
                return ""
            }
            return ChargeSession(
                terminalCode = s("terminalCode", "TerminalCode"),
                chargeId = s("chargeId", "ChargeId"),
                chargeCode = s("chargeCode", "ChargeCode"),
                startTime = s("startTime", "StartTime"),
                chargeTime = s("chargeTime", "ChargeTime"),
                chargingLastTime = s("chargingLastTime", "ChargingLastTime"),
                chargeElectric = s("chargeElectric", "ChargeElectric", "totalPower", "TotalPower"),
                electricMoney = s("electricMoney", "ElectricMoney"),
                serviceMoney = s("serviceMoney", "ServiceMoney"),
                soc = s("soc", "SOC"),
                stateName = s("terminalStateName", "TerminalStateName")
            )
        }
    }
}

/** 我的桩（家充桩）模型 —— 字段名与官方接口一致 */
data class MyPile(
    val pileId: String = "",
    val pileCode: String = "",
    val pileName: String = "",
    val stationId: String = "",
    val stationName: String = "",
    val imgUrl: String = "",
    val status: String = "",
    val statusName: String = "",
    val isOnline: Boolean = false,
    /** 来自 CPM-GetMySinglePileList 的详情字段 */
    val terminalCode: String = "",
    val terminalBrandName: String = "",
    val terminalTypeName: String = "",
    val isNetworkEnable: Boolean = false,
    val raw: JsonObject = JsonObject()
) {
    /**
     * 是否处于「有未结束的充电业务」状态。
     * 02 充电中 / 06 暂停中 / 03 启动中 等都算；00 空闲 / 01 未插枪 / 05 已插枪 不算。
     * （凭状态名兜底：状态码未知时只要名字里带「充电/暂停/启动」就视为进行中。）
     */
    val isBusy: Boolean
        get() {
            if (status in listOf("00", "01", "05")) return false
            if (status == "02" || status == "03" || status == "04" || status == "06" ||
                status == "07" || status == "08") return true
            return statusName.contains("充电") || statusName.contains("暂停") || statusName.contains("启动")
        }

    companion object {
        fun fromJson(o: JsonObject): MyPile {
            fun s(vararg keys: String): String {
                for (k in keys) {
                    val e = o.get(k) ?: continue
                    if (!e.isJsonNull) return e.asString
                }
                return ""
            }
            return MyPile(
                pileId = s("id", "ID", "singlePileId", "PileID", "pileId"),
                pileCode = s("code", "Code", "terminalCode", "pileCode"),
                pileName = s("name", "Name", "productName", "pileName"),
                stationId = s("stationID", "StaID", "staID"),
                stationName = s("stationName", "staName", "StaName"),
                imgUrl = s("imgUrl", "productImageURL", "ImgUrl"),
                status = s("stateCode", "terminalStateCode", "State", "state"),
                statusName = s("stateName", "terminalStateName", "StateName", "stateName"),
                isOnline = o.get("isNetworkEnable")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                terminalCode = s("terminalCode", "code"),
                terminalBrandName = s("terminalBrandName"),
                terminalTypeName = s("terminalTypeName"),
                isNetworkEnable = o.get("isNetworkEnable")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                raw = o
            )
        }

        /** AASS-APP0603_MyPiles 响应：{"personalPiles":[...],"specialPiles":[...]} */
        fun fromPilesEnvelope(env: TeldEnvelope): List<MyPile> {
            val d = env.data ?: return emptyList()
            if (!d.isJsonObject) return emptyList()
            val o = d.asJsonObject
            val out = mutableListOf<MyPile>()
            for (key in listOf("personalPiles", "specialPiles")) {
                val e = o.get(key) ?: continue
                if (e.isJsonArray) {
                    for (x in e.asJsonArray) {
                        if (x.isJsonObject) out.add(fromJson(x.asJsonObject))
                    }
                }
            }
            return out
        }

        /** CPM-GetMySinglePileList 响应：data = PersonalPileModel 数组 */
        fun fromSinglePileEnvelope(env: TeldEnvelope): List<MyPile> {
            val d = env.data ?: return emptyList()
            if (!d.isJsonArray) return emptyList()
            return d.asJsonArray.mapNotNull { x ->
                if (x.isJsonObject) fromJson(x.asJsonObject) else null
            }
        }
    }
}
