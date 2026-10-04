package de.uvsight.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import de.uvsight.core.tr
import de.uvsight.core.AppState
import de.uvsight.core.ConnState
import de.uvsight.core.fmt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service: keeps the Bluetooth link alive while the app is in the background and
 * shows the running session (end, arrows, average) as an ongoing notification.
 */
class SightService : Service() {
    companion object {
        const val CHANNEL = "session"
        const val NOTIF_ID = 1
    }

    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Running session"), NotificationManager.IMPORTANCE_LOW).apply {
            description = tr("Shows the connection to the sight and the running session")
            setShowBadge(false)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rt = SightRuntime.get(this)
        ServiceCompat.startForeground(this, NOTIF_ID, build(rt.controller.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (job == null) job = rt.scope.launch {
            rt.controller.state.map { key(it) }.distinctUntilChanged().collect {
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(rt.controller.state.value))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel(); job = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** Only these parts of the state change the notification. */
    private fun key(s: AppState): List<Any?> = listOf(s.conn, s.autoReconnect, s.sightAsleep, s.status?.pct, s.session?.active, s.sight?.label,
        s.session?.end, s.session?.endShots, s.session?.ends, s.session?.avg, s.session?.x, s.distM, s.distAuto)

    private fun build(s: AppState): Notification {
        val ses = s.session?.takeIf { it.active }
        val title: String
        val text: String
        val view: String
        when {
            ses != null -> {
                title = tr("End {end}, arrow {arrow}", "end" to ses.end, "arrow" to ses.endShots)
                text = tr("{ends} {endsWord}, average {avg}, {x} X", "ends" to ses.ends, "endsWord" to (if (ses.ends == 1) tr("end") else tr("ends")), "avg" to fmt(ses.avg, 2), "x" to ses.x) +
                    (if (s.distM > 0) ", ${s.distM} m" else "") +
                    (if (s.conn != ConnState.CONNECTED) "  ·  " + tr("sight out of reach, session continues") else "")
                view = "training"
            }
            s.conn == ConnState.CONNECTED -> { title = tr("Connected to {sight}", "sight" to (s.sight?.label ?: tr("the sight"))); text = s.status?.let { tr("Battery {pct} %", "pct" to it.pct) } ?: tr("Waiting for data"); view = "status" }
            s.autoReconnect -> { title = tr("Reconnecting to the sight"); text = tr("Move the bow to wake it"); view = "status" }
            else -> { title = tr("Connecting to the sight"); text = ""; view = "status" }
        }
        val open = Intent(this, MainActivity::class.java).apply { putExtra("view", view); flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (ses != null) b.addAction(0, tr("Enter scores"), pi)
        return b.build()
    }
}
