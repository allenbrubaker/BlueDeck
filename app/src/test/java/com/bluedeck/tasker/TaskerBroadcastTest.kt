package com.bluedeck.tasker

import android.app.Application
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TaskerBroadcastTest {
    @Test fun resultIsPackageScopedWithoutAComponentAndContainsTypedExtras() {
        val request = TaskerRequest(TaskerContract.Action.GET_LOCATION, "req-1", requestedAt = 1000)
        val response = TaskerResponse(request, true, values = mapOf("latitude" to 39.1, "longitude" to -94.6, "heading" to 0, "speed" to 0.0))
        val intent = TaskerBroadcasts.resultIntent(response, 2000)
        assertEquals("com.bluedeck.tasker.LOCATION_RESULT", intent.action)
        assertEquals("net.dinglisch.android.taskerm", intent.`package`)
        assertNull(intent.component)
        assertNull(intent.data)
        assertNull(intent.type)
        assertNull(intent.categories)
        assertTrue(intent.getBooleanExtra("success", false))
        assertEquals("req-1", intent.getStringExtra("requestId"))
        assertEquals(39.1, intent.getDoubleExtra("latitude", 0.0), 0.000001)
        assertEquals(0, intent.getIntExtra("heading", -1))
        assertEquals(2000L, intent.getLongExtra("completedAt", 0))
    }

    @Test fun taskerNumericIdsAndStringFalseAreAccepted() {
        val request = TaskerReceiver.requestFrom(Intent("com.bluedeck.tasker.GET_STATUS")
            .putExtra("requestId", 42L).putExtra("forceRefresh", "false"))!!
        assertEquals("42", request.requestId)
        assertFalse(request.forceRefresh)
    }

    @Test fun legacyLockUnlockActionsAreSupported() {
        assertEquals(TaskerContract.Action.LOCK, TaskerReceiver.requestFrom(Intent("com.bluedeck.widget.LOCK"))?.action)
        assertEquals(TaskerContract.Action.UNLOCK, TaskerReceiver.requestFrom(Intent("com.bluedeck.widget.UNLOCK"))?.action)
        assertNull(TaskerReceiver.requestFrom(Intent("com.bluedeck.widget.CLIMATE")))
        assertNull(TaskerReceiver.requestFrom(Intent("com.other.UNLOCK")))
    }

    @Test fun failedCommandStillIdentifiesCommandAndRequest() {
        val request = TaskerRequest(TaskerContract.Action.UNLOCK, "unlock-1")
        val intent = TaskerBroadcasts.resultIntent(TaskerResponse.error(request, "API_ERROR", "Rejected"), 2000)
        assertEquals("com.bluedeck.tasker.COMMAND_RESULT", intent.action)
        assertFalse(intent.getBooleanExtra("success", true))
        assertFalse(intent.getBooleanExtra("accepted", true))
        assertEquals("UNLOCK", intent.getStringExtra("command"))
        assertEquals("unlock-1", intent.getStringExtra("requestId"))
        assertEquals("Rejected", intent.getStringExtra("error"))
    }
}
