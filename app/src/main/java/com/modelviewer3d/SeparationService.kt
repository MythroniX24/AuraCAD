package com.modelviewer3d

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch

/**
 * ForegroundService — keeps process alive during heavy mesh separation.
 *
 * Flow:
 *   1. MeshListFragment starts this service via startForegroundService()
 *   2. Service shows a progress notification immediately
 *   3. Coroutine runs nativePerformSeparationCPU() on IO thread (heavy CPU)
 *   4. Polls nativeGetSeparationProgress() to update notification
 *   5. When CPU done → sends ACTION_SEPARATION_CPU_DONE broadcast
 *   6. MainActivity receives it → calls nativePerformSeparationGPU() on GL thread
 *   7. MainActivity sends ACTION_SEPARATION_COMPLETE broadcast
 *   8. Service receives it → shows "done" notification → stops itself
 */
class SeparationService : Service() {

    companion object {
        const val ACTION_START             = "com.modelviewer3d.SEP_START"
        const val ACTION_SEPARATION_CPU_DONE = "com.modelviewer3d.SEP_CPU_DONE"
        const val ACTION_SEPARATION_COMPLETE = "com.modelviewer3d.SEP_COMPLETE"
        const val ACTION_SEPARATION_FAILED   = "com.modelviewer3d.SEP_FAILED"

        const val CHANNEL_ID   = "mesh_separation"
        const val NOTIF_ID     = 7777

        fun start(context: Context) {
            val intent = Intent(context, SeparationService::class.java)
                .setAction(ACTION_START)
            context.startForegroundService(intent)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var progressJob: Job? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification(0, "Starting separation…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) runSeparation()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Core logic ────────────────────────────────────────────────────────────
    private fun runSeparation() {
        scope.launch {
            // Poll progress every 600ms and update notification
            progressJob = launch {
                while (isActive) {
                    delay(600)
                    val p = try { NativeLib.nativeGetSeparationProgress() } catch (_: Exception) { 0 }
                    if (p in 1..99) updateNotification(p, "Separating mesh islands… $p%")
                }
            }

            // Heavy CPU work (sort + UF + reconstruction) — blocks IO thread
            val cpuOk = try {
                NativeLib.nativePerformSeparationCPU()
            } catch (e: Exception) {
                false
            }

            progressJob?.cancel()

            if (!cpuOk) {
                updateNotification(0, "Separation failed")
                sendBroadcast(Intent(ACTION_SEPARATION_FAILED))
                delay(2000)
                stopSelf()
                return@launch
            }

            // CPU done — ask MainActivity to upload to GPU (needs GL thread)
            updateNotification(95, "Uploading to GPU…")
            sendBroadcast(Intent(ACTION_SEPARATION_CPU_DONE))
            // Service stays alive; MainActivity will broadcast COMPLETE when GPU done
        }
    }

    // ── Called by MainActivity broadcast receiver after GPU upload ────────────
    fun onSeparationComplete(meshCount: Int) {
        scope.launch {
            updateNotification(100, "✅ $meshCount mesh islands ready!")
            delay(3000)
            stopSelf()
        }
    }

    // ── Notification helpers ──────────────────────────────────────────────────
    private fun buildNotification(progress: Int, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("AuraCAD — Mesh Separation")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(progress < 100)
            .setOnlyAlertOnce(true)
            .apply {
                if (progress in 1..99) {
                    setProgress(100, progress, false)
                } else if (progress == 0) {
                    setProgress(100, 0, true) // indeterminate while starting
                }
            }
            .build()
    }

    private fun updateNotification(progress: Int, text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(progress, text))
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        val chan = NotificationChannel(
            CHANNEL_ID,
            "AuraCAD Mesh Separation",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress while separating 3D mesh islands"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(chan)
    }
}
