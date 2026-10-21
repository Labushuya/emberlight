package io.github.labushuya.emberlight.feature.space.retention

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.retention.PurgeResult
import io.github.labushuya.emberlight.share.space.retention.RemoveResult
import io.github.labushuya.emberlight.share.space.retention.RetentionChange
import io.github.labushuya.emberlight.share.space.retention.RetentionRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plant die Erinnerungen zur Aufbewahrung (Paket 3.6b, ADR 0021) und löst die automatische Löschung aus (Paket 3.6c).
 * Ein inexakter Wecker (AlarmManager, ohne Play Services) weckt den Empfänger zum nächsten geplanten Zeitpunkt, auch zum
 * Ende einer Aufbewahrung. Ein WorkManager-Lauf (siehe [RetentionWork]) prüft zusätzlich stündlich. Beim Start der App
 * wird alles nachgeholt: unterbrochene Löschungen, fällige Löschungen und verpasste Erinnerungen. Jede Erinnerung wird je
 * geplantem Zeitpunkt höchstens einmal gesendet.
 *
 * [checkNow] läuft nie doppelt gleichzeitig: Wecker, WorkManager, Start und Liste rufen es auf, und ein paralleler
 * Lauf könnte dieselbe Erinnerung zweimal senden.
 */
@Singleton
class RetentionReminders
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: RetentionRepository,
        private val notifier: RetentionNotifier,
    ) {
        private val checkLock = Mutex()

        /**
         * Start der App: unterbrochene Löschungen fortsetzen, fällige Löschungen ausführen, verpasste Erinnerungen nachholen,
         * den Wecker neu setzen und den periodischen Lauf einplanen.
         */
        suspend fun startupCatchUp() {
            repository.resumePurges()
            checkNow(System.currentTimeMillis())
            RetentionWork.schedule(context)
        }

        /**
         * Prüft jetzt: löscht abgelaufene Aufbewahrungen (Paket 3.6c), sendet fällige Erinnerungen und plant den nächsten
         * Wecker. Die Löschung kommt vor der Planung, damit für einen gelöschten Space keine Erinnerung mehr geht.
         */
        suspend fun checkNow(now: Long) {
            checkLock.withLock {
                repository.purgeDue(now).forEach { notifier.cancel(it) }
                val plan = repository.plan(now)
                plan.due.forEach { reminder ->
                    // Ohne Berechtigung bleibt die Erinnerung offen. Die Liste und das Detail zeigen den Hinweis.
                    if (notifier.post(reminder)) repository.markReminded(reminder.spaceId, reminder.plannedAt)
                }
                scheduleAt(plan.nextCheckAt)
            }
        }

        /** Ob Erinnerungen gezeigt werden dürfen. Ohne Berechtigung zeigt die App den Hinweis im Detail. */
        fun notificationsAllowed(): Boolean = notifier.canNotify()

        /** Neue Frist aus dem Detail. Eine Änderung plant die Erinnerung neu. */
        suspend fun changeRetention(
            spaceId: SpaceId,
            days: Int,
            now: Long,
        ): RetentionChange {
            val result = repository.changeUntil(spaceId, days, now)
            if (result is RetentionChange.Changed) notifier.cancel(spaceId)
            checkNow(now)
            return result
        }

        /** „1 Tag länger aufbewahren“ aus der Benachrichtigung. Löscht nichts. */
        suspend fun extendByOneDay(
            spaceId: SpaceId,
            now: Long,
        ) {
            val result = repository.extendByOneDay(spaceId, now)
            if (result is RetentionChange.Changed) notifier.cancel(spaceId)
            checkNow(now)
        }

        /** Löscht einen Space nach der Bestätigung im Detail. Gibt das Ergebnis zurück. */
        suspend fun purge(spaceId: SpaceId): PurgeResult {
            val result = repository.purge(spaceId)
            notifier.cancel(spaceId)
            return result
        }

        /** Entfernt einen gelöschten Space aus der Liste, samt Titelbild (Paket 3.6c). */
        suspend fun removeFromList(spaceId: SpaceId): RemoveResult = repository.remove(spaceId)

        /** Setzt den Wecker auf [atMillis], oder hebt ihn auf bei `null`. Ein inexakter Wecker reicht für Erinnerungen. */
        fun scheduleAt(atMillis: Long?) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            alarms.cancel(checkIntent())
            if (atMillis != null) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, checkIntent())
            }
        }

        private fun checkIntent(): PendingIntent {
            val intent =
                Intent(context, RetentionReceiver::class.java).setAction(RetentionReceiver.ACTION_CHECK)
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CHECK,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private companion object {
            const val REQUEST_CHECK = 0x5245_5443
        }
    }
