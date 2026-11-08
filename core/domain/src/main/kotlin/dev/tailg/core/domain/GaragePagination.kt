package dev.tailg.core.domain

/**
 * Read-only paginated garage. A page-size limit is a wire contract, not a guarantee that
 * every page will be filled. A short page is treated as the end until live data proves otherwise.
 */
data class VehiclePage(
    val pageIndex: Int,
    val pageSize: Int,
    val vehicles: List<ListedVehicle>,
    val serverHasNext: Boolean? = null,
    val serverTotal: Int? = null,
) {
    init {
        require(pageIndex >= 1) { "Page index starts at one" }
        require(pageSize in 1..100) { "Unsupported page size" }
        require(vehicles.size <= pageSize) { "Page contains more vehicles than requested" }
        require(serverTotal == null || serverTotal >= 0) { "Invalid total" }
    }
}

data class GarageSnapshot(
    val vehicles: List<ListedVehicle> = emptyList(),
    val loadedPages: Int = 0,
    val nextPageIndex: Int? = 1,
    val totalHint: Int? = null,
) {
    val hasMore: Boolean get() = nextPageIndex != null
}

/**
 * Merges pages without granting device capabilities, repeating a vehicle, or looping forever
 * when an older server ignores nowPageIndex and returns the same page repeatedly.
 */
object GaragePageMerger {
    private const val MAX_PAGES = 50

    fun append(previous: GarageSnapshot, incoming: VehiclePage): GarageSnapshot {
        require(previous.nextPageIndex == incoming.pageIndex) { "Out-of-sequence garage page" }
        require(previous.loadedPages < MAX_PAGES) { "Garage page safety limit reached" }
        val byId = LinkedHashMap<String, ListedVehicle>()
        previous.vehicles.forEach { byId[it.summary.id.value] = it }
        incoming.vehicles.forEach { byId[it.summary.id.value] = it }
        val uniqueAdded = byId.size - previous.vehicles.size
        val couldHaveMore = when (incoming.serverHasNext) {
            true -> true
            false -> false
            null -> incoming.vehicles.size == incoming.pageSize
        } && uniqueAdded > 0 && incoming.pageIndex < MAX_PAGES
        return GarageSnapshot(
            vehicles = byId.values.toList(),
            loadedPages = incoming.pageIndex,
            nextPageIndex = if (couldHaveMore) incoming.pageIndex + 1 else null,
            totalHint = incoming.serverTotal ?: previous.totalHint,
        )
    }
}
