package com.bluedeck.tasker

/** Public broadcast contract. Tasker converts extra names to lowercase variables. */
object TaskerContract {
    const val PREFIX = "com.bluedeck.tasker."
    const val TASKER_PACKAGE = "net.dinglisch.android.taskerm"
    const val REQUEST_ID = "requestId"
    const val MAX_REQUEST_AGE_MS = 60_000L

    enum class Action(val resultAction: String) {
        GET_LOCATION("${PREFIX}LOCATION_RESULT"),
        GET_STATUS("${PREFIX}STATUS_RESULT"),
        LOCK("${PREFIX}COMMAND_RESULT"),
        UNLOCK("${PREFIX}COMMAND_RESULT");

        val intentAction: String get() = PREFIX + name
        val isCommand: Boolean get() = this == LOCK || this == UNLOCK
    }

    fun action(value: String?): Action? = Action.entries.firstOrNull {
        value == it.intentAction || (it.isCommand && value == "com.bluedeck.widget.${it.name}")
    }
}

data class TaskerRequest(
    val action: TaskerContract.Action,
    val requestId: String,
    val forceRefresh: Boolean = true,
    val requestedAt: Long = System.currentTimeMillis()
)

data class TaskerResponse(
    val request: TaskerRequest,
    val success: Boolean,
    val error: String = "",
    val errorCode: String = "",
    val values: Map<String, Any> = emptyMap()
) {
    fun extras(completedAt: Long): Map<String, Any> = values + mapOf(
        "apiVersion" to 1,
        "action" to request.action.name,
        TaskerContract.REQUEST_ID to request.requestId,
        "success" to success,
        "error" to error.take(500),
        "errorCode" to errorCode,
        "requestedAt" to request.requestedAt,
        "completedAt" to completedAt
    ) + if (request.action.isCommand) mapOf(
        "command" to request.action.name,
        "accepted" to success
    ) else emptyMap()

    companion object {
        fun error(request: TaskerRequest, code: String, message: String) =
            TaskerResponse(request, false, message, code)
    }
}
