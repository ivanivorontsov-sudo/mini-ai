package com.example.llama

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONTokener
import kotlin.coroutines.resume

data class SearchHit(val title: String, val snippet: String, val url: String, var text: String = "")

class SearchFailed(msg: String, val captchaUrl: String? = null) : Exception(msg)

/** Hidden WebView search: Google or Yandex results page, then 1-2 pages read Readability-style. */
class WebSearch(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())

    suspend fun search(query: String, engine: String, openPages: Int = 2): List<SearchHit> {
        val q = Uri.encode(query)
        val url = if (engine == "yandex") "https://yandex.ru/search/?text=$q&lr=213" else "https://www.google.com/search?q=$q&hl=ru"
        val (finalUrl, json) = load(url, if (engine == "yandex") YANDEX_JS else GOOGLE_JS, 20000)
            ?: throw SearchFailed("нет ответа от поисковика (нет сети?)")
        if (finalUrl.contains("/sorry/") || finalUrl.contains("showcaptcha") || json.contains("\"captcha\""))
            throw SearchFailed("Поисковик просит подтвердить, что вы не робот", finalUrl)
        val arr = JSONTokener(json).nextValue().let { if (it is String) JSONArray(it) else it as JSONArray }
        val hits = (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { SearchHit(it.optString("t"), it.optString("s"), it.optString("u")) }
            .filter { it.url.startsWith("http") && it.title.isNotBlank() }
            .distinctBy { it.url }.take(5)
        if (hits.isEmpty()) throw SearchFailed("не удалось разобрать результаты")
        for (h in hits.take(openPages)) {
            val r = load(h.url, READ_JS, 12000) ?: continue
            h.text = runCatching { JSONTokener(r.second).nextValue() as String }.getOrDefault("").take(1500)
        }
        return hits
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(url: String, js: String, timeoutMs: Long): Pair<String, String>? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                main.post {
                    val wv = WebView(ctx)
                    wv.settings.javaScriptEnabled = true
                    wv.settings.blockNetworkImage = true
                    wv.settings.userAgentString = UA
                    wv.settings.domStorageEnabled = true
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    var done = false
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, u: String) {
                            if (done) return
                            main.postDelayed({
                                if (done) return@postDelayed
                                view.evaluateJavascript(js) { res ->
                                    if (done) return@evaluateJavascript
                                    done = true
                                    val fin = view.url ?: u
                                    view.destroy()
                                    if (cont.isActive) cont.resume(fin to (res ?: "[]"))
                                }
                            }, 1200)
                        }
                    }
                    cont.invokeOnCancellation { main.post { if (!done) { done = true; wv.destroy() } } }
                    wv.loadUrl(url)
                }
            }
        }

    companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
        // returns JSON string of [{t,s,u}]
        private const val GOOGLE_JS = """(function(){var o=[],seen={};if(document.querySelector('form#captcha-form,#recaptcha,iframe[src*=recaptcha]'))return '[{"captcha":1}]';document.querySelectorAll('a[href]').forEach(function(a){var hr=a.getAttribute('href')||'';var u=a.href;var h=a.querySelector('h3,[role=heading]');var lines=a.innerText.split('\n').map(function(x){return x.trim()}).filter(function(x){return x});if(hr.indexOf('/url?')==0){try{u=new URL(a.href).searchParams.get('q')||u}catch(e){}}else if(hr.indexOf('/goto?')==0){var ul=lines.filter(function(x){return /^https?:\/\//.test(x)})[0];if(!ul)return;if(ul.indexOf('›')<0&&ul.indexOf('...')<0&&ul.replace(/\/+$/,'').split('/').length>3)u=ul}else if(!/^https?:/.test(hr)||/google\./.test(a.hostname))return;if(!h&&lines.length<2)return;var t=h?h.innerText:lines[lines.length-1];if(!t||t.length<5||seen[u])return;seen[u]=1;var b=a.closest('[data-hveid]')||a.parentElement.parentElement;var s=b?b.innerText.replace(a.innerText,'').trim().slice(0,300):'';o.push({t:t,s:s,u:u})});return JSON.stringify(o)})()"""
        private const val YANDEX_JS = """(function(){var o=[];document.querySelectorAll('li.serp-item, .serp-item').forEach(function(li){var a=li.querySelector('a.OrganicTitle-Link, h2 a, a.Link');if(!a)return;var t=(li.querySelector('h2')||a).innerText;var s=(li.querySelector('.OrganicTextContentSpan, .TextContainer, .Organic-ContentWrapper')||li).innerText.slice(0,300);o.push({t:t,s:s,u:a.href})});if(!o.length&&(location.href.indexOf('captcha')>=0||document.querySelector('.CheckboxCaptcha,.AdvancedCaptcha')))return '[{"captcha":1}]';return JSON.stringify(o)})()"""
        private const val READ_JS = """(function(){var r=document.querySelector('article')||document.querySelector('main')||document.querySelector('[role=main]')||document.body;if(!r)return '';var c=r.cloneNode(true);c.querySelectorAll('script,style,nav,header,footer,aside,form,noscript,iframe').forEach(function(e){e.remove()});var ps=[].slice.call(c.querySelectorAll('p,li,h1,h2,h3')).map(function(p){return p.innerText.trim()}).filter(function(t){return t.length>40});var t=ps.length?ps.join('\n'):c.innerText;return t.replace(/\s+\n/g,'\n').slice(0,3000)})()"""
    }
}
