package com.aura.agent.agent

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aura.agent.App
import com.aura.agent.MainActivity
import com.aura.agent.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** Keeps agent runs alive in the background (and after the app is swiped away). */
class AgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as App
        if (intent?.action == ACTION_STOP) {
            app.runner.stopAll()
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Aura is working", ""), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        acquireWake()
        watcher?.cancel()
        watcher = scope.launch {
            // after a process restart nothing is running yet: ask the runner to resume unfinished chats
            if (intent == null || intent.getBooleanExtra(EXTRA_RESUME, false)) withContext(Dispatchers.IO) { app.runner.resumeAll() }
            var seen = app.runner.active.value.isNotEmpty()
            combine(app.runner.active, app.runner.status) { a, s -> a to s }.collect { (active, status) ->
                if (active.isNotEmpty()) {
                    seen = true
                    val n = buildNotification(if (status.isBlank()) "Aura is working" else status, if (active.size > 1) "${active.size} chats running" else "")
                    getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, n)
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    releaseWake()
                    stopSelf()
                    this.cancel()
                }
            }
        }
        return START_STICKY
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, AgentService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, App.CH_RUN)
            .setSmallIcon(R.drawable.ic_sparkle)
            .setContentTitle(title)
            .setContentText(text.ifBlank { "Running in the background" })
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setProgress(0, 0, true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun acquireWake() {
        if (wake?.isHeld == true) return
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aura:agent").apply { acquire(2 * 60 * 60 * 1000L) }
    }

    private fun releaseWake() { runCatching { if (wake?.isHeld == true) wake?.release() } }

    override fun onDestroy() {
        releaseWake(); scope.cancel(); super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 42
        const val ACTION_STOP = "com.aura.agent.STOP"
        const val EXTRA_RESUME = "resume"
        fun start(ctx: Context, resume: Boolean = false) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, AgentService::class.java).putExtra(EXTRA_RESUME, resume)) }
        }
    }
}
