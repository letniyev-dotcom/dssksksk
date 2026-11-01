package app.diary.daily

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Постоянный счётчик шагов: живёт в фоне с тихим уведомлением, чтобы шаги считались всегда. */
class StepsService : Service() {
    private lateinit var sm: SensorManager
    private var lastShown = -1
    private var listening = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val n = Steps.onReading(applicationContext, e.values[0].toLong())
            Store.syncSteps(applicationContext, n)
            if (n != lastShown && (lastShown < 0 || n - lastShown >= 20 || n >= Steps.GOAL && lastShown < Steps.GOAL)) {
                lastShown = n
                getSystemService(NotificationManager::class.java).notify(ID, build(n))
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    override fun onCreate() {
        super.onCreate()
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CH, "Шаги", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Steps.today(this)
        lastShown = n
        val note = build(n)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            } else startForeground(ID, note)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor == null || !Steps.hasPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!listening) {
            // последний параметр: пусть система копит шаги и отдаёт пачкой, это экономит батарею
            listening = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (listening) sm.unregisterListener(listener)
        listening = false
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun build(steps: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_steps)
            .setContentTitle("${steps.toString().reversed().chunked(3).joinToString(" ").reversed()} из ${Steps.GOAL / 1000} 000 шагов")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val ID = 7
        private const val CH = "steps"

        fun start(c: Context) {
            if (!Steps.hasSensor(c) || !Steps.hasPermission(c)) return
            try { ContextCompat.startForegroundService(c, Intent(c, StepsService::class.java)) } catch (e: Exception) { }
        }
    }
}

/** После перезагрузки телефона и обновления приложения счёт запускается сам. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        StepsService.start(c)
        if (Steps.hasSensor(c)) StepsWorker.schedule(c)
    }
}
