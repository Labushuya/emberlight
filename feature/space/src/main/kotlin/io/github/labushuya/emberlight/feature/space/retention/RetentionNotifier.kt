package io.github.labushuya.emberlight.feature.space.retention

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.labushuya.emberlight.feature.space.R
import io.github.labushuya.emberlight.share.server.SpaceServerService
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.retention.RetentionReminder
import io.github.labushuya.emberlight.share.space.retention.RetentionReminderKind
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Benachrichtigungen zur Aufbewahrung (Paket 3.6b, ADR 0021). Eigener Kanal, nicht der Kanal der Vorwarnung. Die Aktionen:
 * „Ansehen“ öffnet das Detail, „1 Tag länger aufbewahren“ verlängert ohne Löschung, „Jetzt löschen“ öffnet die App mit
 * Bestätigung. Vor dem Ende eines Space gibt es nur „Ansehen“.
 */
@Singleton
class RetentionNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** Darf der Space überhaupt eine Meldung zeigen? Ohne Berechtigung bleibt nur der Hinweis in der App. */
        fun canNotify(): Boolean =
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                )

        /** Zeigt die Erinnerung. `false`, wenn keine Berechtigung besteht. Dann bleibt sie ungesendet. */
        @SuppressLint("MissingPermission")
        fun post(
            reminder: RetentionReminder,
            locale: java.util.Locale = java.util.Locale.getDefault(),
        ): Boolean {
            if (!canNotify()) return false
            createChannel()
            val id = reminder.spaceId
            val name = reminder.name
            val text =
                when (reminder.kind) {
                    RetentionReminderKind.SOFORT -> {
                        context.getString(R.string.space_retention_notice_sofort)
                    }

                    RetentionReminderKind.AFTER_END -> {
                        context.getString(
                            R.string.space_retention_notice_text,
                            reminder.untilMillis?.let { dateText(it, locale) } ?: "",
                        )
                    }
                }
            val builder =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle(context.getString(R.string.space_retention_notice_title, name))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
            activityIntent(RetentionRoutes.open(id.value), requestFor(RetentionRoutes.open(id.value)))?.let {
                builder.setContentIntent(it)
                builder.addAction(0, context.getString(R.string.space_retention_action_view), it)
            }
            if (reminder.ended) {
                builder.addAction(
                    0,
                    context.getString(R.string.space_retention_action_extend),
                    broadcastIntent(id, requestFor(ACTION_EXTEND + id.value)),
                )
                activityIntent(RetentionRoutes.purge(id.value), requestFor(RetentionRoutes.purge(id.value)))?.let {
                    builder.addAction(0, context.getString(R.string.space_retention_action_purge), it)
                }
            }
            NotificationManagerCompat.from(context).notify(notificationIdOf(id), builder.build())
            return true
        }

        /** Entfernt die Meldung eines Space, etwa nach einer Verlängerung. */
        fun cancel(spaceId: SpaceId) {
            NotificationManagerCompat.from(context).cancel(notificationIdOf(spaceId))
        }

        private fun createChannel() {
            NotificationManagerCompat.from(context).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.space_retention_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }

        private fun dateText(
            millis: Long,
            locale: java.util.Locale,
        ): String = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(millis))

        private fun activityIntent(
            route: String,
            requestCode: Int,
        ): PendingIntent? {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
            launch.putExtra(SpaceServerService.EXTRA_ROUTE, route)
            launch.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            return PendingIntent.getActivity(
                context,
                requestCode,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun broadcastIntent(
            spaceId: SpaceId,
            requestCode: Int,
        ): PendingIntent {
            val intent =
                Intent(context, RetentionReceiver::class.java)
                    .setAction(ACTION_EXTEND)
                    .putExtra(EXTRA_SPACE_ID, spaceId.value)
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        companion object {
            const val CHANNEL_ID = "space_retention"
            const val ACTION_EXTEND = "io.github.labushuya.emberlight.retention.EXTEND"
            const val EXTRA_SPACE_ID = "io.github.labushuya.emberlight.retention.SPACE_ID"
            private const val NOTIFICATION_BASE = 5100

            /** Eine Meldung je Space. Eine neue Erinnerung ersetzt die alte, nie zwei nebeneinander. */
            fun notificationIdOf(spaceId: SpaceId): Int = NOTIFICATION_BASE + (spaceId.value.hashCode() and 0x0FFF)

            private fun requestFor(key: String): Int = key.hashCode()
        }
    }
