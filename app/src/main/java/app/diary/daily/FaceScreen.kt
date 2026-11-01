package app.diary.daily

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FaceScreen(
    p: Palette,
    kind: String,
    count: Int,
    onBack: () -> Unit,
    onPlay: (Video) -> Unit,
    onManual: (() -> Unit)? = null,
) {
    val limit = limitOf(kind)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(Videos.list(ctx).filter { it.kind == kind }) }
    val faceKind = kind != "gym"
    // для упражнений показываем, что просмотрено в текущем подходе; для тренировки — за сегодня
    val watched = remember { if (faceKind) Videos.session(ctx) else Videos.watchedToday(ctx) }
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            busy = true
            failed = false
            scope.launch {
                val ok = withContext(Dispatchers.IO) { uris.map { Videos.add(ctx, it, kind) }.count { it != null } }
                list = Videos.list(ctx).filter { it.kind == kind }
                failed = ok < uris.size
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(p.bg).systemBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            Box(Modifier.align(Alignment.CenterStart).size(56.dp).press(onBack), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_back), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(p.tx))
            }
            BasicText(
                if (kind == "gym") "Тренировка" else "Упражнения",
                modifier = Modifier.align(Alignment.Center),
                style = TextStyle(color = p.tx, fontSize = 16.sp, fontWeight = FontWeight.Medium),
            )
        }

        Column(Modifier.padding(horizontal = 28.dp)) {
            Spacer(Modifier.height(8.dp))
            if (limit == 1) {
                BasicText(
                    if (count >= 1) "Готово" else "Не засчитана",
                    maxLines = 1, softWrap = false,
                    style = TextStyle(color = if (count >= 1) p.ac else p.tx, fontSize = 36.sp, fontWeight = FontWeight.Light),
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicText(
                        "$count из $limit",
                        maxLines = 1, softWrap = false,
                        style = TextStyle(color = if (count >= limit) p.ac else p.tx, fontSize = 40.sp, fontWeight = FontWeight.Light),
                    )
                    BasicText(
                        "сегодня", maxLines = 1, softWrap = false,
                        style = TextStyle(color = p.mu, fontSize = 15.sp), modifier = Modifier.padding(start = 10.dp, bottom = 8.dp),
                    )
                }
            }
            BasicText(
                if (faceKind) "досмотри все видео подряд" else "досмотри видео до конца, и засчитается",
                maxLines = 1, softWrap = false,
                style = TextStyle(color = p.mu, fontSize = 14.sp),
            )
            Spacer(Modifier.height(16.dp))
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (list.isEmpty()) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText("пока нет видео", style = TextStyle(color = p.tx, fontSize = 16.sp, fontWeight = FontWeight.Medium))
                    Spacer(Modifier.height(4.dp))
                    BasicText("добавь свои, и они будут здесь", style = TextStyle(color = p.mu, fontSize = 14.sp, textAlign = TextAlign.Center))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 28.dp), state = listState) {
                    if (list.size > 1) {
                        item(key = "hint") {
                            BasicText(
                                "зажми и перетащи, чтобы поменять порядок",
                                maxLines = 1, softWrap = false,
                                style = TextStyle(color = p.mu, fontSize = 13.sp),
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                    items(list, key = { it.id }) { v ->
                        val done = v.id in watched
                        val dragging = dragId == v.id
                        Row(
                            Modifier.fillMaxWidth()
                                .then(if (dragging) Modifier else Modifier.animateItemPlacement())
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (dragging) dragDy else 0f
                                    val sc = if (dragging) 1.03f else 1f
                                    scaleX = sc; scaleY = sc
                                }
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (dragging) p.tx.copy(alpha = 0.08f) else Color.Transparent)
                                .press { confirm = null; onPlay(v) }
                                .padding(vertical = 8.dp, horizontal = 6.dp)
                                .pointerInput(v.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            confirm = null
                                            dragId = v.id; dragDy = 0f
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragDy += amount.y
                                            val infos = listState.layoutInfo.visibleItemsInfo
                                            val me = infos.firstOrNull { it.key == v.id }
                                            if (me != null) {
                                                val center = me.offset + me.size / 2f + dragDy
                                                val target = infos.firstOrNull {
                                                    it.key != v.id && it.key != "hint" &&
                                                        center >= it.offset && center < it.offset + it.size
                                                }
                                                if (target != null) {
                                                    val from = list.indexOfFirst { it.id == v.id }
                                                    val to = list.indexOfFirst { it.id == target.key }
                                                    if (from >= 0 && to >= 0 && from != to) {
                                                        val m = list.toMutableList()
                                                        m.add(to, m.removeAt(from))
                                                        list = m
                                                        dragDy += (me.offset - target.offset).toFloat()
                                                    }
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            dragId = null; dragDy = 0f
                                            Videos.reorder(ctx, list.map { it.id })
                                        },
                                        onDragCancel = {
                                            dragId = null; dragDy = 0f
                                            Videos.reorder(ctx, list.map { it.id })
                                        },
                                    )
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Thumb(v, p.mu, Modifier.size(width = 96.dp, height = 60.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                BasicText(
                                    v.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(color = p.tx, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                                )
                                BasicText(
                                    if (done) "${fmtTime(v.durMs)} · " + (if (faceKind) "в этом подходе" else "сегодня смотрел") else fmtTime(v.durMs),
                                    style = TextStyle(color = if (done) p.ac else p.mu, fontSize = 13.sp),
                                )
                            }
                            if (confirm == v.id) {
                                BasicText(
                                    "удалить?",
                                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(p.ac.copy(alpha = 0.14f))
                                        .press { Videos.remove(ctx, v.id); list = Videos.list(ctx).filter { it.kind == kind }; confirm = null }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    style = TextStyle(color = p.ac, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                                )
                            } else {
                                Box(Modifier.size(44.dp).press { confirm = v.id }, contentAlignment = Alignment.Center) {
                                    Image(painterResource(R.drawable.ic_trash), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(p.mu))
                                }
                            }
                        }
                    }
                }
            }
        }

        Column(
            Modifier.padding(horizontal = 28.dp).padding(bottom = 16.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (failed) {
                BasicText("часть видео не добавилась", style = TextStyle(color = p.mu, fontSize = 13.sp))
            }
            if (onManual != null) {
                ActionButton(
                    p, R.drawable.ic_check,
                    if (count >= 1) "Снять отметку" else "Отметить без видео",
                ) { onManual() }
            }
            ActionButton(p, R.drawable.ic_plus, if (busy) "добавляю…" else "Добавить видео") {
                if (!busy) picker.launch(arrayOf("video/*"))
            }
        }
    }
}

/** одинаковая кнопка во всю ширину: иконка в рамке фиксированного размера, текст в одну строку */
@Composable
private fun ActionButton(p: Palette, icon: Int, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp))
            .background(p.tx.copy(alpha = 0.06f)).press(onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Image(painterResource(icon), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(p.ac))
        }
        Spacer(Modifier.width(10.dp))
        BasicText(
            text, maxLines = 1, softWrap = false,
            style = TextStyle(color = p.tx, fontSize = 16.sp, fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun Thumb(v: Video, mu: Color, modifier: Modifier) {
    val ctx = LocalContext.current
    var bmp by remember(v.id) { mutableStateOf(Thumbs.cached(v.id)) }
    LaunchedEffect(v.id) {
        if (bmp == null) bmp = withContext(Dispatchers.IO) { Thumbs.load(ctx, v) }
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(mu.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Box(Modifier.size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_play), null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(Color.White))
        }
    }
}
