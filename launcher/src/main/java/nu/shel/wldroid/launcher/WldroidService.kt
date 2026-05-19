package nu.shel.wldroid.launcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service that keeps wldroid processes alive during setup and
 * desktop sessions, preventing Android's PhantomProcessRecord killer from
 * terminating long-running proot / virgl / compositor processes.
 *
 * Bind locally via [LocalBinder] to observe [serviceState] and call
 * lifecycle methods such as [createEnvironment], [startSession], and
 * [stopSession].
 */
class WldroidService : Service() {

    companion object {
        private const val CHANNEL_ID = "wldroid_status"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "wldroid:service"
    }

    // ── Binder ──────────────────────────────────────────────────────────

    /** Local binder that exposes the service instance to in-process clients. */
    inner class LocalBinder : Binder() {
        fun getService(): WldroidService = this@WldroidService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    // ── State ───────────────────────────────────────────────────────────

    private val _serviceState = MutableStateFlow<WldroidServiceState>(WldroidServiceState.Inactive)

    /** Observable service state. */
    val serviceState: StateFlow<WldroidServiceState> = _serviceState.asStateFlow()

    // ── Coroutines ──────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Wake lock ───────────────────────────────────────────────────────

    private var wakeLock: PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            wakeLock = null
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(
            title = "WLDroid",
            text = "Initializing…",
            indeterminate = true,
        )
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        releaseWakeLock()
        _serviceState.value = WldroidServiceState.Inactive
        super.onDestroy()
    }

    // ── Notification helpers ────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "WLDroid Status",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows the current state of the WLDroid desktop service"
            setShowBadge(false)
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Int? = null,
        indeterminate: Boolean = false,
        priority: Int = NotificationCompat.PRIORITY_LOW,
        ongoing: Boolean = true,
        silent: Boolean = true,
    ) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_manage)
        .setContentTitle(title)
        .setContentText(text)
        .setPriority(priority)
        .setOngoing(ongoing)
        .setSilent(silent)
        .apply {
            when {
                progress != null -> setProgress(100, progress, false)
                indeterminate -> setProgress(0, 0, true)
            }
        }
        .build()

    private fun postNotification(
        title: String,
        text: String,
        progress: Int? = null,
        indeterminate: Boolean = false,
        priority: Int = NotificationCompat.PRIORITY_LOW,
        ongoing: Boolean = true,
        silent: Boolean = true,
    ) {
        val notification = buildNotification(title, text, progress, indeterminate, priority, ongoing, silent)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    // ── Notification update methods ─────────────────────────────────────

    /**
     * Update the notification to reflect the current setup phase.
     * Uses a determinate progress bar for [SetupPhase.DOWNLOADING] and
     * indeterminate for all other phases.
     */
    fun updateSetupNotification(phase: SetupPhase, progress: Float?, envName: String) {
        _serviceState.value = WldroidServiceState.Setup(phase, progress, envName)
        acquireWakeLock()
        val pct = progress?.let { (it * 100).toInt().coerceIn(0, 100) }
        val isDeterminate = phase == SetupPhase.DOWNLOADING && pct != null
        postNotification(
            title = "Setting up $envName",
            text = phase.displayName,
            progress = if (isDeterminate) pct else null,
            indeterminate = !isDeterminate,
        )
    }

    /**
     * Update the notification for an active desktop session.
     * Low priority, silent, ongoing.
     */
    fun updateSessionNotification(envName: String) {
        postNotification(
            title = "Desktop running",
            text = envName,
            priority = NotificationCompat.PRIORITY_LOW,
            silent = true,
        )
    }

    /**
     * Update the notification to show an error state.
     * Default priority so it surfaces in the notification shade.
     */
    fun updateErrorNotification(message: String) {
        postNotification(
            title = "WLDroid Error",
            text = message,
            priority = NotificationCompat.PRIORITY_DEFAULT,
            ongoing = false,
            silent = false,
        )
    }

    // ── Placeholder session methods ─────────────────────────────────────

    /**
     * Begin environment creation / setup.
     * Placeholder — will be wired to proot + shims in a later step.
     */
    fun createEnvironment(@Suppress("UNUSED_PARAMETER") config: Any) {
        _serviceState.value = WldroidServiceState.Setup(
            phase = SetupPhase.DOWNLOADING,
            progress = 0f,
            envName = "New Environment",
        )
        acquireWakeLock()
    }

    /**
     * Start a desktop session.
     * Placeholder — will be wired to compositor + virgl in a later step.
     */
    fun startSession() {
        _serviceState.value = WldroidServiceState.SessionActive(
            envName = "Default",
            gpuMode = nu.shel.wldroid.virgl.GpuMode.SOFTWARE,
        )
        acquireWakeLock()
    }

    /**
     * Stop the running desktop session and release resources.
     * Placeholder — will be wired to compositor + virgl teardown.
     */
    fun stopSession() {
        releaseWakeLock()
        _serviceState.value = WldroidServiceState.Inactive
        stopSelfIfIdle()
    }

    /**
     * Stop the service if no work is in progress.
     */
    fun stopSelfIfIdle() {
        if (_serviceState.value is WldroidServiceState.Inactive) {
            stopSelf()
        }
    }
}
