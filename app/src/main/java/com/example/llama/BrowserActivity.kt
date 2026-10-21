package com.example.llama

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity

/** Visible mini-browser for solving a search captcha. Cookies are shared with the hidden WebView. */
class BrowserActivity : AppCompatActivity() {
    private lateinit var wv: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val back = Button(this).apply { text = "Назад" }
        val title = TextView(this).apply { text = "Подтвердите, что вы не робот, и нажмите «Готово»"; setPadding(16, 0, 16, 0) }
        val close = Button(this).apply { text = "Готово" }
        bar.addView(back); bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f)); bar.addView(close)
        wv = WebView(this)
        root.addView(bar); root.addView(wv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString = WebSearch.UA
        wv.webViewClient = WebViewClient()
        wv.loadUrl(intent.getStringExtra("url") ?: "about:blank")
        back.setOnClickListener { if (wv.canGoBack()) wv.goBack() else finishOk() }
        close.setOnClickListener { finishOk() }
        onBackPressedDispatcher.addCallback(this) { if (wv.canGoBack()) wv.goBack() else finishOk() }
    }

    private fun finishOk() { CookieManager.getInstance().flush(); setResult(RESULT_OK); finish() }
    override fun onDestroy() { wv.destroy(); super.onDestroy() }
}
