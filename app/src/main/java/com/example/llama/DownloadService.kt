package com.example.llama

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class DownloadService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    @Volatile private var paused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> { paused = true; return START_NOT_STICKY }
            ACTION_START -> {
                val m = Models.byId(intent.getStringExtra(EXTRA_ID)) ?: return START_NOT_STICKY
                if (job?.isActive == true) return START_NOT_STICKY
                startFg(notif(m.title, "Подготовка…", 0, 0))
                paused = false
                job = scope.launch { run(m) }
            }
        }
        return START_NOT_STICKY
    }

    private fun startFg(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NID, n)
    }

    private fun notif(title: String, text: String, p: Int, max: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "Загрузка модели", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(text).setOnlyAlertOnce(true).setOngoing(true)
            .setContentIntent(open).setProgress(max, p, max == 0 && p == 0)
            .addAction(0, "Пауза", pause).build()
    }

    private fun update(m: ModelInfo, done: Long) {
        val pct = (done * 100 / m.size).toInt()
        getSystemService(NotificationManager::class.java)
            .notify(NID, notif(m.title, "$pct% · ${mb(done)} из ${mb(m.size)} МБ", pct, 100))
    }

    private fun run(m: ModelInfo) {
        val dir = modelsDir(this)
        val part = File(dir, m.fileName + ".part")
        val target = File(dir, m.fileName)
        try {
            var done = if (part.exists()) part.length() else 0L
            if (dir.usableSpace < m.size - done + 200L * 1024 * 1024)
                throw Exception("Мало места: нужно ${mb(m.size - done)} МБ, свободно ${mb(dir.usableSpace)} МБ")
            if (done < m.size) {
                val c = URL(m.url).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 20000; c.readTimeout = 60000
                if (done > 0) c.setRequestProperty("Range", "bytes=$done-")
                val code = c.responseCode
                if (code == 200 && done > 0) done = 0 // server ignored Range
                else if (code != 200 && code != 206) throw Exception("Сервер ответил $code")
                RandomAccessFile(part, "rw").use { raf ->
                    raf.setLength(done); raf.seek(done)
                    c.inputStream.use { input ->
                        val buf = ByteArray(1 shl 16)
                        var last = 0L
                        while (true) {
                            if (paused) {
                                DlBus.state.value = DlState.Paused(m, done)
                                stopSelf(); return
                            }
                            val n = input.read(buf); if (n < 0) break
                            raf.write(buf, 0, n); done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 500) {
                                last = now
                                DlBus.state.value = DlState.Running(m, done, m.size)
                                update(m, done)
                            }
                        }
                    }
                }
                c.disconnect()
            }
            if (part.length() != m.size) throw Exception("Файл неполный (${mb(part.length())} из ${mb(m.size)} МБ), нажмите «Скачать» ещё раз")
            DlBus.state.value = DlState.Verifying(m)
            getSystemService(NotificationManager::class.java).notify(NID, notif(m.title, "Проверка SHA256…", 0, 0))
            val sha = sha256(part)
            if (!sha.equals(m.sha256, true)) { part.delete(); throw Exception("Контрольная сумма не совпала, файл удалён. Скачайте заново.") }
            target.delete(); part.renameTo(target)
            DlBus.state.value = DlState.Done(m)
        } catch (e: Exception) {
            DlBus.state.value = DlState.Failed(m, e.message ?: e.toString())
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }

    override fun onDestroy() { paused = true; scope.cancel(); super.onDestroy() }

    companion object {
        const val ACTION_START = "start"; const val ACTION_PAUSE = "pause"; const val EXTRA_ID = "id"
        private const val CH = "dl"; private const val NID = 42
        fun mb(b: Long) = b / (1024 * 1024)
        fun modelsDir(c: Context) = File(c.filesDir, "models").apply { mkdirs() }
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(f).use { s -> val b = ByteArray(1 shl 20); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        fun start(c: Context, m: ModelInfo) = c.startForegroundService(Intent(c, DownloadService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, m.id))
        fun pause(c: Context) = c.startService(Intent(c, DownloadService::class.java).setAction(ACTION_PAUSE))
    }
}
