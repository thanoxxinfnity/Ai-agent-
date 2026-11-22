package com.aura.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import com.aura.agent.agent.AgentRunner
import com.aura.agent.data.AppDb
import com.aura.agent.data.Store
import com.aura.agent.net.Supabase

class App : Application() {
    lateinit var db: AppDb
    lateinit var store: Store
    lateinit var runner: AgentRunner
    lateinit var supabase: Supabase
    @Volatile var foreground = false

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, AppDb::class.java, "aura.db").fallbackToDestructiveMigration().build()
        store = Store(this)
        supabase = Supabase(store)
        runner = AgentRunner(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RUN, "Background work", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Finished replies", NotificationManager.IMPORTANCE_DEFAULT))
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { foreground = true }
            override fun onStop(owner: LifecycleOwner) { foreground = false }
        })
    }

    fun notifyDone(chatId: String, title: String, text: String) {
        if (foreground) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = Intent(this, MainActivity::class.java).putExtra("chatId", chatId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(this, chatId.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_sparkle)
            .setContentTitle(title.ifBlank { "Aura" })
            .setContentText(text.replace('\n', ' ').take(140))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(600)))
            .setContentIntent(pi).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(chatId.hashCode(), n)
    }

    companion object {
        const val CH_RUN = "run"
        const val CH_DONE = "done"
    }
}
