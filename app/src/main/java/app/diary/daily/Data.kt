package app.diary.daily

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// ---------- день, серия ----------

data class Item(val id: String, val title: String, val hint: String, val icon: Int)

val ITEMS = listOf(
    Item("face", "Упражнения для лица", "утром и вечером: три видео подряд", R.drawable.ic_face),
    Item("run", "Бег", "пробежка в своё удовольствие", R.drawable.ic_run),
    Item("steps", "15 тысяч шагов", "считаются сами", R.drawable.ic_steps),
    Item("gym", "Тренировка", "по желанию, смотри видео", R.drawable.ic_gym),
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

const val FACE_PER_DAY = 2

fun limitOf(kind: String) = if (kind == "gym") 1 else FACE_PER_DAY

private fun need(id: String) = if (id == "water") 8 else 1

fun allDone(s: DayState) = ITEMS.all { it.id == "gym" || (s.v[it.id] ?: 0) >= need(it.id) }

object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("diary", Context.MODE_PRIVATE)

    @Synchronized
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

    /** записывает значение пункта и пересчитывает серию */
    private fun commit(c: Context, base: DayState, id: String, next: Int): DayState {
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

    @Synchronized
    fun tap(c: Context, id: String, n: Int = 0): DayState {
        val base = load(c)
        // лицо и шаги вручную не отмечаются: только просмотр видео и реальный счёт
        if (id == "face" || id == "steps") return base
        val cur = base.v[id] ?: 0
        val next = when (id) {
            "water" -> if (n == cur) n - 1 else n
            else -> if (cur > 0) 0 else 1
        }
        return commit(c, base, id, next)
    }

    /**
     * Досмотрено видео упражнений. Одно выполнение (утро или вечер) = все видео из списка,
     * каждое по разу. Когда набрался полный комплект, выполнение засчитывается (максимум два
     * за день) и комплект обнуляется для следующего подхода.
     */
    @Synchronized
    fun faceDone(c: Context, videoId: String): DayState {
        val base = load(c)
        Videos.markWatched(c, videoId)
        val cur = base.v["face"] ?: 0
        if (cur >= FACE_PER_DAY) return base
        val all = Videos.list(c).filter { it.kind == "face" }.map { it.id }.toSet()
        val got = Videos.sessionAdd(c, videoId)
        if (all.isNotEmpty() && !got.containsAll(all)) return base
        Videos.sessionReset(c)
        return commit(c, base, "face", cur + 1)
    }

    /** досмотрено видео тренировки: засчитывает «Тренировку» */
    @Synchronized
    fun gymDone(c: Context, videoId: String): DayState {
        val base = load(c)
        Videos.markWatched(c, videoId)
        if ((base.v["gym"] ?: 0) >= 1) return base
        return commit(c, base, "gym", 1)
    }

    fun videoDone(c: Context, kind: String, videoId: String): DayState =
        if (kind == "gym") gymDone(c, videoId) else faceDone(c, videoId)

    /** цель по шагам выполняется только по реальному счёту */
    @Synchronized
    fun syncSteps(c: Context, steps: Int): DayState {
        val base = load(c)
        val want = if (steps >= Steps.GOAL) 1 else 0
        if ((base.v["steps"] ?: 0) == want) return base
        return commit(c, base, "steps", want)
    }
}

// ---------- шаги ----------

object Steps {
    const val GOAL = 15000

    private fun prefs(c: Context) = c.getSharedPreferences("steps", Context.MODE_PRIVATE)

    fun hasSensor(c: Context): Boolean {
        val sm = c.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    }

    fun hasPermission(c: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    fun asked(c: Context) = prefs(c).getBoolean("asked", false)
    fun setAsked(c: Context) = prefs(c).edit().putBoolean("asked", true).apply()

    fun today(c: Context): Int {
        val p = prefs(c)
        return if (p.getString("date", "") == LocalDate.now().toString()) p.getInt("steps", 0) else 0
    }

    /**
     * Датчик отдаёт общее число шагов с перезагрузки телефона. Шаги за день копим по
     * разнице между показаниями. Если между последним показанием и полуночью прошло
     * больше часа, шаги этого промежутка не засчитываем: лучше недосчитать, чем
     * присвоить чужие вчерашние.
     */
    @Synchronized
    fun onReading(c: Context, counter: Long, nowMs: Long = System.currentTimeMillis()): Int {
        val p = prefs(c)
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val lastCounter = p.getLong("last", -1L)
        val lastTime = p.getLong("lastTime", 0L)
        val date = p.getString("date", "") ?: ""
        var steps = p.getInt("steps", 0)

        val delta = when {
            lastCounter < 0 -> 0L
            counter >= lastCounter -> counter - lastCounter
            else -> counter // телефон перезагружался, счёт пошёл с нуля
        }

        if (date == today.toString()) {
            steps += delta.toInt()
        } else {
            val lastDay = Instant.ofEpochMilli(lastTime).atZone(zone).toLocalDate()
            val midnight = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val closeToMidnight = lastDay == today.minusDays(1) && midnight - lastTime <= 60 * 60 * 1000L
            steps = if (lastCounter >= 0 && closeToMidnight) delta.toInt() else 0
        }

        p.edit()
            .putString("date", today.toString())
            .putInt("steps", steps)
            .putLong("last", counter)
            .putLong("lastTime", nowMs)
            .apply()
        return steps
    }

    /** одно показание датчика (для фоновой проверки) */
    suspend fun readOnce(c: Context): Long? {
        val sm = c.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        return withTimeoutOrNull(8000L) {
            suspendCancellableCoroutine<Long> { cont ->
                val l = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(e.values[0].toLong())
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_FASTEST)
                cont.invokeOnCancellation { sm.unregisterListener(l) }
            }
        }
    }
}

// ---------- видео ----------

data class Video(val id: String, val name: String, val file: String, val durMs: Long, val kind: String = "face")

object Videos {
    private fun prefs(c: Context) = c.getSharedPreferences("videos", Context.MODE_PRIVATE)

    fun file(c: Context, v: Video) = File(File(c.filesDir, "videos"), v.file)

    @Synchronized
    fun list(c: Context): List<Video> {
        val arr = try { JSONArray(prefs(c).getString("list", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            Video(o.getString("id"), o.getString("name"), o.getString("file"), o.optLong("dur", 0L), o.optString("kind", "face"))
        }.filter { file(c, it).exists() }
    }

    private fun saveList(c: Context, l: List<Video>) {
        val arr = JSONArray()
        l.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("file", it.file).put("dur", it.durMs).put("kind", it.kind))
        }
        prefs(c).edit().putString("list", arr.toString()).apply()
    }

    private fun displayName(c: Context, uri: Uri): String {
        var cur: Cursor? = null
        try {
            cur = c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cur != null && cur.moveToFirst()) return cur.getString(0) ?: "видео"
        } catch (e: Exception) {
        } finally {
            cur?.close()
        }
        return uri.lastPathSegment ?: "видео"
    }

    /** копирует видео в память приложения, чтобы оно не пропало вместе с исходным файлом */
    fun add(c: Context, uri: Uri, kind: String = "face"): Video? {
        val dir = File(c.filesDir, "videos").apply { mkdirs() }
        val raw = displayName(c, uri)
        val ext = raw.substringAfterLast('.', "mp4").lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "mp4" }
        val id = System.currentTimeMillis().toString() + (0..999).random()
        val f = File(dir, "$id.$ext")
        try {
            val input = c.contentResolver.openInputStream(uri) ?: return null
            input.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        } catch (e: Exception) {
            f.delete()
            return null
        }
        var dur = 0L
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            f.delete()
            return null
        } finally {
            r.release()
        }
        val v = Video(id, raw.substringBeforeLast('.'), f.name, dur, kind)
        synchronized(this) { saveList(c, list(c) + v) }
        return v
    }

    @Synchronized
    fun remove(c: Context, id: String) {
        val all = list(c)
        all.firstOrNull { it.id == id }?.let { file(c, it).delete() }
        saveList(c, all.filter { it.id != id })
        Thumbs.drop(id)
    }

    // просмотренные сегодня
    @Synchronized
    fun watchedToday(c: Context): Set<String> {
        val raw = prefs(c).getString("watched", "") ?: ""
        val today = LocalDate.now().toString()
        if (!raw.startsWith("$today:")) return emptySet()
        return raw.substringAfter(":").split(",").filter { it.isNotEmpty() }.toSet()
    }

    /** видео текущего (ещё не завершённого) подхода упражнений */
    @Synchronized
    fun session(c: Context): Set<String> {
        val raw = prefs(c).getString("session", "") ?: ""
        val today = LocalDate.now().toString()
        if (!raw.startsWith("$today:")) return emptySet()
        return raw.substringAfter(":").split(",").filter { it.isNotEmpty() }.toSet()
    }

    @Synchronized
    fun sessionAdd(c: Context, id: String): Set<String> {
        val s = session(c) + id
        prefs(c).edit().putString("session", "${LocalDate.now()}:${s.joinToString(",")}").apply()
        return s
    }

    @Synchronized
    fun sessionReset(c: Context) {
        prefs(c).edit().putString("session", "").apply()
    }

    @Synchronized
    fun markWatched(c: Context, id: String) {
        val s = watchedToday(c) + id
        prefs(c).edit().putString("watched", "${LocalDate.now()}:${s.joinToString(",")}").apply()
    }
}

object Thumbs {
    private val cache = HashMap<String, ImageBitmap>()

    @Synchronized fun cached(id: String): ImageBitmap? = cache[id]
    @Synchronized fun drop(id: String) { cache.remove(id) }

    fun load(c: Context, v: Video): ImageBitmap? {
        cached(v.id)?.let { return it }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(Videos.file(c, v).absolutePath)
            val at = minOf(1_000_000L, v.durMs * 500L) // мкс: секунда или середина короткого ролика
            val raw = r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val w = 360
            val h = maxOf(1, raw.height * w / raw.width)
            val bmp = Bitmap.createScaledBitmap(raw, w, h, true).asImageBitmap()
            synchronized(this) { cache[v.id] = bmp }
            bmp
        } catch (e: Exception) {
            null
        } finally {
            r.release()
        }
    }
}
