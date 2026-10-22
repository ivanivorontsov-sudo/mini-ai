package com.example.llama

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ivan GPT: Ivan's own tiny model (≈10M params, Llama-like, SentencePiece BPE 8000, context 256).
 * Always takes the LATEST release of the private repo via the GitHub API with the user's own token.
 * Chat format is exactly the one it was trained on (ivan-gpt/prep_chat.py, chat_eval_lib.py):
 *   "Человек: …\nИИ: …" lines, prompt ends with "\nИИ:", reply stops at EOS or the first newline,
 *   only the last ~200 tokens of history are kept.
 */
object IvanGpt {
    const val ID = "ivan_gpt"
    const val TITLE = "Ivan GPT (модель Ивана)"
    const val NOTE = "Маленькая модель Ивана, обучена с нуля, пишет в его стиле, факты путает. ≈10 млн параметров, ~20 МБ, работает на любом телефоне. Нужен ваш GitHub-токен (репозиторий приватный)."
    /** Where to look for the .gguf, in order. Both repos are private; nothing is mirrored publicly. */
    val REPOS = listOf("ivanivorontsov-sudo/ivan-gpt", "ivanivorontsov-sudo/mini-ai")

    class NeedToken(msg: String) : Exception(msg)

    private fun api(path: String, token: String): JSONObject {
        val c = URL("https://api.github.com/$path").openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 15000; c.readTimeout = 30000
        val code = c.responseCode
        if (code == 401) throw NeedToken("GitHub не принял токен (401). Проверьте его в настройках.")
        if (code == 404) return JSONObject()
        if (code != 200) throw Exception("GitHub ответил $code")
        return JSONObject(c.inputStream.bufferedReader().readText())
    }

    /** Finds the newest release that has ivan-gpt*.gguf (or a zip with it). Network call: run off the main thread. */
    fun resolveLatest(c: Context): ModelInfo {
        val token = SecureStore.githubToken(c) ?: throw NeedToken("Введите GitHub-токен с доступом к репозиторию ivan-gpt (Настройки → GitHub-токен).")
        for (repo in REPOS) {
            val rel = api("repos/$repo/releases/latest", token)
            val assets = rel.optJSONArray("assets") ?: continue
            var best: JSONObject? = null; var zipped = false
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i); val n = a.getString("name").lowercase()
                val isIvan = repo.endsWith("ivan-gpt") || n.startsWith("ivan-gpt")
                if (isIvan && n.endsWith(".gguf")) { best = a; zipped = false; break }
                if (isIvan && n.endsWith(".zip") && n.contains("gguf") && best == null) { best = a; zipped = true }
            }
            val a = best ?: continue
            val tag = rel.optString("tag_name")
            val sha = a.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 } ?: ""
            return ModelInfo(ID, TITLE, a.getString("url"), "ivan-gpt.gguf", a.getLong("size"), sha, 1, NOTE,
                githubAuth = true, version = if (repo.endsWith("ivan-gpt")) tag else "$tag (копия в mini-ai)", zipped = zipped)
        }
        throw Exception("В последнем релизе ivan-gpt нет файла .gguf. Можно загрузить ivan-gpt.gguf кнопкой «Файл с телефона».")
    }

    fun installedVersion(c: Context) = c.getSharedPreferences("mini", Context.MODE_PRIVATE).getString("ivan_ver", null)
    fun setInstalledVersion(c: Context, v: String) = c.getSharedPreferences("mini", Context.MODE_PRIVATE).edit().putString("ivan_ver", v).apply()

    // --- chat format ---
    fun buildPrompt(history: List<String>, maxChars: Int = 700): String {
        val sb = StringBuilder()
        for (line in history.asReversed()) { if (sb.length + line.length > maxChars && sb.isNotEmpty()) break; sb.insert(0, line + "\n") }
        return sb.toString().trimEnd('\n') + "\nИИ:"
    }

    // --- port of ivan-gpt/safety.py ---
    private val RX = Regex("\\b(" + listOf("вирус\\w*", "троян\\w*", "кейлог+ер\\w*", "шифровальщик\\w*", "вымогател\\w*", "эксплойт\\w*", "эксплоит\\w*", "руткит\\w*",
        "бэкдор\\w*", "ботнет\\w*", "червь", "червя", "малвар\\w*", "вредонос\\w*", "стил+ер\\w*", "ddos", "ддос\\w*",
        "virus\\w*", "trojan\\w*", "keylog+er\\w*", "ransomware", "exploit\\w*", "rootkit\\w*", "backdoor\\w*", "botnet\\w*",
        "malware", "worm", "stealer", "payload", "shellcode", "reverse\\s*shell", "обратн\\w*\\s+шелл\\w*", "0day|zero[- ]day",
        "(взлома|взломать|взлом)\\w*\\s+(пароль|аккаунт|сайт|wi-?fi|вайфай|телефон)\\w*", "sql[- ]?инъекц\\w*|sql[- ]?injection").joinToString("|") + ")\\b",
        setOf(RegexOption.IGNORE_CASE))
    const val REFUSAL = "Не помогаю с вредоносными программами (вирусы, трояны, эксплойты, кейлоггеры, шифровальщики)."
    fun blocked(t: String?) = RX.containsMatchIn(t ?: "")
}
