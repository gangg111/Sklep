package com.gangg111.sklep

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val CH_DOWNLOAD = "pobieranie"
private const val CH_UPDATES = "aktualizacje"
private const val NOTIF_DOWNLOAD = 1

private fun channels(ctx: Context) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CH_DOWNLOAD, "Pobieranie", NotificationManager.IMPORTANCE_LOW))
    nm.createNotificationChannel(NotificationChannel(CH_UPDATES, "Dostępne aktualizacje", NotificationManager.IMPORTANCE_DEFAULT))
}

private fun openApp(ctx: Context, pkg: String?, code: Int) = PendingIntent.getActivity(
    ctx, code, Intent(ctx, MainActivity::class.java).putExtra("open", pkg).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

/** Trzyma proces przy życiu, dopóki coś się pobiera, i pokazuje postęp w powiadomieniu. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, DownloadService::class.java))
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channels(this)
        startForeground(NOTIF_DOWNLOAD, build("Pobieranie…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        scope.launch {
            snapshotFlow { Store.jobs.toMap() }.collect { jobs ->
                val active = jobs.filterValues { it is Task.Downloading || it is Task.Verifying }
                if (active.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                val names = active.keys.mapNotNull { p -> Store.apps.value.firstOrNull { it.pkg == p }?.name }
                val done = active.values.sumOf { (it as? Task.Downloading)?.done ?: 0 }
                val total = active.values.sumOf { (it as? Task.Downloading)?.total ?: 0 }
                val title = if (active.values.all { it is Task.Verifying }) "Sprawdzanie ${names.joinToString()}"
                else "Pobieranie ${names.joinToString()}"
                getSystemService(NotificationManager::class.java).notify(NOTIF_DOWNLOAD, build(title, done, total))
            }
        }
        return START_NOT_STICKY
    }

    private fun build(title: String, done: Long, total: Long): Notification =
        Notification.Builder(this, CH_DOWNLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .apply {
                if (total > 0) {
                    setContentText("${done / 1_000_000} z ${total / 1_000_000} MB")
                    setProgress(1000, (done * 1000 / total).toInt(), false)
                } else setProgress(0, 0, true)
            }
            .setOngoing(true)
            .setContentIntent(openApp(this, null, 0))
            .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Raz na kilka godzin (gdy jest sieć) sprawdza wydania i powiadamia o nowych wersjach zainstalowanych aplikacji. */
class UpdateCheckJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val ID = 1

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (!Store.prefs(ctx).getBoolean("notify", true)) {
                js.cancel(ID)
                return
            }
            if (js.getPendingJob(ID) != null) return
            // Powiadomienia to dodatek: błąd planowania nie może blokować startu apki.
            runCatching { js.schedule(
                JobInfo.Builder(ID, ComponentName(ctx, UpdateCheckJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(6 * 60 * 60 * 1000L)
                    .setPersisted(true)
                    .build(),
            ) }.onFailure { Store.message.value = "Nie udało się włączyć sprawdzania w tle: ${it.message}" }
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            runCatching { check(this@UpdateCheckJob) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope.cancel()
        return true
    }

    private suspend fun check(ctx: Context) {
        Store.load(ctx)
        channels(ctx)
        val prefs = Store.prefs(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        for (a in Store.apps.value) {
            val rel = Store.releases[a.pkg] ?: continue
            val inst = Store.installedVersion(ctx, a.pkg) ?: continue
            // Każdą wersję zgłaszamy tylko raz.
            if (!Store.isNewer(rel, inst) || prefs.getString("notified_${a.pkg}", null) == rel.tag) continue
            nm.notify(
                a.pkg.hashCode(),
                Notification.Builder(ctx, CH_UPDATES)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Dostępna aktualizacja: ${a.name}")
                    .setContentText("Wersja ${rel.tag} · ${rel.size / 1_000_000} MB")
                    .setContentIntent(openApp(ctx, a.pkg, a.pkg.hashCode()))
                    .setAutoCancel(true)
                    .build(),
            )
            prefs.edit().putString("notified_${a.pkg}", rel.tag).apply()
        }
    }
}
