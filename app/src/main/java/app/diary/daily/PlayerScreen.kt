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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
    total: Int,       // сколько видео в одном подходе
    got: Int,         // сколько из них уже просмотрено в текущем подходе
    dup: Boolean,     // это видео уже смотрели в текущем подходе
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
    val pos = remember { mutableLongStateOf(0L) }
    val posSec by remember { derivedStateOf { pos.longValue / 1000 } }
    var drag by remember { mutableStateOf<Float?>(null) }
    var dur by remember { mutableLongStateOf(video.durMs) }
    var ratio by remember { mutableFloatStateOf(16f / 9f) }
    var controls by remember { mutableStateOf(true) }
    var touch by remember { mutableIntStateOf(0) }
    var landscape by remember { mutableStateOf(false) }
    var watched by remember { mutableLongStateOf(0L) }
    var counted by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(countNow) }
    val full = countNow >= limit
    val skip = full || dup

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

    // плавная позиция: обновляется каждый кадр, пока панель видна (читается только при отрисовке)
    LaunchedEffect(controls) {
        if (controls) {
            while (true) withFrameNanos { pos.longValue = player.currentPosition }
        } else {
            pos.longValue = player.currentPosition
        }
    }

    // честный зачёт: считаем только время реального воспроизведения, перемотка не в счёт
    LaunchedEffect(player) {
        var last = player.currentPosition
        while (true) {
            delay(100)
            val now = player.currentPosition
            if (player.isPlaying) {
                val d = now - last
                if (d in 1..600) watched += d
            }
            last = now
            if (!counted && !skip && dur > 0 && watched >= (dur * COUNT_AT).toLong()) {
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

    val iconTint = ColorFilter.tint(Color.White)
    val chip = Color.Black.copy(alpha = 0.45f)
    Box(Modifier.fillMaxSize().background(Color.Black).press { controls = !controls }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { c -> SurfaceView(c).also { player.setVideoSurfaceView(it) } },
                modifier = Modifier.aspectRatio(ratio),
            )
        }

        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().displayCutoutPadding()) {
                // верх: закрыть, название, во весь экран (без затемнения, только маленькие плашки)
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.size(44.dp))
                    Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        BasicText(
                            video.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(chip).padding(horizontal = 14.dp, vertical = 9.dp),
                            style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        )
                    }
                    Spacer(Modifier.size(44.dp))
                }
                Box(Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 10.dp)) {
                    BtnIcon(44.dp, chip, R.drawable.ic_close, 22.dp, iconTint) { onClose() }
                }
                Box(Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 10.dp)) {
                    BtnIcon(
                        44.dp, chip, if (landscape) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen,
                        22.dp, iconTint,
                    ) { landscape = !landscape; touch++ }
                }

                // статус зачёта
                val frac = if (dur > 0) (watched.toFloat() / (dur * COUNT_AT)).coerceIn(0f, 1f) else 0f
                val statusText = when {
                    counted && count > countNow -> if (limit == 1) "Засчитано" else "Подход засчитан · $count из $limit"
                    counted -> "Видео засчитано · ${minOf(got + 1, total)} из $total"
                    full -> if (limit == 1) "Уже засчитано сегодня" else "$limit из $limit сегодня"
                    dup -> "Уже смотрел в этом подходе"
                    total > 1 -> "Видео ${got + 1} из $total · просмотр ${(frac * 100).toInt()}%"
                    else -> "Просмотр ${(frac * 100).toInt()}%"
                }
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 66.dp)
                        .clip(RoundedCornerShape(12.dp)).background(chip)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (counted) {
                        Image(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(p.ac))
                        Spacer(Modifier.width(6.dp))
                    }
                    BasicText(statusText, style = TextStyle(color = if (counted) p.ac else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                }

                // центр: назад 10, воспроизведение, вперёд 10
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    BtnIcon(56.dp, chip, R.drawable.ic_back10, 32.dp, iconTint) {
                        player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); touch++
                    }
                    Spacer(Modifier.width(24.dp))
                    BtnIcon(
                        80.dp, p.ac, if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                        38.dp, ColorFilter.tint(p.bg),
                    ) {
                        when {
                            ended -> { player.seekTo(0); player.play() }
                            player.isPlaying -> player.pause()
                            else -> player.play()
                        }
                        touch++
                    }
                    Spacer(Modifier.width(24.dp))
                    BtnIcon(56.dp, chip, R.drawable.ic_fwd10, 32.dp, iconTint) {
                        val t = player.currentPosition + 10_000
                        player.seekTo(if (dur > 0) minOf(t, dur) else t); touch++
                    }
                }

                // низ: компактный округлый островок, время по краям полосы
                val shown = drag?.let { (it * dur / 1000).toLong() } ?: posSec
                val timeStyle = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp).padding(bottom = 16.dp)
                        .widthIn(max = 320.dp).fillMaxWidth()
                        .clip(CircleShape).background(chip)
                        .padding(horizontal = 18.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(fmtTime(shown * 1000), style = timeStyle)
                    Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Seek(
                            pos = { pos.longValue }, dur = dur, ac = p.ac, mark = COUNT_AT, showMark = !skip,
                            drag = drag, onDrag = { drag = it; touch++ },
                            onSeek = { player.seekTo(it); pos.longValue = it; touch++ },
                        )
                    }
                    BasicText(fmtTime(dur), style = timeStyle)
                }
            }
        }
    }
}

@Composable
private fun BtnIcon(size: androidx.compose.ui.unit.Dp, bg: Color, icon: Int, iconSize: androidx.compose.ui.unit.Dp, tint: ColorFilter, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(bg).press(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(icon), null, Modifier.size(iconSize), colorFilter = tint)
    }
}

@Composable
private fun Seek(
    pos: () -> Long, dur: Long, ac: Color, mark: Float, showMark: Boolean,
    drag: Float?, onDrag: (Float?) -> Unit, onSeek: (Long) -> Unit,
) {
    val curDrag by rememberUpdatedState(drag)
    Canvas(
        Modifier.fillMaxWidth().height(28.dp)
            .pointerInput(dur) {
                detectTapGestures { o -> if (dur > 0) onSeek(((o.x / size.width).coerceIn(0f, 1f) * dur).toLong()) }
            }
            .pointerInput(dur) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> onDrag((o.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { curDrag?.let { if (dur > 0) onSeek((it * dur).toLong()) }; onDrag(null) },
                    onDragCancel = { onDrag(null) },
                    onHorizontalDrag = { c, _ -> onDrag((c.position.x / size.width).coerceIn(0f, 1f)) },
                )
            },
    ) {
        // позиция читается здесь, при отрисовке: полоса плавно едет каждый кадр без перекомпоновки
        val frac = drag ?: if (dur > 0) (pos().toFloat() / dur).coerceIn(0f, 1f) else 0f
        val h = 4.dp.toPx()
        val y = (size.height - h) / 2
        val r = CornerRadius(h / 2, h / 2)
        drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, y), Size(size.width, h), r)
        drawRoundRect(ac, Offset(0f, y), Size(size.width * frac, h), r)
        if (showMark) {
            val x = size.width * mark
            drawRoundRect(Color.White.copy(alpha = 0.7f), Offset(x - 1.dp.toPx(), y - 4.dp.toPx()), Size(2.dp.toPx(), h + 8.dp.toPx()), CornerRadius(1.dp.toPx()))
        }
        drawCircle(ac, (if (drag != null) 9.dp else 7.dp).toPx(), Offset(size.width * frac, size.height / 2))
    }
}
