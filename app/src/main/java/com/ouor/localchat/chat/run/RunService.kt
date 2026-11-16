package com.ouor.localchat.chat.run

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ouor.localchat.R
import com.ouor.localchat.core.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * dataSync foreground service that keeps the process alive while any run is active and shows the
 * "generating replies" notification. No chat logic lives here (architecture 5.6).
 */
@AndroidEntryPoint
class RunService : androidx.lifecycle.LifecycleService() {

    @Inject lateinit var registry: RunRegistry

    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.chat_run_channel), NotificationManager.IMPORTANCE_LOW),
        )
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(registry.runs.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        lifecycleScope.launch {
            registry.runs.collect { runs ->
                if (runs.isEmpty()) stopSelf() else manager.notify(NOTIFICATION_ID, notification(runs))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) appScope.launch { registry.stopAll() }
        return START_NOT_STICKY
    }

    /** Android 15+ dataSync time limit: stop the runs (saved as STOPPED) and the service. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        appScope.launch { registry.stopAll() }
        stopSelf()
    }

    private fun notification(runs: Map<String, RunState>): Notification {
        val attention = runs.values.firstOrNull {
            val pause = (it.phase as? RunPhase.Paused)?.pause
            pause != null && pause !is Pause.UserControl
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)!!.apply {
            (attention ?: runs.values.firstOrNull())?.let { putExtra(EXTRA_SESSION_ID, it.sessionId) }
        }
        val stop = Intent(this, RunService::class.java).setAction(ACTION_STOP)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(
                if (attention != null) getString(R.string.chat_run_needs_attention)
                else resources.getQuantityString(R.plurals.chat_run_generating, runs.size, runs.size),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(0, getString(R.string.chat_run_stop), PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    companion object {
        /** Notification tap: the session to open (ui reads it from the launch intent). */
        const val EXTRA_SESSION_ID = "com.ouor.localchat.extra.SESSION_ID"
        private const val ACTION_STOP = "com.ouor.localchat.action.STOP_RUNS"
        private const val CHANNEL = "runs"
        private const val NOTIFICATION_ID = 1
    }
}

/** Starts [RunService] whenever runs appear; the service stops itself when they are gone. */
@Singleton
class RunServiceController @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val registry: RunRegistry,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            var wasEmpty = true
            registry.runs.collect { runs ->
                // Runs start from a user action, so the app is in the foreground here.
                if (wasEmpty && runs.isNotEmpty()) ContextCompat.startForegroundService(context, Intent(context, RunService::class.java))
                wasEmpty = runs.isEmpty()
            }
        }
    }
}
