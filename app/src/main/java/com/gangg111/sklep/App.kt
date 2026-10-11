package com.gangg111.sklep

import android.app.Application
import android.content.ContentValues
import android.provider.MediaStore
import android.util.Log

/** Zapisuje każdy crash do Pobrane/sklep_crash.txt (MediaStore, bez uprawnień), bo logcat cudzej apki jest nieczytelny. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val text = "LABstore ${packageManager.getPackageInfo(packageName, 0).versionName}, wątek ${t.name}\n" +
                    Log.getStackTraceString(e)
                val uri = contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, "sklep_crash.txt")
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    },
                )
                uri?.let { u -> contentResolver.openOutputStream(u)?.use { it.write(text.toByteArray()) } }
            }
            prev?.uncaughtException(t, e)
        }
    }
}
