package com.galeriaia.app.indexing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.galeriaia.app.MainActivity
import com.galeriaia.app.data.EmbeddingStore
import com.galeriaia.app.data.MediaRepository
import com.galeriaia.app.data.Prefs
import com.galeriaia.app.data.VideoRepository
import com.galeriaia.app.ml.ClipModel
import com.galeriaia.app.ml.ModelFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Indexa as fotos em segundo plano (serviço com notificação): dá para usar o celular
 * ou apagar a tela enquanto roda. Pausa sozinho se a bateria esquentar demais.
 */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val UNIQUE_MANUAL = "index-manual"
        const val UNIQUE_AUTO = "index-auto"
        const val TAG = "index"
        const val TAG_MANUAL = "index-manual-tag"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_STARTED = "started"
        const val KEY_COOLING = "cooling"
        const val KEY_TEMP = "temp"
        const val KEY_PRIORITY = "priority"
        const val KEY_PHASE = "phase"
        const val PHASE_VIDEOS = "videos"
        private const val IN_FROM = "from"
        private const val IN_TO = "to"
        private const val IN_MONTH = "month"
        private const val IN_LABEL = "label"

        private const val CHANNEL = "indexacao"
        private const val NOTIF_ID = 1001
        private const val THREADS_NORMAL = 3 // menos núcleos = esquenta menos
        private const val THREADS_FAST = 6
        private const val HOT_C = 41f
        private const val COOL_C = 37f

        /** Impede duas indexações ao mesmo tempo (manual + automática). */
        private val lock = Mutex()

        fun startNow(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                UNIQUE_MANUAL, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<IndexWorker>().addTag(TAG).addTag(TAG_MANUAL).build()
            )
        }

        /**
         * Analisa primeiro as fotos de um período (ex.: o que a pessoa acabou de buscar) e depois o resto.
         * [fromMs]..[toMs] em milissegundos, ou [month] (1-12) para "esse mês em qualquer ano".
         */
        fun startPriority(ctx: Context, fromMs: Long, toMs: Long, month: Int, label: String) {
            val data = workDataOf(IN_FROM to fromMs, IN_TO to toMs, IN_MONTH to month, IN_LABEL to label)
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                UNIQUE_MANUAL, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<IndexWorker>().setInputData(data).addTag(TAG).addTag(TAG_MANUAL).build()
            )
        }

        /** Reinicia a indexação em andamento (ex.: ao trocar o modo rápido). Continua de onde parou. */
        fun restart(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                UNIQUE_MANUAL, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<IndexWorker>().addTag(TAG).addTag(TAG_MANUAL).build()
            )
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_MANUAL)
        }

        /** Indexa fotos novas sozinho, 1x a cada ~12 h, só com o celular carregando. */
        fun scheduleAuto(ctx: Context) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE_AUTO, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<IndexWorker>(12, TimeUnit.HOURS).setConstraints(constraints).addTag(TAG).build()
            )
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Preparando…", 0, 0)

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!ModelFiles.allPresent(ctx.filesDir)) return Result.failure()
        // Se outra indexação estiver terminando, espera ela liberar
        return lock.withLock { indexAll(ctx) }
    }

    private suspend fun indexAll(ctx: Context): Result {
        val media = MediaRepository(ctx)
        val store = EmbeddingStore(ctx)

        val images = withContext(Dispatchers.IO) { media.queryImages() }
        val indexed = withContext(Dispatchers.IO) { store.allIds() }
        val current = images.mapTo(HashSet()) { it.id }
        withContext(Dispatchers.IO) { store.delete(indexed.filter { it !in current }) }

        var todo = images.filter { it.id !in indexed }
        if (todo.isEmpty()) return indexVideos(ctx)

        // Período prioritário: essas fotos vão para o começo da fila
        val priorityLabel = inputData.getString(IN_LABEL)
        var priorityCount = 0
        if (priorityLabel != null) {
            val from = inputData.getLong(IN_FROM, Long.MIN_VALUE)
            val to = inputData.getLong(IN_TO, Long.MAX_VALUE)
            val month = inputData.getInt(IN_MONTH, 0)
            val zone = java.time.ZoneId.systemDefault()
            val inRange = { ms: Long ->
                if (month > 0) java.time.Instant.ofEpochMilli(ms).atZone(zone).monthValue == month
                else ms in from..to
            }
            val (first, rest) = todo.partition { inRange(it.dateTaken) }
            priorityCount = first.size
            todo = first + rest
        }

        runCatching { setForeground(foregroundInfo(if (Prefs.fastMode(ctx)) "Analisando (modo rápido)…" else "Analisando fotos…", 0, todo.size)) }
        val started = System.currentTimeMillis()
        var done = 0
        val fast = Prefs.fastMode(ctx)

        ClipModel(ModelFiles.dir(ctx.filesDir), if (fast) THREADS_FAST else THREADS_NORMAL).use { clip ->
            for (chunk in todo.chunked(16)) {
                if (isStopped) break
                if (!fast) coolDownIfHot(ctx, done, todo.size, started)

                val entries = coroutineScope {
                    val prepared = chunk.map { img ->
                        async(Dispatchers.IO) {
                            runCatching {
                                val bmp = media.loadThumbnail(img.uri)
                                try { clip.preprocess(bmp) } finally { bmp.recycle() }
                            }.getOrNull()
                        }
                    }.awaitAll()
                    chunk.mapIndexed { i, img ->
                        // Foto que não abre: vetor zerado para não tentar de novo
                        val vec = prepared[i]?.let { runCatching { clip.embedImage(it) }.getOrNull() }
                            ?: FloatArray(ClipModel.EMBED_DIM)
                        EmbeddingStore.Entry(img.id, img.dateTaken, vec)
                    }
                }
                withContext(Dispatchers.IO) { store.insert(entries) }
                done += chunk.size
                setProgress(
                    if (priorityLabel != null && done < priorityCount)
                        workDataOf(KEY_DONE to done, KEY_TOTAL to todo.size, KEY_STARTED to started, KEY_PRIORITY to priorityLabel)
                    else workDataOf(KEY_DONE to done, KEY_TOTAL to todo.size, KEY_STARTED to started)
                )
                if (done % 160 == 0 || done == todo.size) {
                    runCatching { setForeground(foregroundInfo("Analisando fotos…", done, todo.size)) }
                }
            }
        }
        if (isStopped) return Result.success()
        return indexVideos(ctx)
    }

    /** Segunda fase: vídeos (3 quadros por vídeo, vetor = média dos quadros). */
    private suspend fun indexVideos(ctx: Context): Result {
        val videos = VideoRepository(ctx)
        val store = EmbeddingStore.videos(ctx)
        val all = withContext(Dispatchers.IO) { runCatching { videos.queryVideos() }.getOrDefault(emptyList()) }
        val indexed = withContext(Dispatchers.IO) { store.allIds() }
        val current = all.mapTo(HashSet()) { it.id }
        withContext(Dispatchers.IO) { store.delete(indexed.filter { it !in current }) }
        val todo = all.filter { it.id !in indexed }
        if (todo.isEmpty()) return Result.success()

        runCatching { setForeground(foregroundInfo("Analisando vídeos…", 0, todo.size)) }
        val started = System.currentTimeMillis()
        var done = 0
        val fast = Prefs.fastMode(ctx)
        ClipModel(ModelFiles.dir(ctx.filesDir), if (fast) THREADS_FAST else THREADS_NORMAL).use { clip ->
            for (chunk in todo.chunked(4)) {
                if (isStopped) break
                if (!fast) coolDownIfHot(ctx, done, todo.size, started)
                val entries = coroutineScope {
                    val frames = chunk.map { v ->
                        async(Dispatchers.IO) {
                            runCatching {
                                videos.frames(v.uri, v.durationMs).map { bmp ->
                                    try { clip.preprocess(bmp) } finally { bmp.recycle() }
                                }
                            }.getOrDefault(emptyList())
                        }
                    }.awaitAll()
                    chunk.mapIndexed { i, v ->
                        val vecs = frames[i].mapNotNull { runCatching { clip.embedImage(it) }.getOrNull() }
                        val avg = FloatArray(ClipModel.EMBED_DIM)
                        for (vec in vecs) for (k in avg.indices) avg[k] += vec[k]
                        var n = 0f
                        for (x in avg) n += x * x
                        n = kotlin.math.sqrt(n)
                        if (n > 0f) for (k in avg.indices) avg[k] /= n
                        EmbeddingStore.Entry(v.id, v.dateTaken, avg)
                    }
                }
                withContext(Dispatchers.IO) { store.insert(entries) }
                done += chunk.size
                setProgress(
                    workDataOf(KEY_DONE to done, KEY_TOTAL to todo.size, KEY_STARTED to started, KEY_PHASE to PHASE_VIDEOS)
                )
                if (done % 40 == 0 || done == todo.size) {
                    runCatching { setForeground(foregroundInfo("Analisando vídeos…", done, todo.size)) }
                }
            }
        }
        return Result.success()
    }

    private fun batteryTemp(ctx: Context): Float {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
    }

    private suspend fun coolDownIfHot(ctx: Context, done: Int, total: Int, started: Long) {
        var temp = batteryTemp(ctx)
        if (temp < HOT_C) return
        while (temp > COOL_C && !isStopped) {
            setProgress(
                workDataOf(
                    KEY_DONE to done, KEY_TOTAL to total, KEY_STARTED to started,
                    KEY_COOLING to true, KEY_TEMP to temp,
                )
            )
            runCatching { setForeground(foregroundInfo("Pausado para esfriar (%.0f °C)".format(temp), done, total)) }
            delay(30_000)
            temp = batteryTemp(ctx)
        }
    }

    private fun foregroundInfo(text: String, done: Int, total: Int): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Indexação de fotos", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Galeria IA")
            .setContentText(if (total > 0) "$text $done/$total" else text)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
        return ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
