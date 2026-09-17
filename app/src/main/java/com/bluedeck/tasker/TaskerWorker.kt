package com.bluedeck.tasker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.bluedeck.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class TaskerWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val gateway: TaskerVehicleGateway
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val action = TaskerContract.action(inputData.getString("action")) ?: return Result.failure()
        val request = TaskerRequest(
            action,
            inputData.getString(TaskerContract.REQUEST_ID) ?: return Result.failure(),
            inputData.getBoolean("forceRefresh", true),
            inputData.getLong("requestedAt", 0)
        )
        val response = if (action.isCommand && runAttemptCount > 0) {
            // A process interruption can leave the vehicle command outcome unknown.
            // Never silently replay a lock/unlock command after WorkManager restarts it.
            TaskerResponse.error(request, "INTERRUPTED", "Command interrupted; check vehicle status before retrying")
        } else {
            TaskerRequestHandler(gateway).execute(request)
        }
        TaskerBroadcasts.send(applicationContext, response)
        // Vehicle failures are returned to Tasker, not automatically retried by WorkManager.
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channelId = "tasker_requests"
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "Tasker requests", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("BlueDeck")
            .setContentText("Processing a Tasker request")
            .setOngoing(true)
            .build()
        return ForegroundInfo(7200 + (id.hashCode() and 0xffff), notification)
    }
}
