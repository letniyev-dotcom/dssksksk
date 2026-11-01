package app.diary.daily

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** раз в ~15 минут снимает показание датчика, чтобы шаги считались и при закрытом приложении */
class StepsWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Steps.hasPermission(ctx)) return Result.success()
        val counter = Steps.readOnce(ctx) ?: return Result.success()
        val n = Steps.onReading(ctx, counter)
        Store.syncSteps(ctx, n)
        return Result.success()
    }

    companion object {
        fun schedule(c: Context) {
            val req = PeriodicWorkRequestBuilder<StepsWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(c)
                .enqueueUniquePeriodicWork("steps", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
