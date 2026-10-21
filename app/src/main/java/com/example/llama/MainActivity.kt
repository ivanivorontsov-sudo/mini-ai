package com.example.llama

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.onCompletion
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var statusTv: TextView
    private lateinit var progress: ProgressBar
    private lateinit var messagesRv: RecyclerView
    private lateinit var input: EditText
    private lateinit var fab: FloatingActionButton
    private lateinit var searchSw: com.google.android.material.materialswitch.MaterialSwitch
    private val webSearch by lazy { WebSearch(this) }
    private var captchaRetry: CompletableDeferred<Boolean>? = null
    private val openBrowser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { captchaRetry?.complete(true) }
    private fun searchEngine() = prefs.getString("engine", "google")!!
    private lateinit var honestSw: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var engine: InferenceEngine
    private val engineReady = CompletableDeferred<Unit>()
    private var generationJob: Job? = null
    private var currentModel: File? = null
    private var isModelReady = false
    private val messages = mutableListOf<Message>()
    private val lastAssistant = StringBuilder()
    private val adapter = MessageAdapter(messages)
    private val prefs by lazy { getSharedPreferences("mini", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusTv = findViewById(R.id.gguf)
        progress = findViewById(R.id.progress)
        messagesRv = findViewById(R.id.messages)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = adapter
        input = findViewById(R.id.user_input)
        fab = findViewById(R.id.fab)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        lifecycleScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(applicationContext); engineReady.complete(Unit)
        }
        findViewById<Button>(R.id.btn_pick).setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        findViewById<Button>(R.id.btn_download).setOnClickListener { showModels() }
        findViewById<Button>(R.id.btn_clear).setOnClickListener { clearChat() }
        fab.setOnClickListener { send() }
        searchSw = findViewById(R.id.search)
        searchSw.isChecked = prefs.getBoolean("search", false)
        searchSw.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("search", on).apply() }
        val engBtn = findViewById<Button>(R.id.btn_engine)
        fun engLabel() { engBtn.text = if (searchEngine() == "yandex") "Яндекс ▾" else "Google ▾" }
        engLabel()
        engBtn.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Поисковик")
                .setSingleChoiceItems(arrayOf("Google", "Яндекс"), if (searchEngine() == "yandex") 1 else 0) { d, i ->
                    prefs.edit().putString("engine", if (i == 1) "yandex" else "google").apply(); engLabel(); d.dismiss()
                }.show()
        }
        honestSw = findViewById(R.id.honest)
        honestSw.isChecked = prefs.getBoolean("honest", true)
        honestSw.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("honest", on).apply()
            currentModel?.let { messages.clear(); adapter.notifyDataSetChanged(); loadModel(it) }
        }

        lifecycleScope.launch { DlBus.state.collect { onDl(it) } }

        val saved = prefs.getString("model", null)?.let { File(it) }
        if (saved != null && saved.exists()) loadModel(saved) else showModels()
    }

    private fun totalRamGb(): Double {
        val mi = ActivityManager.MemoryInfo(); getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        return mi.totalMem / 1e9
    }

    private fun showModels() {
        val ram = totalRamGb()
        val dir = DownloadService.modelsDir(this)
        val items = Models.list.map { m ->
            val have = File(dir, m.fileName).exists()
            val part = File(dir, m.fileName + ".part")
            val st = when { have -> " ✅ скачана"; part.exists() -> " ⏸ скачано ${part.length() * 100 / m.size}%"; else -> "" }
            val warn = if (ram < m.ramGb - 0.5) " ⚠ мало памяти" else ""
            "${m.title}$st\n${"%.1f".format(m.size / 1e9)} ГБ · нужно ОЗУ от ${m.ramGb} ГБ$warn\n${m.note}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Выберите модель (у вас ОЗУ ≈ ${"%.1f".format(ram)} ГБ)")
            .setItems(items) { _, i -> if (!maybePause(Models.list[i])) modelAction(Models.list[i]) }
            .setNeutralButton("Файл с телефона") { _, _ -> pickFile.launch(arrayOf("*/*")) }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun modelAction(m: ModelInfo) {
        val f = File(DownloadService.modelsDir(this), m.fileName)
        if (f.exists()) {
            AlertDialog.Builder(this).setTitle(m.title)
                .setPositiveButton("Использовать") { _, _ -> loadModel(f) }
                .setNegativeButton("Удалить") { _, _ ->
                    if (currentModel == f) unload()
                    f.delete(); toast("Модель удалена")
                }.setNeutralButton("Отмена", null).show()
            return
        }
        val dir = DownloadService.modelsDir(this)
        val partLen = File(dir, m.fileName + ".part").let { if (it.exists()) it.length() else 0 }
        val need = m.size - partLen
        if (dir.usableSpace < need + 200L * 1024 * 1024) {
            AlertDialog.Builder(this).setTitle("Мало места")
                .setMessage("Нужно ${need / 1_000_000_000.0} ГБ, свободно ${"%.2f".format(dir.usableSpace / 1e9)} ГБ. Освободите место или выберите модель полегче.")
                .setPositiveButton("OK", null).show(); return
        }
        DownloadService.start(this, m)
    }

    private fun onDl(s: DlState) {
        when (s) {
            is DlState.Idle -> progress.visibility = View.GONE
            is DlState.Running -> {
                progress.visibility = View.VISIBLE; progress.isIndeterminate = false
                progress.progress = (s.done * 1000 / s.total).toInt()
                statusTv.text = "Скачивание ${s.model.title}: ${s.done * 100 / s.total}% (${s.done / 1_000_000} из ${s.total / 1_000_000} МБ). Нажмите «Скачать» → модель, чтобы поставить на паузу."
            }
            is DlState.Verifying -> { progress.isIndeterminate = true; statusTv.text = "Проверка SHA256…" }
            is DlState.Paused -> { progress.visibility = View.GONE; statusTv.text = "Пауза: ${s.model.title}, скачано ${s.done / 1_000_000} МБ. Нажмите «Скачать», чтобы продолжить." }
            is DlState.Failed -> { progress.visibility = View.GONE; statusTv.text = "Ошибка загрузки: ${s.error}" }
            is DlState.Done -> {
                progress.visibility = View.GONE; DlBus.state.value = DlState.Idle
                toast("Модель скачана и проверена"); loadModel(File(DownloadService.modelsDir(this), s.model.fileName))
            }
        }
    }

    // when a download is running, tapping the model again pauses it
    private fun maybePause(m: ModelInfo): Boolean {
        val s = DlBus.state.value
        if (s is DlState.Running && s.model == m) { DownloadService.pause(this); return true }
        return false
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val name = (uri.lastPathSegment ?: "model").substringAfterLast('/').substringAfterLast(':')
                .let { if (it.endsWith(".gguf")) it else "$it.gguf" }
            val f = File(DownloadService.modelsDir(this@MainActivity), name)
            withContext(Dispatchers.Main) { statusTv.text = "Копирую файл в приложение…"; progress.visibility = View.VISIBLE; progress.isIndeterminate = true }
            try {
                contentResolver.openInputStream(uri)!!.use { i -> FileOutputStream(f).use { i.copyTo(it, 1 shl 20) } }
                val magic = f.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
                if (String(magic) != "GGUF") { f.delete(); throw Exception("это не файл GGUF") }
                withContext(Dispatchers.Main) { progress.visibility = View.GONE; loadModel(f) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { progress.visibility = View.GONE; statusTv.text = "Не удалось: ${e.message}" }
            }
        }
    }

    private fun setInput(on: Boolean) { input.isEnabled = on; fab.isEnabled = on }

    private fun unload() {
        if (isModelReady) runCatching { engine.cleanUp() }
        isModelReady = false; currentModel = null; setInput(false)
        input.hint = "Сначала загрузите модель"; statusTv.text = "Модель не загружена."
    }

    private fun loadModel(f: File) {
        setInput(false); input.hint = "Загрузка модели…"
        statusTv.text = "Загружаю ${f.name}…"
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                engineReady.await()
                if (isModelReady) engine.cleanUp()
                isModelReady = false
                val honest = prefs.getBoolean("honest", true)
                if (honest) engine.setSamplingParams(0.3f, 0.9f, 1.1f) else engine.setSamplingParams(0.7f, 0.95f, 1.0f)
                engine.loadModel(f.path)
                engine.setSystemPrompt(if (honest) HONEST_PROMPT else SYSTEM_PROMPT)
                isModelReady = true; currentModel = f
                prefs.edit().putString("model", f.path).apply()
                withContext(Dispatchers.Main) {
                    statusTv.text = "Модель: ${f.name}"; input.hint = "Напишите сообщение"; setInput(true)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "load", e)
                runCatching { engine.cleanUp() }
                withContext(Dispatchers.Main) { statusTv.text = "Не удалось загрузить модель: ${e.message}"; input.hint = "Выберите другую модель" }
            }
        }
    }

    private fun clearChat() {
        generationJob?.cancel()
        messages.clear(); adapter.notifyDataSetChanged()
        currentModel?.let { loadModel(it) } // reload resets the model's context
    }

    private fun setAssistant(text: String) {
        val i = messages.size - 1
        messages[i] = messages[i].copy(content = text)
        adapter.notifyItemChanged(i); messagesRv.scrollToPosition(i)
    }

    /** Asks the user to solve a captcha in the mini-browser. true = solved/closed, retry. */
    private suspend fun askCaptcha(url: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        captchaRetry = d
        AlertDialog.Builder(this).setTitle("Поиск")
            .setMessage("Поисковик просит подтвердить, что вы не робот")
            .setPositiveButton("Открыть") { _, _ -> openBrowser.launch(android.content.Intent(this, BrowserActivity::class.java).putExtra("url", url)) }
            .setNegativeButton("Без поиска") { _, _ -> d.complete(false) }
            .setOnCancelListener { d.complete(false) }.show()
        return d.await()
    }

    private suspend fun doSearch(q: String): Pair<List<SearchHit>?, String?> {
        var retried = false
        while (true) {
            try {
                return webSearch.search(q, searchEngine()) to null
            } catch (e: SearchFailed) {
                if (e.captchaUrl != null && !retried) {
                    setAssistant("Поисковик просит подтвердить, что вы не робот…")
                    if (askCaptcha(e.captchaUrl)) { retried = true; setAssistant("Повторяю поиск…"); continue }
                }
                return null to (e.message ?: "ошибка")
            } catch (e: Exception) {
                return null to (e.message ?: e.toString())
            }
        }
    }

    private fun send() {
        val msg = input.text.toString().trim()
        if (msg.isEmpty()) { toast("Введите сообщение"); return }
        input.text = null; setInput(false)
        messages.add(Message(UUID.randomUUID().toString(), msg, true))
        lastAssistant.clear()
        messages.add(Message(UUID.randomUUID().toString(), "…", false))
        adapter.notifyItemRangeInserted(messages.size - 2, 2); messagesRv.scrollToPosition(messages.size - 1)
        generationJob = lifecycleScope.launch {
            var prompt = msg
            var hits: List<SearchHit>? = null
            var prefix = ""
            if (searchSw.isChecked) {
                setAssistant("Ищу в интернете (${if (searchEngine() == "yandex") "Яндекс" else "Google"})…")
                val (h, err) = doSearch(msg)
                hits = h
                if (h == null) prefix = "⚠ Поиск не удался: $err. Отвечаю без интернета.\n\n"
                else {
                    val ctx = h.mapIndexed { i, r ->
                        "[${i + 1}] ${r.title}\n${r.url}\n${r.snippet.take(300)}" + (if (r.text.isNotBlank()) "\nТекст страницы: ${r.text}" else "")
                    }.joinToString("\n\n")
                    prompt = "Результаты поиска в интернете:\n$ctx\n\nОтветь на вопрос, опираясь на эти результаты. Ссылайся на источники номерами [1], [2]. Если в результатах нет ответа — так и скажи.\n\nВопрос: $msg"
                }
                setAssistant(prefix + "…")
            }
            lastAssistant.append(prefix)
            try {
                withContext(Dispatchers.Default) {
                    engine.sendUserPrompt(prompt, 1024).collect { t ->
                        withContext(Dispatchers.Main) { setAssistant(lastAssistant.append(t).toString()) }
                    }
                }
                hits?.let { h ->
                    lastAssistant.append("\n\nИсточники:\n" + h.mapIndexed { i, r -> "[${i + 1}] ${r.title}\n${r.url}" }.joinToString("\n"))
                    setAssistant(lastAssistant.toString())
                }
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                toast("Ошибка: ${e.message}")
            } finally { setInput(true) }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onStop() { generationJob?.cancel(); super.onStop() }
    override fun onDestroy() { if (::engine.isInitialized) engine.destroy(); super.onDestroy() }

    companion object {
        private const val TAG = "MiniAI"
        const val HONEST_PROMPT = """Ты — честный и точный помощник. Всегда отвечай по-русски.
Правила:
1. Говори только то, в чём уверен. Если не знаешь или не уверен — прямо скажи «Не знаю» или «Не уверен», не выдумывай.
2. Никогда не придумывай факты, цифры, даты, цитаты, имена, книги, ссылки и адреса сайтов.
3. Если в вопросе есть ложная предпосылка или ошибка — сначала вежливо укажи на неё и поправь, а не отвечай так, будто это правда.
4. Не соглашайся с пользователем только чтобы ему угодить. Если он неправ — скажи об этом и объясни почему. Не меняй верный ответ из-за того, что пользователь возразил без доводов.
5. Если о чём-то ты не слышал (например, о вымышленном человеке, событии или законе) — так и скажи.
6. Отвечай коротко и по делу. Перед ответом молча проверь себя: нет ли в ответе выдумок."""
        const val SYSTEM_PROMPT = "Ты — полезный помощник. Всегда отвечай по-русски, понятно и по делу."
    }
}
