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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import nu.shel.wldroid.compositor.CompositorSession

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

    // ── Phantom process detection ────────────────────────────────────────

    private val phantomProcessDetector = PhantomProcessDetector()

    /**
     * Check whether the phantom process killer is enabled on this device.
     *
     * UI layers can call this proactively to warn users before problems occur.
     * Returns `true` if enabled, `false` if disabled, or `null` if the
     * setting could not be determined.
     */
    fun checkPhantomProcessKiller(): Boolean? = phantomProcessDetector.isPhantomProcessKillerEnabled()

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
        sessionObserverJob?.cancel()
        sessionObserverJob = null
        desktopSession = null
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

    // ── Session management ─────────────────────────────────────────────

    private var desktopSession: DesktopSession? = null
    private var sessionObserverJob: Job? = null

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
     * Start a desktop session backed by the given [compositorSession] and
     * [launcher]. The service takes ownership of the session lifecycle and
     * will observe the underlying [DesktopSession.state] to keep the
     * notification and [serviceState] in sync.
     *
     * @param compositorSession The compositor session providing the Wayland server.
     * @param launcher The desktop launcher managing proot / VirGL / shims.
     * @param envName A human-readable name for the environment shown in the notification.
     */
    fun startSession(
        compositorSession: CompositorSession,
        launcher: DesktopLauncher,
        envName: String = "Desktop",
    ) {
        val session = DesktopSession(compositorSession, launcher)
        desktopSession = session
        acquireWakeLock()

        _serviceState.value = WldroidServiceState.SessionActive(
            envName = envName,
            gpuMode = launcher.gpuMode.value,
        )
        updateSessionNotification(envName)

        // Observe session state changes and propagate to service state / notification.
        sessionObserverJob?.cancel()
        sessionObserverJob = serviceScope.launch {
            session.state.collectLatest { sessionState ->
                when (sessionState) {
                    DesktopSessionState.RUNNING -> {
                        _serviceState.value = WldroidServiceState.SessionActive(
                            envName = envName,
                            gpuMode = launcher.gpuMode.value,
                        )
                        updateSessionNotification(envName)
                    }
                    DesktopSessionState.ERROR -> {
                        val phantomKillSuspected =
                            phantomProcessDetector.isPhantomProcessKillerEnabled() != false
                        val errorMessage = if (phantomKillSuspected) {
                            PhantomProcessDetector.PHANTOM_KILL_GUIDANCE
                        } else {
                            "Desktop session error"
                        }
                        _serviceState.value = WldroidServiceState.Error(
                            message = errorMessage,
                        )
                        updateErrorNotification(errorMessage)
                    }
                    DesktopSessionState.STOPPED -> {
                        desktopSession = null
                        sessionObserverJob?.cancel()
                        sessionObserverJob = null
                        releaseWakeLock()
                        _serviceState.value = WldroidServiceState.Inactive
                        stopSelfIfIdle()
                    }
                    // IDLE, STARTING, STOPPING — transient, no service state change needed.
                    else -> {}
                }
            }
        }
    }

    /**
     * Stop the running desktop session and release resources.
     *
     * Tears down the [DesktopSession] asynchronously. The session observer
     * will transition the service to [WldroidServiceState.Inactive] once
     * the session reaches [DesktopSessionState.STOPPED].
     *
     * Safe to call when no session is active (no-op).
     */
    fun stopSession() {
        val session = desktopSession ?: run {
            // No active session — ensure we're in a clean state.
            releaseWakeLock()
            _serviceState.value = WldroidServiceState.Inactive
            stopSelfIfIdle()
            return
        }
        serviceScope.launch {
            session.stop()
            // The session observer will handle the rest when state reaches STOPPED.
            // But if the observer is already cancelled, clean up directly.
            if (desktopSession != null) {
                desktopSession = null
                sessionObserverJob?.cancel()
                sessionObserverJob = null
                releaseWakeLock()
                _serviceState.value = WldroidServiceState.Inactive
                stopSelfIfIdle()
            }
        }
    }

    /** Return the current [DesktopSession], or `null` if no session is active. */
    fun getDesktopSession(): DesktopSession? = desktopSession

    /**
     * Stop the service if no work is in progress.
     *
     * Checks that there is no active session and no setup in progress
     * before calling [stopSelf].
     */
    fun stopSelfIfIdle() {
        val state = _serviceState.value
        if (state is WldroidServiceState.Inactive && desktopSession == null) {
            stopSelf()
        }
    }
}
