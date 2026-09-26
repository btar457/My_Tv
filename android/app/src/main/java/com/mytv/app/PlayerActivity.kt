package com.mytv.app

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var error: TextView
    private var player: ExoPlayer? = null
    private val prefs by lazy { Prefs(this) }
    private val hideTitle = Runnable { titleView.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        titleView = findViewById(R.id.title)
        error = findViewById(R.id.error)
        error.setOnClickListener { retry() }

        if (PlayerSession.channels.isEmpty()) {
            finish()
            return
        }
        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        if (player == null && PlayerSession.channels.isNotEmpty()) initPlayer()
    }

    override fun onStop() {
        super.onStop()
        player?.let { PlayerSession.index = it.currentMediaItemIndex }
        player?.release()
        player = null
    }

    private fun initPlayer() {
        val channels = PlayerSession.channels

        // Per-channel User-Agent / Referer, looked up by host so HLS segments get them too.
        val headersByHost = HashMap<String, Map<String, String>>()
        for (ch in channels) {
            val host = Uri.parse(ch.url).host ?: continue
            val h = HashMap<String, String>()
            h["User-Agent"] = ch.userAgent ?: DEFAULT_USER_AGENT
            ch.referrer?.let { h["Referer"] = it }
            headersByHost.putIfAbsent(host, h)
        }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(null)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        val withHeaders = ResolvingDataSource.Factory(http) { spec ->
            val headers = headersByHost[spec.uri.host] ?: mapOf("User-Agent" to DEFAULT_USER_AGENT)
            spec.withAdditionalHeaders(headers)
        }

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(withHeaders))
            .build()
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                error.visibility = View.GONE
                showTitle()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) error.visibility = View.GONE
            }

            override fun onPlayerError(e: PlaybackException) {
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    exo.seekToDefaultPosition()
                    exo.prepare()
                    return
                }
                error.text = "تعذّر تشغيل هذه القناة\n(${e.errorCodeName})\n\nاضغط لإعادة المحاولة، أو انتقل للقناة التالية"
                error.visibility = View.VISIBLE
            }
        })

        exo.setMediaItems(channels.map(::mediaItem), PlayerSession.index.coerceIn(0, channels.lastIndex), 0)
        exo.playWhenReady = true
        exo.prepare()
        playerView.player = exo
        player = exo
        showTitle()
    }

    private fun mediaItem(ch: Channel): MediaItem {
        val path = Uri.parse(ch.url).path.orEmpty().lowercase()
        val mime = when {
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".mp4") || path.endsWith(".ts") || path.endsWith(".mkv") -> null
            // Most IPTV links are HLS, even the ones without a .m3u8 extension.
            else -> MimeTypes.APPLICATION_M3U8
        }
        return MediaItem.Builder()
            .setUri(ch.url)
            .setMediaId(ch.url)
            .apply { mime?.let { setMimeType(it) } }
            .build()
    }

    private fun currentChannel(): Channel? {
        val i = player?.currentMediaItemIndex ?: return null
        return PlayerSession.channels.getOrNull(i)
    }

    private fun showTitle() {
        val ch = currentChannel() ?: return
        prefs.addRecent(ch.url)
        val i = (player?.currentMediaItemIndex ?: 0) + 1
        titleView.text = "$i. ${ch.name}"
        titleView.visibility = View.VISIBLE
        titleView.removeCallbacks(hideTitle)
        titleView.postDelayed(hideTitle, 4000)
    }

    private fun retry() {
        error.visibility = View.GONE
        player?.let {
            it.seekToDefaultPosition()
            it.prepare()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        if (p != null && event.action == KeyEvent.ACTION_DOWN && !playerView.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    if (p.hasNextMediaItem()) p.seekToNextMediaItem()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
