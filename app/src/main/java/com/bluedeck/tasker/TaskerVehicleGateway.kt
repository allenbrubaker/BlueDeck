package com.bluedeck.tasker

import com.bluedeck.data.models.Vehicle
import com.bluedeck.data.repository.PreferencesManager
import com.bluedeck.data.repository.Result
import com.bluedeck.data.repository.VehicleRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

class TaskerVehicleGateway @Inject constructor(
    private val repository: VehicleRepository,
    private val preferences: PreferencesManager
) : TaskerVehicleAccess {
    override suspend fun selectedVehicle(): Result<Vehicle> {
        if (!preferences.isDemoMode() && !preferences.isLoggedIn.first()) {
            return Result.Error("Sign in to BlueDeck first")
        }
        val vin = preferences.selectedVin.first()?.takeIf { it.isNotBlank() }
            ?: return Result.Error("Select a vehicle in BlueDeck first")
        val snapshot = preferences.widgetVehicleSnapshot.first()
        // A matching cached identity avoids an extra vehicle-list request on each command.
        if (snapshot.vehicleVin == vin && snapshot.registrationId.isNotBlank()) {
            return Result.Success(Vehicle(
                vin = vin,
                regId = snapshot.registrationId,
                vehicleIdentifier = snapshot.vehicleId,
                enrollmentId = snapshot.vehicleId,
                generation = snapshot.generation.ifBlank { "3" },
                brandIndicator = snapshot.brandIndicator.ifBlank { "H" }
            ))
        }
        return when (val result = repository.getVehicles()) {
            is Result.Error -> result
            is Result.Success -> result.data.firstOrNull { it.vin == vin }?.let { Result.Success(it) }
                ?: Result.Error("The selected vehicle is unavailable; select it again in BlueDeck")
        }
    }

    override suspend fun isDemoMode() = preferences.isDemoMode()
    override suspend fun location(vehicle: Vehicle) = repository.getVehicleLocation(
        vehicle.vin, vehicle.regId, vehicle.generation, vehicle.brandIndicator
    )
    override suspend fun status(vehicle: Vehicle, forceRefresh: Boolean) = repository.getVehicleStatus(
        vehicle.vin, forceRefresh, vehicle.regId, vehicle.generation, vehicle.brandIndicator
    )
    override suspend fun lock(vehicle: Vehicle) = repository.lockDoors(
        vehicle.vin, vehicle.regId, vehicle.generation, vehicle.brandIndicator
    )
    override suspend fun unlock(vehicle: Vehicle) = repository.unlockDoors(
        vehicle.vin, vehicle.regId, vehicle.generation, vehicle.brandIndicator
    )
}
