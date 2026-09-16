package com.videoconverter.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.videoconverter.android.MainActivity
import com.videoconverter.android.data.JobStore
import com.videoconverter.android.data.SessionStore
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.engine.FfmpegProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TranscodeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var jobStore: JobStore
    private lateinit var sessionStore: SessionStore
    private lateinit var ffmpeg: FfmpegProcess
    private lateinit var commands: SerialServiceCommands<ServiceCommand>
    private val pumpCoordinator = PumpCoordinator()
    private val stateLock = Any()

    @Volatile
    private var runningJobId: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        jobStore = JobStore(this)
        sessionStore = SessionStore(this)
        ffmpeg = FfmpegProcess(this)
        commands = SerialServiceCommands(scope, ::executeCommand)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = when (intent?.action) {
            ACTION_ENQUEUE -> ServiceCommand.Enqueue
            ACTION_START_PUMP -> ServiceCommand.StartPump
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_JOB_ID)
                ?.let(ServiceCommand::Cancel)
            ACTION_RETRY -> intent.getStringExtra(EXTRA_JOB_ID)
                ?.let(ServiceCommand::Retry)
            ACTION_CLEAR_FINISHED -> ServiceCommand.ClearFinished
            else -> null
        }
        startForegroundBeforeDispatch(
            startForeground = ::showForegroundPlaceholder,
            dispatch = { command?.let { commands.dispatch(startId, it) } },
        )
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runningJobId?.let(ffmpeg::cancel)
        scope.cancel()
        super.onDestroy()
    }

    private fun executeCommand(command: ServiceCommand, start: ServiceStart) {
        when (command) {
            ServiceCommand.Enqueue -> {
                val jobs = pendingJobs.drain()
                if (jobs.isNotEmpty()) {
                    jobStore.update { current -> current + jobs }
                }
                startPump()
            }
            ServiceCommand.StartPump -> startPump()
            is ServiceCommand.Cancel -> cancelJob(command.jobId)
            is ServiceCommand.Retry -> retryJob(command.jobId)
            ServiceCommand.ClearFinished -> clearFinishedJobs()
            ServiceCommand.QueueDrained -> stopWhenQueueIsEmpty(start)
        }
    }

    private fun startPump() {
        if (!pumpCoordinator.requestStart()) return
        scope.launch {
            try {
                while (isActive) {
                    val running = claimNextQueued()
                    if (running == null) {
                        val stillQueued = jobStore.load().any { it.status == JobStatus.Queued }
                        if (pumpCoordinator.shouldContinue(stillQueued)) continue
                        commands.dispatchInternal(ServiceCommand.QueueDrained)
                        return@launch
                    }

                    showForeground(running)

                    val target = sessionStore.load().output
                    val result = ffmpeg.transcode(running, target) { progress ->
                        val updated = jobStore.update { jobs ->
                            jobs.map { job ->
                                if (job.id == running.id && job.status == JobStatus.Running) {
                                    job.copy(progress = progress)
                                } else {
                                    job
                                }
                            }
                        }.firstOrNull { it.id == running.id }
                        if (updated?.status == JobStatus.Running) showForeground(updated)
                    }
                    synchronized(stateLock) {
                        jobStore.update { jobs -> jobs.completeRunningJob(result) }
                        if (runningJobId == running.id) runningJobId = null
                    }
                }
            } finally {
                synchronized(stateLock) {
                    runningJobId = null
                }
                if (pumpCoordinator.finish()) startPump()
            }
        }
    }

    private fun claimNextQueued(): Job? = synchronized(stateLock) {
        var claimed: Job? = null
        jobStore.update { jobs ->
            jobs.claimNextQueued(ffmpeg::reserve).also {
                claimed = it.claimed
            }.jobs
        }
        runningJobId = claimed?.id
        claimed
    }

    private fun cancelJob(jobId: String) {
        synchronized(stateLock) {
            jobStore.update { jobs -> jobs.cancelJob(jobId, runningJobId) }
            if (runningJobId == jobId) ffmpeg.cancel(jobId)
        }
        startPump()
    }

    private fun retryJob(jobId: String) {
        synchronized(stateLock) {
            jobStore.update { jobs -> jobs.retryJob(jobId) }
        }
        startPump()
    }

    private fun clearFinishedJobs() {
        synchronized(stateLock) {
            jobStore.update { jobs ->
                jobs.filter {
                    it.status == JobStatus.Queued || it.status == JobStatus.Running
                }
            }
        }
        if (runningJobId == null) commands.dispatchInternal(ServiceCommand.QueueDrained)
    }

    private fun showForeground(job: Job) {
        startForegroundNotification(notification(job))
    }

    private fun showForegroundPlaceholder() {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("轻转码")
            .setContentText("正在检查转码队列")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        startForegroundNotification(notification)
    }

    private fun startForegroundNotification(notification: Notification) {
        val foregroundType = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        startForeground(NOTIFICATION_ID, notification, foregroundType)
    }

    private fun notification(job: Job): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("轻转码")
            .setContentText("${job.displayName} · ${job.progress.toInt()}%")
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, job.progress.toInt(), false)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "转码进度",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun stopWhenQueueIsEmpty(start: ServiceStart) {
        if (commands.latestStart() != start) return
        val hasWork = synchronized(stateLock) {
            runningJobId != null ||
                jobStore.load().any { it.status == JobStatus.Queued || it.status == JobStatus.Running }
        }
        if (hasWork) {
            startPump()
            return
        }
        if (commands.latestStart() != start) return
        if (stopSelfResult(start.startId)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    companion object {
        private const val ACTION_ENQUEUE =
            "com.videoconverter.android.service.action.ENQUEUE"
        private const val ACTION_START_PUMP =
            "com.videoconverter.android.service.action.START_PUMP"
        private const val ACTION_CANCEL =
            "com.videoconverter.android.service.action.CANCEL"
        private const val ACTION_RETRY =
            "com.videoconverter.android.service.action.RETRY"
        private const val ACTION_CLEAR_FINISHED =
            "com.videoconverter.android.service.action.CLEAR_FINISHED"
        private const val EXTRA_JOB_ID = "jobId"
        private const val CHANNEL_ID = "transcode"
        private const val NOTIFICATION_ID = 1001
        private val pendingJobs = PendingJobMailbox()

        fun enqueue(context: Context, jobs: List<Job>) {
            pendingJobs.append(jobs)
            startForegroundAction(context, ACTION_ENQUEUE)
        }

        fun startPump(context: Context) {
            startForegroundAction(context, ACTION_START_PUMP)
        }

        fun cancel(context: Context, jobId: String) {
            context.startService(actionIntent(context, ACTION_CANCEL, jobId))
        }

        fun retry(context: Context, jobId: String) {
            context.startForegroundService(actionIntent(context, ACTION_RETRY, jobId))
        }

        fun clearFinished(context: Context) {
            context.startService(actionIntent(context, ACTION_CLEAR_FINISHED))
        }

        private fun startForegroundAction(context: Context, action: String) {
            context.startForegroundService(actionIntent(context, action))
        }

        private fun actionIntent(
            context: Context,
            action: String,
            jobId: String? = null,
        ): Intent = Intent(context, TranscodeService::class.java).apply {
            this.action = action
            jobId?.let { putExtra(EXTRA_JOB_ID, it) }
        }
    }
}

private fun List<Job>.replace(updated: Job): List<Job> =
    map { job -> if (job.id == updated.id) updated else job }

internal data class QueuedClaim(
    val jobs: List<Job>,
    val claimed: Job?,
)

internal fun List<Job>.claimNextQueued(reserve: (String) -> Boolean): QueuedClaim {
    val queued = firstOrNull { it.status == JobStatus.Queued }
        ?: return QueuedClaim(this, null)
    if (!reserve(queued.id)) return QueuedClaim(this, null)
    val claimed = queued.copy(
        status = JobStatus.Running,
        progress = 0.0,
        error = null,
    )
    return QueuedClaim(replace(claimed), claimed)
}

internal fun List<Job>.cancelJob(jobId: String, activeJobId: String?): List<Job> =
    map { job ->
        val cancellable = job.status == JobStatus.Queued ||
            (job.status == JobStatus.Running && job.id == activeJobId)
        if (job.id == jobId && cancellable) {
            job.copy(
                status = JobStatus.Cancelled,
                progress = 0.0,
                error = null,
            )
        } else {
            job
        }
    }

internal fun List<Job>.retryJob(jobId: String): List<Job> =
    map { job ->
        if (
            job.id == jobId &&
            job.status in setOf(JobStatus.Failed, JobStatus.Cancelled)
        ) {
            job.copy(
                status = JobStatus.Queued,
                progress = 0.0,
                error = null,
            )
        } else {
            job
        }
    }

internal fun List<Job>.completeRunningJob(result: Job): List<Job> =
    map { job ->
        if (job.id == result.id && job.status == JobStatus.Running) result else job
    }

internal inline fun startForegroundBeforeDispatch(
    startForeground: () -> Unit,
    dispatch: () -> Unit,
) {
    startForeground()
    dispatch()
}

private sealed interface ServiceCommand {
    data object Enqueue : ServiceCommand
    data object StartPump : ServiceCommand
    data class Cancel(val jobId: String) : ServiceCommand
    data class Retry(val jobId: String) : ServiceCommand
    data object ClearFinished : ServiceCommand
    data object QueueDrained : ServiceCommand
}

internal class PendingJobMailbox {
    private val lock = Any()
    private val jobs = mutableListOf<Job>()

    fun append(pending: List<Job>) = synchronized(lock) {
        jobs += pending
    }

    fun drain(): List<Job> = synchronized(lock) {
        jobs.toList().also { jobs.clear() }
    }
}

internal data class ServiceStart(
    val generation: Long,
    val startId: Int,
)

internal class SerialServiceCommands<T>(
    scope: CoroutineScope,
    execute: (T, ServiceStart) -> Unit,
) {
    private val lock = Any()
    private val channel = Channel<Pair<T, ServiceStart>>(Channel.UNLIMITED)
    private var latest: ServiceStart? = null

    init {
        scope.launch {
            for ((command, start) in channel) execute(command, start)
        }
    }

    fun dispatch(startId: Int, command: T) {
        synchronized(lock) {
            val start = ServiceStart(
                generation = (latest?.generation ?: 0) + 1,
                startId = startId,
            )
            latest = start
            check(channel.trySend(command to start).isSuccess)
        }
    }

    fun dispatchInternal(command: T) {
        synchronized(lock) {
            latest?.let { check(channel.trySend(command to it).isSuccess) }
        }
    }

    fun latestStart(): ServiceStart? = synchronized(lock) { latest }
}

internal class PumpCoordinator {
    private var running = false
    private var wakeRequested = false

    @Synchronized
    fun requestStart(): Boolean {
        wakeRequested = true
        if (running) return false
        running = true
        wakeRequested = false
        return true
    }

    @Synchronized
    fun shouldContinue(hasQueuedJob: Boolean): Boolean {
        if (hasQueuedJob || wakeRequested) {
            wakeRequested = false
            return true
        }
        running = false
        return false
    }

    @Synchronized
    fun finish(): Boolean {
        running = false
        return wakeRequested.also { wakeRequested = false }
    }
}
