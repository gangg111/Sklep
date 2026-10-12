package com.gangg111.sklep

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Katalog żyje w osobnym repo: zmiana listy aplikacji nie wymaga wydania LABstore. */
const val CATALOG_URL = "https://raw.githubusercontent.com/gangg111/LABstore-katalog/main/catalog.json"

data class AppEntry(
    val pkg: String, val name: String, val repo: String, val category: String,
    val summary: String, val description: String, val icon: String,
    val screenshots: List<String>, val apkName: String?,
    /** Grafika promocyjna jak „feature graphic” w Play (~1024×500). */
    val banner: String = "",
)

data class Release(
    val tag: String, val notes: String, val date: String, val published: Long, val prerelease: Boolean,
    val apkUrl: String, val size: Long, val sha256: String?, val versionCode: Long?,
)

sealed interface Task {
    data class Downloading(val done: Long, val total: Long) : Task
    /** Pobieranie przerwane (sieć, zamknięta apka); sesja instalatora trzyma już pobraną część. */
    data class Paused(val done: Long, val total: Long, val url: String) : Task
    data object Verifying : Task
    data object Installing : Task
}

object Store {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val apps = mutableStateOf<List<AppEntry>>(emptyList())
    val releases = mutableStateMapOf<String, Release>()
    val history = mutableStateMapOf<String, List<Release>>()
    val releaseErrors = mutableStateMapOf<String, String>()
    val jobs = mutableStateMapOf<String, Task>()
    val message = mutableStateOf<String?>(null)
    val loading = mutableStateOf(false)
    val offline = mutableStateOf(false)
    /** Zmienia się po każdym powrocie do apki, żeby stan „zainstalowana” przeliczył się na nowo. */
    val installedTick = mutableStateOf(0)
    /** Pakiet do otwarcia z powiadomienia. */
    val openRequest = mutableStateOf<String?>(null)

    private val base = CATALOG_URL.substringBeforeLast('/') + "/"
    private val running = HashMap<String, Job>()

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("sklep", Context.MODE_PRIVATE)

    fun refresh(ctx: Context) {
        if (loading.value) return
        loading.value = true
        scope.launch {
            load(ctx)
            loading.value = false
        }
    }

    /** Katalog + wydania wszystkich aplikacji; używane też przez zadanie w tle. */
    suspend fun load(ctx: Context) = withContext(Dispatchers.IO) {
        val cache = File(ctx.filesDir, "catalog.json")
        val catalog = try {
            http(CATALOG_URL).decodeToString().also {
                parseCatalog(it)  // uszkodzonego katalogu nie zapisujemy
                cache.writeText(it)
                offline.value = false
            }
        } catch (e: Exception) {
            // Brak sieci albo błąd w katalogu: ostatnia poprawnie pobrana wersja.
            offline.value = true
            if (!cache.exists()) {
                message.value = "Nie udało się pobrać katalogu: ${e.message}"
                return@withContext
            }
            cache.readText()
        }
        apps.value = parseCatalog(catalog)
        val beta = prefs(ctx).getBoolean("beta", false)
        apps.value.map { a ->
            async {
                try {
                    val all = fetchReleases(a)
                    history[a.pkg] = all
                    val latest = all.firstOrNull { beta || !it.prerelease }
                        ?: throw IOException("Brak wydania z plikiem APK")
                    releases[a.pkg] = withVersionCode(a, latest)
                    releaseErrors.remove(a.pkg)
                } catch (e: Exception) {
                    releaseErrors[a.pkg] = e.message ?: e.javaClass.simpleName
                }
            }
        }.awaitAll()
        restorePaused(ctx)
    }

    private fun parseCatalog(text: String): List<AppEntry> {
        val arr = JSONObject(text).getJSONArray("apps")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val shots = o.optJSONArray("screenshots")
            AppEntry(
                pkg = o.getString("package"), name = o.getString("name"), repo = o.getString("repo"),
                category = o.optString("category"), summary = o.optString("summary"),
                description = o.optString("description"), icon = o.optString("icon"),
                screenshots = shots?.let { s -> (0 until s.length()).map { s.getString(it) } } ?: emptyList(),
                apkName = o.optString("apk").ifEmpty { null },
                banner = o.optString("banner"),
            )
        }
    }

    /** Jedno zapytanie daje i najnowsze wydanie, i historię (limit API GitHuba: 60/h). */
    private fun fetchReleases(a: AppEntry): List<Release> {
        val arr = JSONArray(http("https://api.github.com/repos/${a.repo}/releases?per_page=20").decodeToString())
        return (0 until arr.length()).map { arr.getJSONObject(it) }.filter { !it.optBoolean("draft") }.mapNotNull { r ->
            val assets = r.getJSONArray("assets").let { x -> (0 until x.length()).map { x.getJSONObject(it) } }
            val apk = assets.firstOrNull { it.getString("name") == a.apkName }
                ?: assets.firstOrNull { it.getString("name").endsWith(".apk") }
                ?: return@mapNotNull null
            Release(
                tag = r.getString("tag_name"), notes = r.optString("body"),
                date = r.optString("published_at").take(10), prerelease = r.optBoolean("prerelease"),
                published = runCatching { java.time.Instant.parse(r.getString("published_at")).toEpochMilli() }.getOrDefault(0),
                apkUrl = apk.getString("browser_download_url"), size = apk.getLong("size"),
                sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
                versionCode = null,
            )
        }
    }

    /** Opcjonalny <nazwa>.json obok APK (jak dsh-mobile.json) podaje versionCode, pewniejszy niż tag. */
    private fun withVersionCode(a: AppEntry, r: Release): Release {
        val metaUrl = r.apkUrl.removeSuffix(".apk") + ".json"
        val vc = runCatching { JSONObject(http(metaUrl).decodeToString()).getLong("versionCode") }.getOrNull()
        return r.copy(versionCode = vc)
    }

    // ---------- stan zainstalowania ----------

    fun installedVersion(ctx: Context, pkg: String): Pair<Long, String>? = try {
        val p = ctx.packageManager.getPackageInfo(pkg, 0)
        p.longVersionCode to (p.versionName ?: "")
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * Czy wydanie jest nowsze od zainstalowanej wersji. Tag wydania bywa niezgodny z versionName w APK
     * (AI Image Enhancer v1.1.0 ma w środku versionName 1.0), więc kolejność źródeł prawdy:
     * versionCode z <apk>.json → zapis, które wydanie LABstore sam zainstalował → data instalacji przez
     * LABstore późniejsza niż publikacja wydania → dopiero porównanie tagu z versionName.
     */
    fun isNewer(ctx: Context, pkg: String, rel: Release, installed: Pair<Long, String>): Boolean {
        rel.versionCode?.let { return it > installed.first }
        prefs(ctx).getString("installed_$pkg", null)?.split('|')?.let { (tag, vc) ->
            if (vc.toLongOrNull() == installed.first) return rel.tag != tag && compareVersions(rel.tag, tag) > 0
        }
        if (installedByUsAfter(ctx, pkg, rel.published)) return false
        return compareVersions(rel.tag, installed.second) > 0
    }

    private fun installedByUsAfter(ctx: Context, pkg: String, published: Long): Boolean = runCatching {
        if (published <= 0 || android.os.Build.VERSION.SDK_INT < 30) return false
        val pm = ctx.packageManager
        pm.getInstallSourceInfo(pkg).installingPackageName == ctx.packageName &&
            pm.getPackageInfo(pkg, 0).lastUpdateTime >= published
    }.getOrDefault(false)

    /** Po udanej instalacji: zapamiętaj, które wydanie dało tę wersję aplikacji. */
    fun rememberInstalled(ctx: Context, pkg: String, tag: String) {
        val vc = installedVersion(ctx, pkg)?.first ?: return
        prefs(ctx).edit().putString("installed_$pkg", "$tag|$vc").apply()
    }

    private fun compareVersions(a: String, b: String): Int {
        val x = Regex("\\d+").findAll(a.substringBefore('-')).map { it.value.toLong() }.toList()
        val y = Regex("\\d+").findAll(b.substringBefore('-')).map { it.value.toLong() }.toList()
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    // ---------- instalacja ----------

    /** Zapis postępu: "sessionId|url|done|total", żeby wznowić po zerwaniu sieci albo zabiciu apki. */
    private fun saveSession(ctx: Context, pkg: String, id: Int, url: String, done: Long, total: Long) =
        prefs(ctx).edit().putString("session_$pkg", "$id|$url|$done|$total").apply()

    private fun clearSession(ctx: Context, pkg: String) = prefs(ctx).edit().remove("session_$pkg").apply()

    private fun savedSession(ctx: Context, pkg: String): List<String>? {
        val s = prefs(ctx).getString("session_$pkg", null)?.split('|') ?: return null
        // Sesja mogła wygasnąć albo zostać porzucona przez system.
        return s.takeIf { it.size == 4 && ctx.packageManager.packageInstaller.getSessionInfo(it[0].toInt()) != null }
            ?: run { clearSession(ctx, pkg); null }
    }

    private fun restorePaused(ctx: Context) {
        for (a in apps.value) {
            if (jobs[a.pkg] != null) continue
            val s = savedSession(ctx, a.pkg) ?: continue
            jobs[a.pkg] = Task.Paused(s[2].toLong(), s[3].toLong(), s[1])
        }
    }

    /** Wydanie, od którego jest przerwane pobieranie, albo najnowsze, gdy tamto już zniknęło. */
    fun resumeTarget(pkg: String, url: String): Release? =
        history[pkg]?.firstOrNull { it.apkUrl == url } ?: releases[pkg]

    /**
     * APK idzie strumieniem prosto do sesji PackageInstallera (bez kopii na dysku, DSH ma ~890 MB).
     * Sesja przeżywa zabicie apki, więc po przerwie dopisujemy dalej od zapisanego miejsca (nagłówek Range).
     */
    fun install(ctx: Context, a: AppEntry, rel: Release) {
        if (running[a.pkg]?.isActive == true) return
        val app = ctx.applicationContext
        val pi = app.packageManager.packageInstaller
        val saved = savedSession(app, a.pkg)
        if (saved != null && saved[1] != rel.apkUrl) {
            runCatching { pi.abandonSession(saved[0].toInt()) }
            clearSession(app, a.pkg)
        }
        val resume = saved?.takeIf { it[1] == rel.apkUrl }
        var done = resume?.get(2)?.toLong() ?: 0L
        jobs[a.pkg] = Task.Downloading(done, rel.size)
        DownloadService.start(app)

        running[a.pkg] = scope.launch {
            val id = resume?.get(0)?.toInt() ?: pi.createSession(
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(a.pkg)
                    setSize(rel.size)
                },
            )
            try {
                pi.openSession(id).use { s ->
                    if (done < rel.size) {
                        val conn = open(rel.apkUrl, from = done)
                        if (done > 0 && conn.responseCode != 206) done = 0 // serwer zignorował Range: od nowa
                        conn.inputStream.use { inp ->
                            s.openWrite("base.apk", done, rel.size).use { out ->
                                val buf = ByteArray(256 * 1024)
                                var lastUi = done
                                var lastSync = done
                                while (true) {
                                    ensureActive()
                                    val n = inp.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (done - lastUi > 1_000_000) {
                                        lastUi = done
                                        jobs[a.pkg] = Task.Downloading(done, rel.size)
                                    }
                                    if (done - lastSync > 8_000_000) {
                                        s.fsync(out)
                                        lastSync = done
                                        saveSession(app, a.pkg, id, rel.apkUrl, done, rel.size)
                                    }
                                }
                                s.fsync(out)
                                saveSession(app, a.pkg, id, rel.apkUrl, done, rel.size)
                            }
                        }
                        if (done != rel.size) throw IOException("Pobrano $done z ${rel.size} B")
                    }
                    if (rel.sha256 != null) {
                        jobs[a.pkg] = Task.Verifying
                        val md = MessageDigest.getInstance("SHA-256")
                        s.openRead("base.apk").use { inp ->
                            val buf = ByteArray(1 shl 20)
                            while (true) {
                                ensureActive()
                                val n = inp.read(buf)
                                if (n < 0) break
                                md.update(buf, 0, n)
                            }
                        }
                        val got = md.digest().joinToString("") { "%02x".format(it) }
                        if (!got.equals(rel.sha256, true)) {
                            runCatching { pi.abandonSession(id) }
                            clearSession(app, a.pkg)
                            jobs.remove(a.pkg)
                            message.value = "${a.name}: plik uszkodzony (SHA-256 się nie zgadza), pobierz ponownie"
                            return@launch
                        }
                    }
                    jobs[a.pkg] = Task.Installing
                    clearSession(app, a.pkg)
                    val cb = PendingIntent.getBroadcast(
                        app, id, Intent(app, InstallReceiver::class.java).putExtra("pkg", a.pkg).putExtra("tag", rel.tag),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    s.commit(cb.intentSender)
                }
            } catch (e: CancellationException) {
                // Anulowanie przez użytkownika: cancel() porzuca sesję.
            } catch (e: Exception) {
                // Sieć: zostawiamy sesję z pobraną częścią do wznowienia.
                if (savedSession(app, a.pkg) == null) saveSession(app, a.pkg, id, rel.apkUrl, 0, rel.size)
                val got = savedSession(app, a.pkg)?.get(2)?.toLong() ?: 0
                jobs[a.pkg] = Task.Paused(got, rel.size, rel.apkUrl)
                message.value = "${a.name}: pobieranie przerwane (${e.message}). Możesz wznowić."
            }
        }
    }

    fun cancel(ctx: Context, pkg: String) {
        running.remove(pkg)?.cancel()
        jobs.remove(pkg)
        savedSession(ctx, pkg)?.let { runCatching { ctx.packageManager.packageInstaller.abandonSession(it[0].toInt()) } }
        clearSession(ctx, pkg)
    }

    // ---------- sieć i obrazy ----------

    private fun open(url: String, from: Long = 0): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "LABstore")
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        if (c.responseCode !in 200..299) {
            val code = c.responseCode
            c.disconnect()
            throw IOException(if (code == 403) "limit zapytań GitHuba, spróbuj za chwilę" else "HTTP $code")
        }
        return c
    }

    private fun http(url: String): ByteArray = open(url).let { c -> c.inputStream.use { it.readBytes() } }

    private val images = LruCache<String, Bitmap>(24)

    /** Ścieżka względna w katalogu odnosi się do repo katalogu. */
    suspend fun image(ctx: Context, src: String, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (src.isEmpty()) return@withContext null
        val key = "$src@$maxPx"
        images.get(key)?.let { return@withContext it }
        runCatching {
            val bytes = when {
                src.startsWith("http") -> http(src)
                else -> http(base + src)
            }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()?.also { images.put(key, it) }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pkg = intent.getStringExtra("pkg") ?: return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                intent.getStringExtra("tag")?.let { Store.rememberInstalled(ctx, pkg, it) }
                Store.jobs.remove(pkg)
                Store.installedTick.value++
                Store.message.value = "Zainstalowano"
            }
            else -> {
                Store.jobs.remove(pkg)
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                Store.message.value = when {
                    status == PackageInstaller.STATUS_FAILURE_ABORTED -> "Instalacja anulowana"
                    why.contains("DOWNGRADE") ->
                        "To starsza wersja: najpierw odinstaluj obecną (dane aplikacji zostaną usunięte)."
                    status == PackageInstaller.STATUS_FAILURE_CONFLICT ->
                        "Konflikt podpisu: zainstalowana wersja ma inny klucz. Odinstaluj ją i spróbuj ponownie."
                    status == PackageInstaller.STATUS_FAILURE_STORAGE -> "Za mało miejsca na telefonie"
                    else -> "Instalacja nie powiodła się ($why)"
                }
            }
        }
    }
}
