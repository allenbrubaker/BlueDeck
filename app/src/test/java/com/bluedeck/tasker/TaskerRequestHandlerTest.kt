package com.bluedeck.tasker

import com.bluedeck.data.models.Coordinate
import com.bluedeck.data.models.Speed
import com.bluedeck.data.models.Vehicle
import com.bluedeck.data.models.VehicleLocation
import com.bluedeck.data.models.VehicleStatusData
import com.bluedeck.data.repository.Result
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TaskerRequestHandlerTest {
    private val now = 100_000L
    private fun request(action: TaskerContract.Action) = TaskerRequest(action, "trip-42", requestedAt = now)

    @Test fun returnsVehicleCoordinatesAndStationaryNorthWithoutDroppingZeroValues() = runTest {
        val access = FakeAccess()
        access.locationResult = Result.Success(VehicleLocation(Coordinate(0.0, 0.0), Speed(1, 0.0), 0))
        val result = TaskerRequestHandler(access) { now }.execute(request(TaskerContract.Action.GET_LOCATION))
        assertTrue(result.success)
        assertEquals(0.0, result.values["latitude"])
        assertEquals(0.0, result.values["longitude"])
        assertEquals(0.0, result.values["speed"])
        assertEquals(1, result.values["speedUnit"])
        assertEquals(0, result.values["heading"])
        assertEquals("trip-42", result.extras(now)["requestId"])
        assertEquals("com.bluedeck.tasker.LOCATION_RESULT", result.request.action.resultAction)
        assertEquals(listOf("selected", "location"), access.calls)
    }

    @Test fun missingCoordinatesNeverBecomeZeroZero() = runTest {
        for (json in listOf("{}", "{\"coord\":{}}", "{\"coord\":{\"lat\":10}}", "{\"coord\":{\"lon\":20}}")) {
            val access = FakeAccess()
            access.locationResult = Result.Success(Gson().fromJson(json, VehicleLocation::class.java))
            val result = TaskerRequestHandler(access) { now }.execute(request(TaskerContract.Action.GET_LOCATION))
            assertFalse(json, result.success)
            assertEquals("LOCATION_UNAVAILABLE", result.errorCode)
            assertFalse(result.values.containsKey("latitude"))
        }
    }

    @Test fun rejectsNonFiniteAndOutOfRangeCoordinates() = runTest {
        for (coord in listOf(Coordinate(Double.NaN, 0.0), Coordinate(0.0, Double.POSITIVE_INFINITY), Coordinate(91.0, 0.0), Coordinate(0.0, -181.0))) {
            val access = FakeAccess()
            access.locationResult = Result.Success(VehicleLocation(coord))
            assertFalse(TaskerRequestHandler(access) { now }.execute(request(TaskerContract.Action.GET_LOCATION)).success)
        }
    }

    @Test fun absentSpeedAndHeadingRemainUnknown() = runTest {
        val access = FakeAccess()
        access.locationResult = Result.Success(Gson().fromJson("{\"coord\":{\"lat\":39.1,\"lon\":-94.6},\"speed\":{}}", VehicleLocation::class.java))
        val result = TaskerRequestHandler(access) { now }.execute(request(TaskerContract.Action.GET_LOCATION))
        assertTrue(result.success)
        assertEquals("", result.values["speed"])
        assertEquals("", result.values["speedUnit"])
        assertEquals("", result.values["heading"])
    }

    @Test fun statusUsesReportedLockAndRefreshChoice() = runTest {
        val access = FakeAccess()
        for (locked in listOf(true, false)) {
            access.statusResult = Result.Success(VehicleStatusData(doorLock = locked))
            val result = TaskerRequestHandler(access) { now }.execute(request(TaskerContract.Action.GET_STATUS).copy(forceRefresh = false))
            assertTrue(result.success)
            assertEquals(locked, result.values["locked"])
            assertEquals(false, access.lastForceRefresh)
        }
    }

    @Test fun missingOrUnrecognizedLockStateIsNotReportedAsUnlocked() = runTest {
        for (json in listOf("{}", "{\"doorLockStatus\":\"unknown\"}")) {
            val access = FakeAccess()
            access.statusResult = Result.Success(Gson().fromJson(json, VehicleStatusData::class.java))
            val result = TaskerRequestHandler(access) { now }.execute(request(TaskerContract.Action.GET_STATUS))
            assertFalse(result.success)
            assertEquals("STATUS_UNAVAILABLE", result.errorCode)
            assertFalse(result.values.containsKey("locked"))
        }
        assertEquals(true, VehicleStatusData(doorLockStatus = "LOCKED").reportedDoorsLocked)
        assertEquals(false, VehicleStatusData(doorLockStatus = "0").reportedDoorsLocked)
    }

    @Test fun commandsDoNotQueryStatusOrLocationAndAreSentOnlyOnce() = runTest {
        for (action in listOf(TaskerContract.Action.LOCK, TaskerContract.Action.UNLOCK)) {
            val access = FakeAccess()
            val result = TaskerRequestHandler(access) { now }.execute(request(action))
            assertTrue(result.success)
            assertEquals(listOf("selected", action.name.lowercase()), access.calls)
            assertEquals(action.name, result.extras(now)["command"])
            assertEquals(true, result.extras(now)["accepted"])
            assertFalse(result.values.containsKey("locked"))
        }
    }

    @Test fun commandFailureReturnsCorrelationAndDoesNotRetry() = runTest {
        val access = FakeAccess()
        access.commandResult = Result.Error("Lock failed (503)")
        val result = TaskerRequestHandler(access) { now }.execute(request(TaskerContract.Action.LOCK))
        assertFalse(result.success)
        assertEquals("API_ERROR", result.errorCode)
        assertEquals("Lock failed (503)", result.error)
        assertEquals("trip-42", result.extras(now)["requestId"])
        assertEquals("LOCK", result.extras(now)["command"])
        assertEquals(false, result.extras(now)["accepted"])
        assertEquals(listOf("selected", "lock"), access.calls)
    }

    @Test fun noSelectedVehiclePreventsAllVehicleCalls() = runTest {
        val access = FakeAccess()
        access.selected = Result.Error("Select a vehicle in BlueDeck first")
        val result = TaskerRequestHandler(access) { now }.execute(request(TaskerContract.Action.UNLOCK))
        assertEquals("VEHICLE_UNAVAILABLE", result.errorCode)
        assertEquals(listOf("selected"), access.calls)
    }

    @Test fun expiredRequestNeverUnlocksLater() = runTest {
        val access = FakeAccess()
        val result = TaskerRequestHandler(access) { now + 60_000 }.execute(request(TaskerContract.Action.UNLOCK))
        assertEquals("EXPIRED", result.errorCode)
        assertTrue(access.calls.isEmpty())
    }

    @Test fun timeoutProducesFailureWithoutAutomaticReplay() = runTest {
        val access = FakeAccess()
        access.commandDelay = 50_000
        val result = TaskerRequestHandler(access) { now }.execute(request(TaskerContract.Action.LOCK))
        assertEquals("TIMEOUT", result.errorCode)
        assertEquals(listOf("selected", "lock"), access.calls)
    }

    @Test fun coroutineCancellationPropagates() = runTest {
        val access = FakeAccess()
        access.cancellation = true
        try {
            TaskerRequestHandler(access) { now }.execute(request(TaskerContract.Action.LOCK))
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(listOf("selected", "lock"), access.calls)
        }
    }

    @Test fun demoResultsAreExplicitlyMarked() = runTest {
        val access = FakeAccess()
        access.demo = true
        val result = TaskerRequestHandler(access) { now }.execute(request(TaskerContract.Action.GET_STATUS))
        assertEquals(true, result.values["demo"])
    }

    private class FakeAccess : TaskerVehicleAccess {
        val calls = mutableListOf<String>()
        var selected: Result<Vehicle> = Result.Success(Vehicle(vin = "test-vin"))
        var locationResult: Result<VehicleLocation> = Result.Success(VehicleLocation(Coordinate(39.1, -94.6)))
        var statusResult: Result<VehicleStatusData> = Result.Success(VehicleStatusData(doorLock = true))
        var commandResult: Result<Unit> = Result.Success(Unit)
        var lastForceRefresh: Boolean? = null
        var commandDelay = 0L
        var cancellation = false
        var demo = false
        override suspend fun selectedVehicle(): Result<Vehicle> { calls += "selected"; return selected }
        override suspend fun isDemoMode() = demo
        override suspend fun location(vehicle: Vehicle): Result<VehicleLocation> { calls += "location"; return locationResult }
        override suspend fun status(vehicle: Vehicle, forceRefresh: Boolean): Result<VehicleStatusData> {
            calls += "status"
            lastForceRefresh = forceRefresh
            return statusResult
        }
        override suspend fun lock(vehicle: Vehicle): Result<Unit> = command("lock")
        override suspend fun unlock(vehicle: Vehicle): Result<Unit> = command("unlock")
        private suspend fun command(name: String): Result<Unit> {
            calls += name
            if (cancellation) throw CancellationException("Stopped")
            delay(commandDelay)
            return commandResult
        }
    }
}
