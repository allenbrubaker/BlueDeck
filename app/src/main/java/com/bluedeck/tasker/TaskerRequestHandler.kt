package com.bluedeck.tasker

import com.bluedeck.data.models.Vehicle
import com.bluedeck.data.models.VehicleLocation
import com.bluedeck.data.models.VehicleStatusData
import com.bluedeck.data.repository.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

interface TaskerVehicleAccess {
    suspend fun selectedVehicle(): Result<Vehicle>
    suspend fun isDemoMode(): Boolean
    suspend fun location(vehicle: Vehicle): Result<VehicleLocation>
    suspend fun status(vehicle: Vehicle, forceRefresh: Boolean): Result<VehicleStatusData>
    suspend fun lock(vehicle: Vehicle): Result<Unit>
    suspend fun unlock(vehicle: Vehicle): Result<Unit>
}

/** No phone GPS, state pre-checks, or command retries: Tasker owns proximity and retry policy. */
class TaskerRequestHandler(
    private val access: TaskerVehicleAccess,
    private val clock: () -> Long = System::currentTimeMillis
) {
    suspend fun execute(request: TaskerRequest): TaskerResponse {
        val remaining = TaskerContract.MAX_REQUEST_AGE_MS - (clock() - request.requestedAt)
        if (remaining <= 0 || remaining > TaskerContract.MAX_REQUEST_AGE_MS) {
            return TaskerResponse.error(request, "EXPIRED", "Request expired before execution")
        }
        return try {
            withTimeoutOrNull(minOf(45_000L, remaining)) {
                perform(request)
            } ?: TaskerResponse.error(request, "TIMEOUT", "Vehicle request timed out; command outcome may be unknown")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Do not broadcast exception text, URLs, credentials, or server response bodies.
            TaskerResponse.error(request, "INTERNAL_ERROR", "Could not complete the vehicle request")
        }
    }

    private suspend fun perform(request: TaskerRequest): TaskerResponse {
        val vehicle = when (val selected = access.selectedVehicle()) {
            is Result.Error -> return TaskerResponse.error(request, "VEHICLE_UNAVAILABLE", selected.message)
            is Result.Success -> selected.data
        }
        val common = mapOf<String, Any>("demo" to access.isDemoMode())
        val values: Map<String, Any> = when (request.action) {
            TaskerContract.Action.GET_LOCATION -> when (val result = access.location(vehicle)) {
                is Result.Error -> return apiError(request, result)
                is Result.Success -> {
                    val location = result.data
                    val coord = location.coord
                    if (coord == null || !coord.isValid) {
                        return TaskerResponse.error(request, "LOCATION_UNAVAILABLE", "Vehicle did not return valid coordinates")
                    }
                    val speed = location.speed?.value?.takeIf { it.isFinite() && it >= 0 }
                    val heading = location.heading?.takeIf { it in 0..360 }
                    mapOf(
                        "latitude" to requireNotNull(coord.lat),
                        "longitude" to requireNotNull(coord.lon),
                        "speed" to (speed ?: ""),
                        "speedUnit" to (location.speed?.unit?.takeIf { speed != null } ?: ""),
                        "heading" to (heading ?: "")
                    )
                }
            }
            TaskerContract.Action.GET_STATUS -> when (val result = access.status(vehicle, request.forceRefresh)) {
                is Result.Error -> return apiError(request, result)
                is Result.Success -> {
                    val locked = result.data.reportedDoorsLocked
                        ?: return TaskerResponse.error(request, "STATUS_UNAVAILABLE", "Vehicle did not return lock status")
                    mapOf("locked" to locked, "forceRefresh" to request.forceRefresh)
                }
            }
            TaskerContract.Action.LOCK, TaskerContract.Action.UNLOCK -> {
                val result = if (request.action == TaskerContract.Action.LOCK) access.lock(vehicle) else access.unlock(vehicle)
                if (result is Result.Error) return apiError(request, result)
                // Accepted by the vehicle API, not confirmation that the doors have moved.
                mapOf("command" to request.action.name, "accepted" to true)
            }
        }
        return TaskerResponse(request, success = true, values = common + values)
    }

    private fun apiError(request: TaskerRequest, result: Result.Error) =
        TaskerResponse.error(request, "API_ERROR", result.message)
}
