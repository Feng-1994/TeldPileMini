package cn.mini.teldpile

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import cn.mini.teldpile.api.ApiException
import cn.mini.teldpile.api.MyPile
import cn.mini.teldpile.api.TeldApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var api: TeldApiClient
    private var pile: MyPile? = null
    private var charging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        api = TeldApiClient(this)

        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            api.logout()
            startActivity(android.content.Intent(this, LoginActivity::class.java))
            finish()
        }

        findViewById<Button>(R.id.btnCharge).setOnClickListener {
            val p = pile ?: return@setOnClickListener
            if (p.terminalCode.isEmpty()) {
                Toast.makeText(this, "未获取到桩编码，请下拉刷新", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (p.pileCode.isEmpty()) {
                Toast.makeText(this, "未获取到桩号，请下拉刷新", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!charging) {
                doStart(p)
            } else {
                doStop(p)
            }
        }

        findViewById<SwipeRefreshLayout>(R.id.swipeRefresh).setOnRefreshListener {
            refresh()
        }

        // 诊断日志：一键复制 / 分享
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val text = api.diagnostics()
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("teld-log", text))
            Toast.makeText(this, "日志已复制（${text.length} 字符），去微信/备忘录粘贴发我", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnShareLog).setOnClickListener {
            val text = api.diagnostics()
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
            }
            startActivity(android.content.Intent.createChooser(send, "分享诊断日志"))
        }

        refresh()
    }

    private fun refresh() {
        findViewById<SwipeRefreshLayout>(R.id.swipeRefresh).isRefreshing = true
        lifecycleScope.launch {
            try {
                val piles = withContext(Dispatchers.IO) { api.getMyPiles() }
                if (piles.isEmpty()) {
                    pile = null
                    findViewById<View>(R.id.tvEmpty).visibility = View.VISIBLE
                } else {
                    findViewById<View>(R.id.tvEmpty).visibility = View.GONE
                    var p = piles.first()
                    // 用单桩列表补充 terminalCode 与实时状态（按 id/code 匹配）
                    try {
                        val details = withContext(Dispatchers.IO) { api.getMySinglePileList() }
                        val match = details.firstOrNull {
                            (it.pileId.isNotEmpty() && it.pileId == p.pileId) ||
                                (it.terminalCode.isNotEmpty() && it.terminalCode == p.pileCode)
                        } ?: details.firstOrNull()
                        if (match != null) p = match.copy(raw = p.raw)
                    } catch (e2: Exception) {
                        api.log("## 单桩详情失败: ${e2.javaClass.simpleName}: ${e2.message}")
                    }
                    pile = p
                    render(p)
                }
            } catch (e: Exception) {
                api.log("## 刷新失败: ${e.javaClass.simpleName}: ${e.message}")
                val code = (e as? ApiException)?.code ?: ""
                if (code.startsWith("TTP-SG-1011") || code.startsWith("TTP-SG-1013") ||
                    (e.message ?: "").contains("Token")) {
                    Toast.makeText(this@MainActivity, "登录已过期，请重新登录", Toast.LENGTH_LONG).show()
                    api.logout()
                    startActivity(android.content.Intent(this@MainActivity, LoginActivity::class.java))
                    finish()
                    return@launch
                }
                Toast.makeText(this@MainActivity, e.message ?: getString(R.string.err_unknown), Toast.LENGTH_LONG).show()
            } finally {
                findViewById<SwipeRefreshLayout>(R.id.swipeRefresh).isRefreshing = false
            }
        }
    }

    private fun render(p: MyPile) {
        findViewById<TextView>(R.id.tvPileName).text =
            p.pileName.ifEmpty { p.terminalBrandName.ifEmpty { p.pileCode.ifEmpty { "我的充电桩" } } }
        findViewById<TextView>(R.id.tvPileCode).text = p.pileCode.ifEmpty { p.terminalCode }
        val onlineTv = findViewById<TextView>(R.id.tvOnline)
        onlineTv.text = if (p.isOnline) getString(R.string.pile_online) else getString(R.string.pile_offline)
        onlineTv.background = pill(if (p.isOnline) 0xFF00B856.toInt() else 0xFF9AA0A6.toInt())

        val statusTv = findViewById<TextView>(R.id.tvStatus)
        statusTv.text = p.statusName.ifEmpty { getString(R.string.status_idle) }
        // 充电中 / 暂停中 都算「有未结束业务」→ 按钮给「停止充电」，否则只能看着报 BOSS-1197
        charging = p.isBusy
        findViewById<Button>(R.id.btnCharge).text =
            getString(if (charging) R.string.btn_stop else R.string.btn_start)

        // 有未结束业务才显示实时充电信息卡片，并开始轮询
        findViewById<View>(R.id.llRealtime).visibility = if (charging) View.VISIBLE else View.GONE
        if (charging) startRealtimePolling() else stopRealtimePolling()

        // 登录状态（证明「只需登录一次」：令牌到期会自动续期）
        findViewById<TextView>(R.id.tvLoginState).text =
            api.loginStateText() + " · 最近续期 " + api.lastRefreshInfo
    }

    // ---------------- 实时充电信息（功率/电压/电流/已充电量/已充时长/SOC） ----------------

    private var pollJob: kotlinx.coroutines.Job? = null

    private fun startRealtimePolling() {
        if (pollJob?.isActive == true) return
        pollJob = lifecycleScope.launch {
            while (true) {
                loadRealtime()
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    private fun stopRealtimePolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun loadRealtime() {
        val p = pile ?: return
        if (p.terminalCode.isEmpty()) return
        try {
            // 实时数据接口要用桩所在机房（IDCSG），没有就现查一次路由
            var idc = api.lastRouting?.idcSg ?: ""
            if (idc.isEmpty() && p.pileCode.isNotEmpty()) {
                idc = withContext(Dispatchers.IO) { api.getRouting(p.pileCode) }.idcSg
            }
            val rt = withContext(Dispatchers.IO) { api.getRealtime(p.terminalCode, idc) }
            // 订单信息（已充时长/费用/订单号）：AACS-ChargeInfo 拿 billId，再用 ChargeBill-GetChargeBillV2 拿明细
            val session = withContext(Dispatchers.IO) {
                try {
                    val list = api.getChargeSessions()
                    val head = list.firstOrNull { it.terminalCode == p.terminalCode } ?: list.firstOrNull()
                    if (head == null) null
                    else {
                        val idc = head.idcSg.ifEmpty { idc }
                        api.getChargeBill(head.chargeId, idc) ?: head
                    }
                } catch (e: Exception) {
                    null
                }
            }
            runOnUiThread {
                val kwh = rt?.totalKwh ?: 0.0
                findViewById<TextView>(R.id.tvPower).text =
                    rt?.let { String.format("%.2f", it.powerKw) } ?: "--"
                findViewById<TextView>(R.id.tvVoltage).text =
                    rt?.let { String.format("%.1f", it.voltage) } ?: "--"
                findViewById<TextView>(R.id.tvAmpere).text =
                    rt?.let { String.format("%.2f", it.ampere) } ?: "--"
                findViewById<TextView>(R.id.tvKwh).text = String.format("%.3f",
                    if (kwh > 0) kwh else (session?.chargeElectric?.toDoubleOrNull() ?: 0.0))
                val dur = session?.durationText() ?: ""
                findViewById<TextView>(R.id.tvDuration).text = dur.ifEmpty { "--" }
                val soc = when {
                    (rt?.soc ?: 0) > 0 -> "${rt!!.soc}%"
                    !session?.soc.isNullOrEmpty() && session!!.soc != "0" -> "${session.soc}%"
                    else -> "--"
                }
                findViewById<TextView>(R.id.tvSoc).text = soc

                val sb = StringBuilder()
                if (session != null) {
                    if (session.chargeCode.isNotEmpty() || session.chargeId.isNotEmpty())
                        sb.append("订单 ").append(session.chargeCode.ifEmpty { session.chargeId }).append("\n")
                    if (session.startTime.isNotEmpty())
                        sb.append("开始 ").append(session.startTimeText()).append("   ")
                    val money = (session.electricMoney.toDoubleOrNull() ?: 0.0) +
                            (session.serviceMoney.toDoubleOrNull() ?: 0.0)
                    if (money > 0) sb.append("费用 ").append(String.format("%.2f", money)).append(" 元")
                }
                // 电流为 0 时给出人话解释（车已充满 / 车辆未请求充电 → 桩显示“暂停中”）
                if ((rt?.ampere ?: 0.0) <= 0.01) {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append("电流 0：车已充满或车辆未请求充电，桩处于「暂停中」")
                }
                findViewById<TextView>(R.id.tvSession).text = sb.toString().trim()
                findViewById<TextView>(R.id.tvRealtimeTip).text =
                    (if (soc == "--") "电量由车辆上报，本车未上报 · " else "") +
                            "每 5 秒自动刷新 · " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                .format(java.util.Date())
            }
        } catch (e: Exception) {
            api.log("## 实时数据异常: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun doStart(p: MyPile) {
        val btn = findViewById<Button>(R.id.btnCharge)
        btn.isEnabled = false
        btn.text = "启动中..."
        lifecycleScope.launch {
            try {
                val env = withContext(Dispatchers.IO) { api.startChargeImmediately(p.terminalCode, p.pileCode) }
                if (env.ok) {
                    charging = true
                    btn.text = getString(R.string.btn_stop)
                    findViewById<TextView>(R.id.tvStatus).text = "充电中"
                    Toast.makeText(this@MainActivity, "已下发启动充电指令", Toast.LENGTH_SHORT).show()
                    // 桩状态刷新有延迟，8 秒后再拉一次权威状态
                    kotlinx.coroutines.delay(8000)
                    refresh()
                } else {
                    val hint = when (env.errcode) {
                        "BOSS-1197" -> "该桩还有未结束的充电业务（状态可能显示“暂停中”），请先点“停止充电”再启动"
                        "BOSS-2003" -> "服务端异常(BOSS-2003)，请稍后重试"
                        else -> env.errmsg ?: "(空)"
                    }
                    Toast.makeText(this@MainActivity, "启动失败：$hint（${env.errcode}）", Toast.LENGTH_LONG).show()
                    btn.text = getString(R.string.btn_start)
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: getString(R.string.err_unknown), Toast.LENGTH_SHORT).show()
                btn.text = getString(R.string.btn_start)
            } finally {
                btn.isEnabled = true
            }
        }
    }

    private fun doStop(p: MyPile) {
        val btn = findViewById<Button>(R.id.btnCharge)
        btn.isEnabled = false
        btn.text = "停止中..."
        lifecycleScope.launch {
            try {
                val env = withContext(Dispatchers.IO) { api.stopCharge(p.terminalCode, p.pileCode) }
                if (env.ok) {
                    charging = false
                    btn.text = getString(R.string.btn_start)
                    findViewById<TextView>(R.id.tvStatus).text = "已插枪"
                    Toast.makeText(this@MainActivity, "已下发停止充电指令", Toast.LENGTH_SHORT).show()
                    kotlinx.coroutines.delay(8000)
                    refresh()
                } else {
                    Toast.makeText(this@MainActivity, env.errmsg ?: "停止失败(${env.errcode})", Toast.LENGTH_SHORT).show()
                    btn.text = getString(R.string.btn_stop)
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: getString(R.string.err_unknown), Toast.LENGTH_SHORT).show()
                btn.text = getString(R.string.btn_stop)
            } finally {
                btn.isEnabled = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时，如果当前有未结束业务就恢复轮询
        if (charging && pile != null) startRealtimePolling()
    }

    override fun onPause() {
        super.onPause()
        stopRealtimePolling()
    }

    private fun pill(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = 30f
            setColor(color)
        }
    }
}
