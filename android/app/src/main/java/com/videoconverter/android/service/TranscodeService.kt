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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TranscodeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var jobStore: JobStore
    private lateinit var sessionStore: SessionStore
    private lateinit var ffmpeg: FfmpegProcess
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENQUEUE, ACTION_START_PUMP -> startPump(startId)
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_JOB_ID)?.let {
                cancelJob(it, startId)
            }
            ACTION_RETRY -> intent.getStringExtra(EXTRA_JOB_ID)?.let {
                retryJob(it, startId)
            }
            ACTION_CLEAR_FINISHED -> clearFinishedJobs(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runningJobId?.let(ffmpeg::cancel)
        scope.cancel()
        super.onDestroy()
    }

    private fun startPump(startId: Int) {
        if (!pumpCoordinator.requestStart()) return
        scope.launch {
            while (isActive) {
                val running = claimNextQueued()
                if (running == null) {
                    val stillQueued = jobStore.load().any { it.status == JobStatus.Queued }
                    if (pumpCoordinator.shouldContinue(stillQueued)) continue
                    stopWhenQueueIsEmpty(startId)
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
                    jobStore.update { jobs -> jobs.replace(result) }
                    if (runningJobId == running.id) runningJobId = null
                }
            }
        }
    }

    private fun claimNextQueued(): Job? = synchronized(stateLock) {
        var claimed: Job? = null
        jobStore.update { jobs ->
            val queued = jobs.firstOrNull { it.status == JobStatus.Queued }
                ?: return@update jobs
            claimed = queued.copy(
                status = JobStatus.Running,
                progress = 0.0,
                error = null,
            )
            jobs.replace(claimed!!)
        }
        runningJobId = claimed?.id
        claimed
    }

    private fun cancelJob(jobId: String, startId: Int) {
        scope.launch {
            synchronized(stateLock) {
                if (runningJobId == jobId) {
                    ffmpeg.cancel(jobId)
                } else {
                    jobStore.update { jobs ->
                        jobs.map { job ->
                            if (job.id == jobId && job.status == JobStatus.Queued) {
                                job.copy(status = JobStatus.Cancelled, error = null)
                            } else {
                                job
                            }
                        }
                    }
                }
            }
            startPump(startId)
        }
    }

    private fun retryJob(jobId: String, startId: Int) {
        scope.launch {
            synchronized(stateLock) {
                jobStore.update { jobs ->
                    jobs.map { job ->
                        if (
                            job.id == jobId &&
                            job.status in setOf(
                                JobStatus.Failed,
                                JobStatus.Cancelled,
                            )
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
                }
            }
            startPump(startId)
        }
    }

    private fun clearFinishedJobs(startId: Int) {
        scope.launch {
            synchronized(stateLock) {
                jobStore.update { jobs ->
                    jobs.filter {
                        it.status == JobStatus.Queued || it.status == JobStatus.Running
                    }
                }
            }
            if (runningJobId == null) stopWhenQueueIsEmpty(startId)
        }
    }

    private fun showForeground(job: Job) {
        val notification = notification(job)
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

    private fun stopWhenQueueIsEmpty(startId: Int) {
        if (stopSelfResult(startId)) {
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

        fun enqueue(context: Context, jobs: List<Job>) {
            JobStore(context).update { current -> current + jobs }
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
}
