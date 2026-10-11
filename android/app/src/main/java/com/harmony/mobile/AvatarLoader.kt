package com.harmony.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Loads member avatars from an instance, with its session cookie, and caches
 * them. Avatars are tiny and a call has at most a handful of members, so a plain
 * in-memory cache and a small thread pool are enough.
 */
object AvatarLoader {
    private val client = OkHttpClient()
    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap<String, MutableList<(Bitmap?) -> Unit>>()

    fun load(url: String, cookie: String, onResult: (Bitmap?) -> Unit) {
        cache[url]?.let {
            main.post { onResult(it) }
            return
        }
        if (failed.contains(url)) {
            main.post { onResult(null) }
            return
        }
        synchronized(pending) {
            pending[url]?.let {
                it.add(onResult)
                return
            }
            pending[url] = mutableListOf(onResult)
        }
        io.execute {
            val bitmap = runCatching {
                val request = Request.Builder().url(url).header("Cookie", cookie).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
            }.getOrNull()
            if (bitmap != null) cache[url] = bitmap else failed.add(url)
            val waiters = synchronized(pending) { pending.remove(url) } ?: emptyList()
            main.post { for (waiter in waiters) waiter(bitmap) }
        }
    }
}
