package com.example.myebookapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** Started only in response to a visible user request, never by a boot receiver or worker. */
class ScanService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var promoted = false
    private val handler = Handler(Looper.getMainLooper())
    private var latestStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null

    private fun updateWakeLock(status: ScanStatus) {
        val lock = wakeLock ?: return
        if (status.isScanning && !status.isPaused) {
            if (!lock.isHeld) lock.acquire(2 * 60_000L) // bounded even if the process misbehaves
        } else if (lock.isHeld) lock.release()
    }

    override fun onCreate() {
        super.onCreate()
        LibraryScanner.claimService(this)
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Library scanning", NotificationManager.IMPORTANCE_LOW))
            }
            val initial = notification(LibraryScanner.status.value)
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, initial)
            promoted = true
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:libraryScan").apply { setReferenceCounted(false) }
            serviceScope.launch {
                LibraryScanner.status.collect {
                    updateWakeLock(it)
                    manager.notify(ID, notification(it))
                }
            }
            serviceScope.launch {
                while (isActive) {
                    delay(60_000L)
                    val status = LibraryScanner.status.value
                    if (status.isScanning && !status.isPaused) wakeLock?.let { if (it.isHeld) it.release() }
                    updateWakeLock(status)
                }
            }
        } catch (e: Exception) {
            LibraryScanner.interruptFromService(applicationContext, this, e.localizedMessage ?: "Foreground service could not start", release = true)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (!promoted) { stopSelf(startId); return START_NOT_STICKY }
        LibraryScanner.claimService(this)
        ensureWorker()
        return START_NOT_STICKY
    }

    private fun ensureWorker() {
        if (!promoted || !LibraryScanner.isServiceOwner(this)) return
        val hasWorker = LibraryScanner.startWorker(applicationContext) {
            handler.post {
                // A completion posted by an old service must not start work after destruction.
                if (promoted) ensureWorker()
            }
        }
        if (!hasWorker) stopSelfResult(latestStartId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (startId != latestStartId) return
        LibraryScanner.interruptFromService(applicationContext, this, "Android's background scan time limit was reached. Resume when the app is visible.")
        stopSelf()
    }

    override fun onDestroy() {
        promoted = false
        LibraryScanner.interruptFromService(applicationContext, this, "Background scan stopped. Resume when the app is visible.", release = true)
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(status: ScanStatus): Notification {
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val label = status.phaseLabel.ifBlank { "Preparing library scan…" }
        val details = "${status.filesFoundInPass} added • ${status.metadataProcessed} details read • ${status.metadataLeft} pending"
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(label)
            .setContentText(details)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setContentIntent(launch)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (status.isScanning && !status.isPaused) {
            val total = status.metadataProcessed + status.metadataLeft
            if (status.phaseLabel.startsWith("Reading") && total > 0) builder.setProgress(total, status.metadataProcessed, false)
            else builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL = "library_scan"
        private const val ID = 3104
    }
}
