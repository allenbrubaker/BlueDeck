package com.bluedeck.tasker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TaskerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (TaskerContract.action(intent.action) == null) return
        val pending = goAsync()
        enqueue(context.applicationContext, intent, pending::finish)
    }

    companion object {
        /** Also used by the legacy widget receiver when Tasker supplies a requestId. */
        fun enqueue(context: Context, intent: Intent, finish: () -> Unit) {
            CoroutineScope(Dispatchers.IO).launch {
                var request: TaskerRequest? = null
                var workId: UUID? = null
                try {
                    request = requestFrom(intent) ?: return@launch
                    val work = OneTimeWorkRequestBuilder<TaskerWorker>()
                        .setInputData(workDataOf(
                            "action" to request.action.intentAction,
                            TaskerContract.REQUEST_ID to request.requestId,
                            "forceRefresh" to request.forceRefresh,
                            "requestedAt" to request.requestedAt
                        ))
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build()
                    workId = work.id
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        "tasker:${request.action.name}:${request.requestId}",
                        ExistingWorkPolicy.KEEP,
                        work
                    ).result.get(7, TimeUnit.SECONDS)
                } catch (_: Exception) {
                    workId?.let { WorkManager.getInstance(context).cancelWorkById(it) }
                    request?.let {
                        TaskerBroadcasts.send(context, TaskerResponse.error(
                            it, "ENQUEUE_FAILED", "Could not schedule the vehicle request"
                        ))
                    }
                } finally {
                    finish()
                }
            }
        }

        @Suppress("DEPRECATION")
        internal fun requestFrom(intent: Intent): TaskerRequest? {
            val action = TaskerContract.action(intent.action) ?: return null
            // Tasker's Send Intent auto-types numeric extras. Preserve numeric IDs too.
            val suppliedId = intent.extras?.get(TaskerContract.REQUEST_ID)
            val id = when (suppliedId) {
                is String, is Number -> suppliedId.toString().takeIf { it.isNotBlank() }
                else -> null
            } ?: UUID.randomUUID().toString()
            require(id.length <= 200) { "requestId is too long" }
            val forceRefresh = when (val value = intent.extras?.get("forceRefresh")) {
                is Boolean -> value
                is String -> value.toBooleanStrictOrNull() ?: true
                else -> true
            }
            return TaskerRequest(action, id, forceRefresh)
        }
    }
}

internal object TaskerBroadcasts {
    fun resultIntent(response: TaskerResponse, completedAt: Long): Intent =
        Intent(response.request.action.resultAction).apply {
            // Package-scoped, never component-scoped: Tasker registers its receiver dynamically.
            setPackage(TaskerContract.TASKER_PACKAGE)
            response.extras(completedAt).forEach { (key, value) ->
                when (value) {
                    is Boolean -> putExtra(key, value)
                    is Int -> putExtra(key, value)
                    is Long -> putExtra(key, value)
                    is Double -> putExtra(key, value)
                    is String -> putExtra(key, value)
                    else -> error("Unsupported Tasker extra type")
                }
            }
        }

    fun send(context: Context, response: TaskerResponse) {
        context.sendBroadcast(resultIntent(response, System.currentTimeMillis()))
    }
}
