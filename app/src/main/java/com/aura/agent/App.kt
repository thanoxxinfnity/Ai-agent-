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
import com.aura.agent.agent.CloudSession
import com.aura.agent.data.AppDb
import com.aura.agent.data.Store
import com.aura.agent.net.Llm
import com.aura.agent.net.Supabase
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class App : Application() {
    lateinit var db: AppDb
    lateinit var store: Store
    lateinit var extras: com.aura.agent.data.Extras
    lateinit var mcp: com.aura.agent.agent.McpHub
    lateinit var runner: AgentRunner
    lateinit var supabase: Supabase
    lateinit var voice: com.aura.agent.agent.Voice
    var geminiBase = com.aura.agent.net.GeminiTts.BASE
    var vercelBase = "https://api.vercel.com"
    var vercelPollMs = 3000L
    @Volatile var foreground = false

    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                java.io.File(filesDir, "last_crash.txt").writeText(
                    "Thread: ${t.name}\nAndroid ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) ${android.os.Build.MODEL}\n\n" + android.util.Log.getStackTraceString(e)
                )
            }
            prev?.uncaughtException(t, e)
        }
        db = Room.databaseBuilder(this, AppDb::class.java, "aura.db").addMigrations(AppDb.MIGRATION_1_2, AppDb.MIGRATION_2_3, AppDb.MIGRATION_3_4, AppDb.MIGRATION_4_5).fallbackToDestructiveMigration().build()
        store = Store(this)
        extras = com.aura.agent.data.Extras(this)
        mcp = com.aura.agent.agent.McpHub(extras)
        supabase = Supabase(store)
        com.aura.agent.net.Llm.onWireLearned = { id, wire -> store.setWire(id, wire) }
        runner = AgentRunner(this)
        voice = com.aura.agent.agent.VoiceEngine(this, store, base = { geminiBase })
        appScope.launch { supabase.loadConfig() }
        appScope.launch { CloudSession.recover(store, ::codespacePat) }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RUN, "Background work", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Finished replies", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_ASK, "Approval requests", NotificationManager.IMPORTANCE_HIGH))
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { foreground = true; refreshModels(false) }
            override fun onStop(owner: LifecycleOwner) { foreground = false }
        })
    }

    fun takeCrash(): String? = runCatching {
        val f = java.io.File(filesDir, "last_crash.txt")
        if (f.exists()) f.readText().also { f.delete() } else null
    }.getOrNull()

    /** Each user's own Codespace token lives only on their device (encrypted). Nothing secret is sent to Supabase. */
    suspend fun codespacePat(): String? = store.codespacePat.value.takeIf { it.isNotBlank() }

    private fun workspaceFiles(): Map<String, String> = mapOf(
        ".devcontainer/devcontainer.json" to assets.open("cloud/devcontainer.json").bufferedReader().readText(),
        ".aura/exec_server.py" to assets.open("cloud/exec_server.py").bufferedReader().readText(),
    )

    // overridable so tests can point the cloud client at a mock server
    var cloudApi = "https://api.github.com"
    var cloudPortUrl: (String, Int) -> String = { n, p -> "https://$n-$p.app.github.dev" }
    var cloudPollMs = 5000L

    fun newCloudSession() = CloudSession(store, ::codespacePat, ::workspaceFiles, cloudApi, cloudPortUrl, cloudPollMs)

    private val refreshing = AtomicBoolean(false)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> android.util.Log.e("Aura", "background error", e) })

    /** Re-reads every provider's model list so new or removed models show up without manual work. */
    fun refreshModels(force: Boolean) {
        if (!force && System.currentTimeMillis() - store.modelsRefreshedAt < 6 * 3600_000L) return
        if (!refreshing.compareAndSet(false, true)) return
        appScope.launch {
            try {
                for (p in store.providers.value) {
                    val full = runCatching { withTimeout(20_000) { Llm.listModelsFull(p) } }.getOrNull()
                    val list = full?.models.orEmpty()
                    if (list.isEmpty()) continue
                    val cur = store.providers.value.firstOrNull { it.id == p.id } ?: continue
                    val keep = if (cur.defaultModel.isNotBlank() && cur.defaultModel !in list) list + cur.defaultModel else list
                    store.saveProviders(store.providers.value.map { if (it.id == p.id) cur.copy(models = keep.distinct(), blocked = full?.blocked.orEmpty()) else it })
                }
                store.modelsRefreshedAt = System.currentTimeMillis()
            } finally {
                refreshing.set(false)
            }
        }
    }

    fun notifyApproval(chatId: String, title: String) {
        if (foreground) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = Intent(this, MainActivity::class.java).putExtra("chatId", chatId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(this, ("ask" + chatId).hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CH_ASK).setSmallIcon(R.drawable.ic_sparkle)
            .setContentTitle("Aura needs your approval").setContentText(title.take(120)).setContentIntent(pi).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(("ask" + chatId).hashCode(), n)
    }

    fun clearApprovalNotification(chatId: String) {
        getSystemService(NotificationManager::class.java).cancel(("ask" + chatId).hashCode())
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
        const val CH_ASK = "ask"
    }
}
