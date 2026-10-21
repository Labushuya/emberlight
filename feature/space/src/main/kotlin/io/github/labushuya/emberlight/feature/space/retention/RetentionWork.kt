package io.github.labushuya.emberlight.feature.space.retention

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Der periodische Lauf der Aufbewahrung (Paket 3.6c, ADR 0021). WorkManager (AndroidX, ohne Play Services) prüft stündlich,
 * ob Aufbewahrungen abgelaufen sind und Erinnerungen fällig sind. Er ist das Sicherheitsnetz neben dem wecker-gesteuerten
 * Prüfen. Die Systemplanung verschiebt ihn, das ist bei einer Frist in Tagen unkritisch.
 */
class RetentionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        RetentionWork.check(applicationContext)
        return Result.success()
    }
}

/** Einplanung und Ausführung des periodischen Laufs. */
object RetentionWork {
    /** Name der eindeutigen Arbeit. Ein erneutes Einplanen lässt die bestehende Arbeit unverändert. */
    const val UNIQUE_NAME = "emberlight.retention-check"

    /** Abstand des periodischen Laufs. WorkManager verlangt mindestens 15 Minuten. */
    const val INTERVAL_HOURS = 1L

    /**
     * Die Prüfung selbst. Die App nutzt die Hilt-Bindung von [RetentionReminders]. Tests ersetzen sie durch eine Fake-Uhr.
     */
    @Volatile
    var check: suspend (Context) -> Unit = { context ->
        EntryPointAccessors
            .fromApplication(context.applicationContext, RetentionEntryPoint::class.java)
            .reminders()
            .checkNow(System.currentTimeMillis())
    }

    /** Plant den periodischen Lauf ein. Ist er schon eingeplant, bleibt er unverändert. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<RetentionWorker>(INTERVAL_HOURS, TimeUnit.HOURS).build()
        try {
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        } catch (notReady: IllegalStateException) {
            // WorkManager ist in manchen Testumgebungen nicht eingerichtet. Der Lauf ist nur ein Sicherheitsnetz: Start,
            // Liste und Wecker decken die Aufbewahrung weiterhin ab, und der nächste Prozessstart plant erneut ein.
        }
    }
}

/** Einstieg aus dem Worker in die Hilt-Verdrahtung. Der Worker selbst wird nicht per Hilt erzeugt. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RetentionEntryPoint {
    fun reminders(): RetentionReminders
}
