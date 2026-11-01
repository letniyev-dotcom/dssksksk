package app.diary.daily

import android.Manifest
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DiaryApp() }
    }
}

@Composable
fun DiaryApp() {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val p = palette(isSystemInDarkTheme())

    var st by remember { mutableStateOf(Store.load(ctx)) }
    var steps by remember { mutableIntStateOf(Steps.today(ctx)) }
    val hasSensor = remember { Steps.hasSensor(ctx) }
    var perm by remember { mutableStateOf(Steps.hasPermission(ctx)) }
    var section by rememberSaveable { mutableStateOf<String?>(null) } // "face" / "gym"
    var playId by rememberSaveable { mutableStateOf<String?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { perm = it }

    // первый запуск: спрашиваем доступ к шагам один раз
    LaunchedEffect(Unit) {
        if (hasSensor && !perm && !Steps.asked(ctx)) {
            Steps.setAsked(ctx)
            permLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(perm) {
        if (perm && hasSensor) {
            StepsWorker.schedule(ctx)
            StepsService.start(ctx)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // шаги: живой счёт, пока приложение на экране
    DisposableEffect(owner, perm) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val n = Steps.onReading(ctx, e.values[0].toLong())
                steps = n
                st = Store.syncSteps(ctx, n)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        var on = false
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> {
                    st = Store.load(ctx)
                    perm = Steps.hasPermission(ctx)
                    steps = Steps.today(ctx)
                    if (perm && sensor != null && !on) {
                        on = sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
                    }
                }
                Lifecycle.Event.ON_PAUSE -> if (on) { sm.unregisterListener(l); on = false }
                else -> {}
            }
            Unit
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            if (on) sm.unregisterListener(l)
        }
    }

    val playing = remember(playId) { playId?.let { id -> Videos.list(ctx).firstOrNull { it.id == id } } }

    // состав подхода упражнений и что из него уже просмотрено (фиксируем на момент открытия видео)
    val playSet = remember(playId) { Videos.list(ctx).filter { it.kind == "face" }.map { it.id }.toSet() }
    val playSess = remember(playId) { Videos.session(ctx) }

    BackHandler(enabled = playing == null && section != null) { section = null }

    when {
        playing != null -> PlayerScreen(
            p = p,
            video = playing,
            countNow = st.v[playing.kind] ?: 0,
            limit = limitOf(playing.kind),
            total = if (playing.kind == "gym") 1 else playSet.size,
            got = if (playing.kind == "gym") 0 else playSess.count { it in playSet },
            dup = playing.kind != "gym" && playing.id in playSess,
            onCounted = {
                st = Store.videoDone(ctx, playing.kind, playing.id)
                st.v[playing.kind] ?: 0
            },
            onClose = { playId = null },
        )
        section != null -> FaceScreen(
            p = p,
            kind = section!!,
            count = st.v[section!!] ?: 0,
            onBack = { section = null },
            onPlay = { playId = it.id },
            onManual = if (section == "gym") ({ st = Store.tap(ctx, "gym") }) else null,
        )
        else -> TodayScreen(
            p = p,
            st = st,
            steps = steps,
            hasSensor = hasSensor,
            hasPerm = perm,
            onFace = { section = it },
            onTap = { id, n -> st = Store.tap(ctx, id, n) },
            onStepsTap = {
                if (!perm) {
                    if (Steps.asked(ctx)) {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                        )
                    } else {
                        Steps.setAsked(ctx)
                        permLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    }
                }
            },
        )
    }
}

@Composable
fun TodayScreen(
    p: Palette,
    st: DayState,
    steps: Int,
    hasSensor: Boolean,
    hasPerm: Boolean,
    onFace: (String) -> Unit,
    onTap: (String, Int) -> Unit,
    onStepsTap: () -> Unit,
) {
    val bg = p.bg; val tx = p.tx; val mu = p.mu; val ac = p.ac

    val date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("ru")))
    val note = if (allDone(st)) "день засчитан, так держать" else "отметь всё важное, и день войдёт в серию"

    Column(
        Modifier.fillMaxSize().background(bg).systemBarsPadding().padding(horizontal = 28.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        BasicText(date, style = TextStyle(color = mu, fontSize = 14.sp))
        BasicText("Сегодня", style = TextStyle(color = tx, fontSize = 28.sp, fontWeight = FontWeight.SemiBold))
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Bottom) {
            BasicText("${st.streak}", style = TextStyle(color = ac, fontSize = 64.sp, fontWeight = FontWeight.Light))
            BasicText(
                "дней подряд",
                style = TextStyle(color = mu, fontSize = 15.sp),
                modifier = Modifier.padding(start = 10.dp, bottom = 12.dp),
            )
        }
        BasicText(note, style = TextStyle(color = mu, fontSize = 14.sp))
        Spacer(Modifier.height(8.dp))

        Column(Modifier.weight(1f).fillMaxWidth().padding(bottom = 12.dp)) {
            ITEMS.forEach { item ->
                val c = st.v[item.id] ?: 0
                val on = if (item.id == "water") c >= 8 else c >= 1
                val iconColor by animateColorAsState(if (on) ac else mu, label = "icon")
                val titleColor by animateColorAsState(if (on) tx.copy(alpha = 0.4f) else tx, label = "title")
                val isWater = item.id == "water"

                val hint = when (item.id) {
                    "steps" -> when {
                        !hasSensor -> "в телефоне нет датчика шагов"
                        !hasPerm -> "разреши доступ к шагам"
                        else -> "${fmtNum(steps)} из ${fmtNum(Steps.GOAL)}"
                    }
                    else -> item.hint
                }

                Column(
                    Modifier.weight(if (isWater) 1.7f else 1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(
                        Modifier.fillMaxWidth().then(
                            when (item.id) {
                                "water" -> Modifier
                                "face", "gym" -> Modifier.press { onFace(item.id) }
                                "steps" -> Modifier.press { onStepsTap() }
                                else -> Modifier.press { onTap(item.id, 0) }
                            }
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            painterResource(item.icon), null,
                            Modifier.size(26.dp), colorFilter = ColorFilter.tint(iconColor),
                        )
                        Spacer(Modifier.width(18.dp))
                        Column(Modifier.weight(1f)) {
                            BasicText(
                                item.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = titleColor, fontSize = 17.sp, fontWeight = FontWeight.Medium),
                            )
                            BasicText(hint, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = mu, fontSize = 13.sp))
                        }
                        when (item.id) {
                            "face" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (k in 1..FACE_PER_DAY) Box(
                                    Modifier.size(9.dp).clip(CircleShape)
                                        .background(if (k <= c) ac else mu.copy(alpha = 0.35f))
                                )
                            }
                            "water" -> BasicText("$c из 8", style = TextStyle(color = if (on) ac else mu, fontSize = 14.sp))
                            "steps" -> if (on) Image(
                                painterResource(R.drawable.ic_check), null,
                                Modifier.size(22.dp), colorFilter = ColorFilter.tint(ac),
                            ) else if (hasSensor && hasPerm) BasicText(
                                "${minOf(99, steps * 100 / Steps.GOAL)}%",
                                style = TextStyle(color = mu, fontSize = 14.sp),
                            )
                            else -> if (on) Image(
                                painterResource(R.drawable.ic_check), null,
                                Modifier.size(22.dp), colorFilter = ColorFilter.tint(ac),
                            )
                        }
                    }
                    if (isWater) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier.fillMaxWidth().padding(start = 44.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            for (k in 1..8) {
                                val full = k <= c
                                Image(
                                    painterResource(if (full) R.drawable.ic_glass_full else R.drawable.ic_glass),
                                    null,
                                    Modifier.size(28.dp).press { onTap("water", k) },
                                    colorFilter = ColorFilter.tint(if (full) ac else mu),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
