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
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TranscodeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var jobStore: JobStore
    private lateinit var sessionStore: SessionStore
    private lateinit var ffmpeg: FfmpegProcess
    private var pumpJob: CoroutineJob? = null
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
            ACTION_ENQUEUE, ACTION_START_PUMP -> startPump()
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_JOB_ID)?.let(::cancelJob)
            ACTION_RETRY -> intent.getStringExtra(EXTRA_JOB_ID)?.let(::retryJob)
            ACTION_CLEAR_FINISHED -> clearFinishedJobs()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runningJobId?.let(ffmpeg::cancel)
        scope.cancel()
        super.onDestroy()
    }

    private fun startPump() {
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch {
            while (isActive) {
                val queued = jobStore.load().firstOrNull { it.status == JobStatus.Queued }
                if (queued == null) {
                    stopWhenQueueIsEmpty()
                    return@launch
                }

                runningJobId = queued.id
                val running = queued.copy(
                    status = JobStatus.Running,
                    progress = 0.0,
                    error = null,
                )
                jobStore.update { jobs -> jobs.replace(running) }
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
                jobStore.update { jobs -> jobs.replace(result) }
                runningJobId = null
            }
        }
    }

    private fun cancelJob(jobId: String) {
        ffmpeg.cancel(jobId)
        scope.launch {
            jobStore.update { jobs ->
                jobs.map { job ->
                    if (job.id == jobId && job.status == JobStatus.Queued) {
                        job.copy(status = JobStatus.Cancelled, error = null)
                    } else {
                        job
                    }
                }
            }
            startPump()
        }
    }

    private fun retryJob(jobId: String) {
        scope.launch {
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
            startPump()
        }
    }

    private fun clearFinishedJobs() {
        scope.launch {
            jobStore.update { jobs ->
                jobs.filter { it.status == JobStatus.Queued || it.status == JobStatus.Running }
            }
            if (runningJobId == null) stopWhenQueueIsEmpty()
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

    private fun stopWhenQueueIsEmpty() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
