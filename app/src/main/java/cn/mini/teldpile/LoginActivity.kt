package cn.mini.teldpile

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cn.mini.teldpile.api.ApiException
import cn.mini.teldpile.api.TeldApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var api: TeldApiClient
    private var countdown = 0
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        api = TeldApiClient(this)

        if (api.isLoggedIn()) {
            goMain()
            return
        }

        val etPhone = findViewById<EditText>(R.id.etPhone)
        val etCode = findViewById<EditText>(R.id.etCode)
        val btnSend = findViewById<Button>(R.id.btnSendCode)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val tvError = findViewById<TextView>(R.id.tvError)

        btnSend.setOnClickListener {
            val phone = etPhone.text.toString().trim()
            if (phone.length != 11) {
                tvError.text = "请输入正确的手机号"
                tvError.visibility = TextView.VISIBLE
                return@setOnClickListener
            }
            tvError.visibility = TextView.GONE
            btnSend.isEnabled = false
            lifecycleScope.launch {
                try {
                    val env = withContext(Dispatchers.IO) { api.sendSmsCode(phone) }
                    if (env.ok) {
                        Toast.makeText(this@LoginActivity, "验证码已发送", Toast.LENGTH_SHORT).show()
                        startCountdown(btnSend)
                    } else {
                        showError(tvError, env.errmsg ?: "发送失败(${env.errcode})")
                        btnSend.isEnabled = true
                    }
                } catch (e: Exception) {
                    showError(tvError, e.message ?: getString(R.string.err_unknown))
                    btnSend.isEnabled = true
                }
            }
        }

        btnLogin.setOnClickListener {
            val phone = etPhone.text.toString().trim()
            val code = etCode.text.toString().trim()
            if (phone.length != 11 || code.length !in 4..6) {
                showError(tvError, "请输入手机号和 4~6 位验证码")
                return@setOnClickListener
            }
            btnLogin.isEnabled = false
            lifecycleScope.launch {
                try {
                    val env = withContext(Dispatchers.IO) { api.loginWithSmsCode(phone, code) }
                    if (env.ok) {
                        goMain()
                    } else {
                        showError(tvError, env.errmsg ?: "登录失败(${env.errcode})")
                        btnLogin.isEnabled = true
                    }
                } catch (e: Exception) {
                    showError(tvError, e.message ?: getString(R.string.err_unknown))
                    btnLogin.isEnabled = true
                }
            }
        }
    }

    private fun startCountdown(btn: Button) {
        countdown = 60
        val runnable = object : Runnable {
            override fun run() {
                if (countdown <= 0) {
                    btn.isEnabled = true
                    btn.text = getString(R.string.login_send_code)
                    return
                }
                btn.text = getString(R.string.login_send_code_again, countdown)
                countdown--
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(runnable)
    }

    private fun showError(tv: TextView, msg: String) {
        tv.text = msg
        tv.visibility = TextView.VISIBLE
    }

    private fun goMain() {
        startActivity(android.content.Intent(this, MainActivity::class.java))
        finish()
    }
}
