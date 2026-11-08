package dev.tailg.core.domain

/** Keep explicit selection local. A garage listing is NOT proof of control authorization. */
object VehicleSelectionPolicy {
    fun select(candidate: String, vehicles: List<ListedVehicle>): String? =
        candidate.takeIf { id -> vehicles.any { it.summary.id.value == id } }

    fun reconcile(selected: String?, vehicles: List<ListedVehicle>): String? =
        selected?.takeIf { id -> vehicles.any { it.summary.id.value == id } }
}
