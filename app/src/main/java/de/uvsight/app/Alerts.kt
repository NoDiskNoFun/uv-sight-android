package de.uvsight.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import de.uvsight.core.CoreEvent
import de.uvsight.core.SightController
import de.uvsight.core.fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One-off notifications: session ended by the sight, battery low, charging finished. */
class Alerts(private val context: Context, private val controller: SightController, scope: CoroutineScope) {
    companion object {
        const val CHANNEL = "alerts"
        const val ID_AUTO_END = 10
        const val ID_LOW_BAT = 11
        const val ID_CHARGED = 12
    }

    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Sight alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Session ended by the sight, battery low, charging finished"
            })
        scope.launch {
            controller.events.collect { e ->
                val p = controller.state.value.notif
                when (e) {
                    is CoreEvent.SessionAutoEnded -> if (p.autoEnd) show(ID_AUTO_END, "Session ended by the sight",
                        "${e.ends} ${if (e.ends == 1) "end" else "ends"}, ${e.scored} arrows, average ${fmt(e.avg, 2)}, ${e.x} X, ${e.min} min. No shot for a while.", "history")
                    is CoreEvent.LowBattery -> if (p.lowBat) show(ID_LOW_BAT, "Sight battery low", "${e.pct} % left. The UV light switches off when the battery is empty.", "status")
                    CoreEvent.ChargeFull -> if (p.chargeFull) show(ID_CHARGED, "Sight fully charged", "You can unplug the USB cable.", "status")
                }
            }
        }
    }

    private fun show(id: Int, title: String, text: String, view: String) {
        val open = Intent(context, MainActivity::class.java).apply { putExtra("view", view); flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pi = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, n) }   // SecurityException without POST_NOTIFICATIONS
    }
}
