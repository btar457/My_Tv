package com.mytv.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

data class RefreshResult(val channels: List<Channel>, val errors: List<String>)

class Repository(private val context: Context) {
    private val cacheFile = File(context.filesDir, "channels.json")

    fun loadCache(): List<Channel> = runCatching {
        val arr = JSONArray(cacheFile.readText())
        (0 until arr.length()).map { Channel.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun saveCache(list: List<Channel>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        cacheFile.writeText(arr.toString())
    }

    /** Downloads every source, maps groups, removes duplicate URLs and caches the result. */
    suspend fun refresh(sources: List<Source>): RefreshResult = coroutineScope {
        val results = sources.map { src ->
            async(Dispatchers.IO) {
                runCatching { src to M3uParser.parse(download(src.url)) }
            }
        }.awaitAll()

        val errors = ArrayList<String>()
        val seen = HashSet<String>()
        val channels = ArrayList<Channel>()
        results.forEachIndexed { i, r ->
            val src = sources[i]
            val (_, entries) = r.getOrElse {
                errors.add("${src.name}: ${it.message ?: it.javaClass.simpleName}")
                return@forEachIndexed
            }
            for (e in entries) {
                if (!seen.add(e.url)) continue
                channels.add(
                    Channel(
                        name = e.name,
                        url = e.url,
                        group = Groups.map(e.attrs["group-title"], src),
                        logo = e.attrs["tvg-logo"]?.ifBlank { null },
                        userAgent = e.userAgent ?: e.attrs["http-user-agent"],
                        referrer = e.referrer ?: e.attrs["http-referrer"],
                        adult = src.adult,
                    )
                )
            }
        }
        // Keep the old list if every source failed (e.g. no internet).
        if (channels.isNotEmpty()) withContext(Dispatchers.IO) { saveCache(channels) }
        RefreshResult(channels, errors)
    }

    /** Tests every stream from this device. Returns the URLs that did not respond. */
    suspend fun check(
        channels: List<Channel>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Set<String> = coroutineScope {
        val gate = Semaphore(24)
        val done = AtomicInteger()
        val total = channels.size
        channels.map { ch ->
            async(Dispatchers.IO) {
                val ok = gate.withPermit { isAlive(ch) }
                onProgress(done.incrementAndGet(), total)
                if (ok) null else ch.url
            }
        }.awaitAll().filterNotNull().toSet()
    }

    private fun open(url: String, ua: String?, ref: String?, timeoutMs: Int): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", ua ?: DEFAULT_USER_AGENT)
        ref?.let { conn.setRequestProperty("Referer", it) }
        return conn
    }

    private fun download(url: String): String {
        val conn = open(url, null, null, 30_000)
        try {
            if (conn.responseCode >= 400) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun isAlive(ch: Channel): Boolean = try {
        val conn = open(ch.url, ch.userAgent, ch.referrer, 10_000)
        try {
            if (conn.responseCode >= 400) {
                false
            } else {
                val buf = ByteArray(2048)
                val n = conn.inputStream.use { it.read(buf) }
                val head = if (n > 0) String(buf, 0, n, Charsets.ISO_8859_1).trimStart('﻿', ' ', '\r', '\n', '\t') else ""
                val type = conn.contentType.orEmpty().lowercase()
                head.startsWith("#EXTM3U") || (n > 0 && buf[0] == 0x47.toByte()) ||
                    "video" in type || "dash" in type || "mpegurl" in type
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        false
    }
}
