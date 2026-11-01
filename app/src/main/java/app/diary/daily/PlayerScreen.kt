package app.diary.daily

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

private const val COUNT_AT = 0.9f // доля реально просмотренного, после которой засчитываем

@Composable
fun PlayerScreen(
    p: Palette,
    video: Video,
    countNow: Int,
    limit: Int,
    onCounted: () -> Int,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val activity = ctx as? Activity
    val owner = LocalLifecycleOwner.current
    val player = remember { ExoPlayer.Builder(ctx).build() }

    var playing by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(video.durMs) }
    var ratio by remember { mutableFloatStateOf(16f / 9f) }
    var controls by remember { mutableStateOf(true) }
    var touch by remember { mutableIntStateOf(0) }
    var landscape by remember { mutableStateOf(false) }
    var watched by remember { mutableLongStateOf(0L) }
    var counted by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(countNow) }
    val full = countNow >= limit

    // плеер
    DisposableEffect(player) {
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(Videos.file(ctx, video))))
        player.prepare()
        player.playWhenReady = true
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && player.duration > 0) dur = player.duration
                ended = state == Player.STATE_ENDED
                if (ended) controls = true
            }
            override fun onVideoSizeChanged(s: VideoSize) {
                if (s.width > 0 && s.height > 0) ratio = s.width * s.pixelWidthHeightRatio / s.height
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }

    // пауза при сворачивании
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) player.pause() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // экран не гаснет, системные панели скрыты, на выходе всё возвращается
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        val w = activity?.window
        val ctrl = w?.let { WindowCompat.getInsetsController(it, it.decorView) }
        ctrl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            view.keepScreenOn = false
            ctrl?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    LaunchedEffect(landscape) {
        activity?.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    // позиция и честный зачёт: считаем только время реального воспроизведения, перемотка не в счёт
    LaunchedEffect(player) {
        var last = player.currentPosition
        while (true) {
            delay(250)
            val now = player.currentPosition
            pos = now
            if (player.isPlaying) {
                val d = now - last
                if (d in 1..1200) watched += d
            }
            last = now
            if (!counted && !full && dur > 0 && watched >= (dur * COUNT_AT).toLong()) {
                counted = true
                count = onCounted()
                controls = true
            }
        }
    }

    // автоскрытие панели
    LaunchedEffect(controls, playing, touch) {
        if (controls && playing) {
            delay(3000)
            controls = false
        }
    }

    BackHandler { onClose() }

    val iconTint = ColorFilter.tint(p.ac)
    Box(Modifier.fillMaxSize().background(Color.Black).press { controls = !controls }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { c -> SurfaceView(c).also { player.setVideoSurfaceView(it) } },
                modifier = Modifier.aspectRatio(ratio),
            )
        }

        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().displayCutoutPadding()) {
                // затемнение под элементами
                Box(
                    Modifier.fillMaxWidth().height(120.dp).align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                )
                Box(
                    Modifier.fillMaxWidth().height(150.dp).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                )

                // верх
                Box(Modifier.fillMaxWidth().height(56.dp)) {
                    Box(Modifier.align(Alignment.CenterStart).size(56.dp).press { onClose() }, contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_close), null, Modifier.size(24.dp), colorFilter = iconTint)
                    }
                    BasicText(
                        video.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 64.dp),
                        style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    )
                    Box(
                        Modifier.align(Alignment.CenterEnd).size(56.dp).press { landscape = !landscape; touch++ },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painterResource(if (landscape) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen),
                            null, Modifier.size(24.dp), colorFilter = iconTint,
                        )
                    }
                }

                // статус зачёта
                val frac = if (dur > 0) (watched.toFloat() / (dur * COUNT_AT)).coerceIn(0f, 1f) else 0f
                val statusText = when {
                    counted -> if (limit == 1) "Засчитано" else "Засчитано · $count из $limit"
                    full -> if (limit == 1) "Уже засчитано сегодня" else "$limit из $limit сегодня"
                    else -> "Просмотр ${(frac * 100).toInt()}%"
                }
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (counted) {
                        Image(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp), colorFilter = iconTint)
                        Spacer(Modifier.width(6.dp))
                    }
                    BasicText(statusText, style = TextStyle(color = if (counted) p.ac else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                }

                // центр
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).press { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); touch++ }, contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_back10), null, Modifier.size(32.dp), colorFilter = iconTint)
                    }
                    Spacer(Modifier.width(20.dp))
                    Box(
                        Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.14f)).press {
                            when {
                                ended -> { player.seekTo(0); player.play() }
                                player.isPlaying -> player.pause()
                                else -> player.play()
                            }
                            touch++
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                            null, Modifier.size(34.dp), colorFilter = iconTint,
                        )
                    }
                    Spacer(Modifier.width(20.dp))
                    Box(
                        Modifier.size(56.dp).press {
                            val t = player.currentPosition + 10_000
                            player.seekTo(if (dur > 0) minOf(t, dur) else t); touch++
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(painterResource(R.drawable.ic_fwd10), null, Modifier.size(32.dp), colorFilter = iconTint)
                    }
                }

                // низ
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
                    Seek(
                        pos = pos, dur = dur, ac = p.ac, mark = COUNT_AT, showMark = !full,
                        onSeek = { player.seekTo(it); pos = it; touch++ },
                    )
                    Row(Modifier.fillMaxWidth()) {
                        BasicText(fmtTime(pos), style = TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp))
                        Spacer(Modifier.weight(1f))
                        BasicText(fmtTime(dur), style = TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Seek(pos: Long, dur: Long, ac: Color, mark: Float, showMark: Boolean, onSeek: (Long) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Canvas(
        Modifier.fillMaxWidth().height(32.dp)
            .pointerInput(dur) {
                detectTapGestures { o -> if (dur > 0) onSeek((o.x / size.width).coerceIn(0f, 1f).let { (it * dur).toLong() }) }
            }
            .pointerInput(dur) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let { if (dur > 0) onSeek((it * dur).toLong()) }; drag = null },
                    onDragCancel = { drag = null },
                    onHorizontalDrag = { c, _ -> drag = (c.position.x / size.width).coerceIn(0f, 1f) },
                )
            },
    ) {
        val h = 4.dp.toPx()
        val y = (size.height - h) / 2
        val r = CornerRadius(h / 2, h / 2)
        drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, y), Size(size.width, h), r)
        drawRoundRect(ac, Offset(0f, y), Size(size.width * frac, h), r)
        if (showMark) {
            val x = size.width * mark
            drawRoundRect(Color.White.copy(alpha = 0.7f), Offset(x - 1.dp.toPx(), y - 4.dp.toPx()), Size(2.dp.toPx(), h + 8.dp.toPx()), CornerRadius(1.dp.toPx()))
        }
        drawCircle(ac, 7.dp.toPx(), Offset(size.width * frac, size.height / 2))
    }
}
