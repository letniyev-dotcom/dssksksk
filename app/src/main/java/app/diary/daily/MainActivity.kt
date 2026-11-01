package app.diary.daily

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DiaryScreen() }
    }
}

// ---------- данные ----------

data class Item(val id: String, val title: String, val hint: String, val icon: Int)

val ITEMS = listOf(
    Item("face", "Упражнения для лица", "до двух раз за день", R.drawable.ic_face),
    Item("run", "Бег", "пробежка в своё удовольствие", R.drawable.ic_run),
    Item("steps", "15 тысяч шагов", "гуляй сколько хочется", R.drawable.ic_steps),
    Item("gym", "Тренировка", "по желанию, без давления", R.drawable.ic_gym),
    Item("water", "Вода", "8 стаканов за день", R.drawable.ic_water),
    Item("food", "Правильное питание", "ешь вкусно и без спешки", R.drawable.ic_food),
    Item("sugar", "Без сахара", "сладкое подождёт до завтра", R.drawable.ic_sugar),
    Item("sleep", "Лечь до 22:00", "ранний сон — подарок себе", R.drawable.ic_sleep),
)

data class DayState(
    val date: String,
    val v: Map<String, Int>,
    val streak: Int,
    val last: String,
    val prev: String,
)

private fun need(id: String) = if (id == "water") 8 else 1

fun allDone(s: DayState) = ITEMS.all { it.id == "gym" || (s.v[it.id] ?: 0) >= need(it.id) }

object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("diary", Context.MODE_PRIVATE)

    fun load(c: Context): DayState {
        val p = prefs(c)
        val today = LocalDate.now().toString()
        val map = (p.getString("v", "") ?: "").split(",").filter { it.contains("=") }
            .associate { it.substringBefore("=") to (it.substringAfter("=").toIntOrNull() ?: 0) }
        var s = DayState(
            p.getString("date", "") ?: "", map, p.getInt("streak", 0),
            p.getString("last", "") ?: "", p.getString("prev", "") ?: "",
        )
        if (s.date != today) {
            val yesterday = LocalDate.now().minusDays(1).toString()
            val streak = if (s.last == yesterday || s.last == today) s.streak else 0
            s = DayState(today, emptyMap(), streak, s.last, s.prev)
            save(c, s)
        }
        return s
    }

    fun save(c: Context, s: DayState) {
        prefs(c).edit()
            .putString("date", s.date)
            .putString("v", s.v.entries.joinToString(",") { "${it.key}=${it.value}" })
            .putInt("streak", s.streak)
            .putString("last", s.last)
            .putString("prev", s.prev)
            .apply()
    }

    fun tap(c: Context, id: String, n: Int = 0): DayState {
        val base = load(c)
        val cur = base.v[id] ?: 0
        val next = when (id) {
            "water" -> if (n == cur) n - 1 else n
            "face" -> if (cur >= 2) 0 else cur + 1
            else -> if (cur > 0) 0 else 1
        }
        var s = base.copy(v = base.v + (id to next))
        val done = allDone(s)
        if (done && s.last != s.date) {
            s = s.copy(prev = s.last, last = s.date, streak = s.streak + 1)
        } else if (!done && s.last == s.date) {
            s = s.copy(last = s.prev, streak = maxOf(0, s.streak - 1))
        }
        save(c, s)
        return s
    }
}

// ---------- интерфейс ----------

@Composable
private fun Modifier.press(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )

@Composable
fun DiaryScreen() {
    val ctx = LocalContext.current
    var st by remember { mutableStateOf(Store.load(ctx)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) st = Store.load(ctx) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val dark = isSystemInDarkTheme()
    val bg = if (dark) Color(0xFF141311) else Color(0xFFFAF8F5)
    val tx = if (dark) Color(0xFFF1EDE7) else Color(0xFF1D1B19)
    val mu = if (dark) Color(0xFF6B665F) else Color(0xFFB9B3AB)
    val ac = if (dark) Color(0xFFFF8A6B) else Color(0xFFE0674A)

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

                Column(
                    Modifier.weight(if (isWater) 1.7f else 1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(
                        Modifier.fillMaxWidth().then(
                            if (isWater) Modifier else Modifier.press { st = Store.tap(ctx, item.id) }
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
                            BasicText(item.hint, style = TextStyle(color = mu, fontSize = 13.sp))
                        }
                        when (item.id) {
                            "face" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (k in 1..2) Box(
                                    Modifier.size(9.dp).clip(CircleShape)
                                        .background(if (k <= c) ac else mu.copy(alpha = 0.35f))
                                )
                            }
                            "water" -> BasicText("$c из 8", style = TextStyle(color = if (on) ac else mu, fontSize = 14.sp))
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
                                    Modifier.size(28.dp).press { st = Store.tap(ctx, "water", k) },
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
