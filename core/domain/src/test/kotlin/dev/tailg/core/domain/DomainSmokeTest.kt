package dev.tailg.core.domain

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** Run with tools/check_domain.sh even when Android SDK is not installed. */
fun main() {
    check(FeatureCatalog.all.isNotEmpty())
    check(FeatureCatalog.all.map { it.id }.distinct().size == FeatureCatalog.all.size)
    check(FeatureCatalog.all.count { it.state == ImplementationState.IN_PROGRESS } == 4)
    check(FeatureCatalog.all.none { it.state == ImplementationState.VERIFIED })
    val policy = VehicleCommandPolicy()
    val vehicle = VehicleSummary(VehicleId("self-test"), "测试模型", setOf(VehicleCapability.REMOTE_LOCK), true)
    check(policy.authorize(vehicle, VehicleCommand.LOCK) == ControlDecision.Allowed)
    check(policy.authorize(vehicle, VehicleCommand.UNLOCK) is ControlDecision.Rejected)
    check(policy.authorize(vehicle.copy(boundToCurrentAccount = false), VehicleCommand.LOCK) is ControlDecision.Rejected)

    var gatewayCalls = 0
    val gateway = object : VehicleCommandGateway {
        override suspend fun send(vehicle: VehicleSummary, command: VehicleCommand): VehicleCommandResult {
            gatewayCalls++
            return VehicleCommandResult.Unconfirmed
        }
    }
    val useCase = VehicleControlUseCase(policy, gateway)
    check(runSync { useCase.execute(vehicle, VehicleCommand.UNLOCK) } is VehicleCommandResult.Rejected)
    check(gatewayCalls == 0) { "Rejected commands must not reach the transport" }
    check(runSync { useCase.execute(vehicle, VehicleCommand.LOCK) } == VehicleCommandResult.Unconfirmed)
    check(gatewayCalls == 1)
    val humanProof = HumanChallengeReceipt("ticket-from-human", "random-from-human")
    check(humanProof.validate() == null)
    check(humanProof.requestBody().keys == setOf("ticket", "randstr"))
    check(HumanChallengeReceipt("", "r").validate() != null)
    check(HumanChallengeReceipt("x\n", "r").validate() != null)
    val verifiedCaptcha = CaptchaVerificationPolicy.inspect(true, "pass-token-from-server", 60)
    check(verifiedCaptcha is CaptchaDecision.Accepted)
    check("pass-token-from-server" !in verifiedCaptcha.toString())
    check(CaptchaVerificationPolicy.inspect(false, "pass-token", 60) is CaptchaDecision.Rejected)
    check(CaptchaVerificationPolicy.inspect(true, "", 60) is CaptchaDecision.Rejected)
    check(CaptchaVerificationPolicy.inspect(true, "token", 0) is CaptchaDecision.Rejected)
    check(CaptchaVerificationPolicy.inspect(null, "token", null) is CaptchaDecision.Accepted)
    check(SessionRenewalPolicy.updatedToken("0", true, "new-token") == "new-token")
    check(SessionRenewalPolicy.updatedToken("1", true, "new-token") == null)
    check(SessionRenewalPolicy.updatedToken("0", false, "new-token") == null)
    check(SessionRenewalPolicy.updatedToken("0", true, "not-valid\n") == null)
    val login = SmsLoginInput("13800138000", "456123")
    check(login.validate() == null)
    check(login.requestBody().keys == setOf("macCode", "phone", "smsCode", "autoCompleteUserDetail"))
    check(login.requestBody()["autoCompleteUserDetail"] == "true")
    check(SmsLoginInput("133", "1111").validate() != null)
    check(VehicleListWireContract.requestBody()["containShare"] == true)
    check(VehicleListWireContract.pageBody(true, 20, 2)["nowPageIndex"] == 2)
    check(SessionReceiptPolicy.inspect("1", "token", null) is SessionDecision.Incomplete)
    check(SessionReceiptPolicy.inspect("0", null, null) is SessionDecision.Incomplete)
    check(SessionReceiptPolicy.inspect("0", "sample-token", null) is SessionDecision.Accepted)
    check(SessionReceiptPolicy.inspect("0", "bad\rheader", null) is SessionDecision.Incomplete)
    check("sample-token" !in SessionReceiptPolicy.inspect("0", "sample-token", null).toString())
    val listed = VehicleListMapper.map(VehicleListRecord("car-1", "车 A", "日常用车", null, "1", "77"))
    check(listed?.summary?.displayName == "日常用车")
    check(listed?.summary?.capabilities?.isEmpty() == true)
    check(listed?.summary?.let { policy.authorize(it, VehicleCommand.LOCK) } is ControlDecision.Rejected)
    check(VehicleListMapper.map(VehicleListRecord(null, "A", null, null, null, null)) == null)

    // Read-only garage pagination: explicit server flag, fallback, de-duplication and cap.
    val garageCars = (1..10).map { n ->
        requireNotNull(VehicleListMapper.map(VehicleListRecord("id-$n", "车$n", null, null, null, null)))
    }
    check(garageCars.none { it.summary.boundToCurrentAccount })
    check(VehicleSelectionPolicy.select("id-3", garageCars) == "id-3")
    check(VehicleSelectionPolicy.select("unseen", garageCars) == null)
    check(VehicleSelectionPolicy.reconcile("id-3", garageCars) == "id-3")
    check(VehicleSelectionPolicy.reconcile("id-3", garageCars.drop(3)) == null)
    check(VehicleSelectionPolicy.reconcile(null, garageCars) == null)
    val firstPage = VehiclePage(1, 10, garageCars, serverHasNext = true, serverTotal = 12)
    val firstGarage = GaragePageMerger.append(GarageSnapshot(), firstPage)
    check(firstGarage.vehicles.size == 10 && firstGarage.nextPageIndex == 2)
    check(firstGarage.totalHint == 12)
    val page2Car = requireNotNull(VehicleListMapper.map(
        VehicleListRecord("id-11", "车辆11", null, null, null, null),
    ))
    val pageTwo = VehiclePage(2, 10, listOf(garageCars.first(), page2Car), serverHasNext = false)
    val merged = GaragePageMerger.append(firstGarage, pageTwo)
    check(merged.vehicles.size == 11 && !merged.hasMore)
    check(merged.totalHint == 12)
    check(merged.vehicles.first().summary.id.value == "id-1")
    check(merged.vehicles.last().summary.id.value == "id-11")
    val repeated = GaragePageMerger.append(
        firstGarage, VehiclePage(2, 10, garageCars, serverHasNext = true),
    )
    check(!repeated.hasMore) { "Repeating a full page must terminate pagination" }
    val shortPageHasMore = GaragePageMerger.append(
        GarageSnapshot(), VehiclePage(1, 10, garageCars.take(2), serverHasNext = true),
    )
    check(shortPageHasMore.hasMore) { "An explicit server hasNext must override short-page fallback" }
    val fallbackShort = GaragePageMerger.append(
        GarageSnapshot(), VehiclePage(1, 10, garageCars.take(2)),
    )
    check(!fallbackShort.hasMore)
    check(runCatching { GaragePageMerger.append(firstGarage, firstPage) }.isFailure)
    check(runCatching { VehiclePage(1, 2, garageCars) }.isFailure)
    check(runCatching { VehicleListWireContract.pageBody(true, 101, 1) }.isFailure)
    val fence = SessionMutationGate()
    val oldLogin = fence.ticket()
    fence.invalidate() // User pressed logout while the old HTTP request was in flight.
    var storedAfterLogout = false
    check(!fence.commitIfCurrent(oldLogin) { storedAfterLogout = true })
    check(!storedAfterLogout)
    val newLogin = fence.ticket()
    check(fence.commitIfCurrent(newLogin) { storedAfterLogout = true })
    check(storedAfterLogout)
    check(!fence.commitIfCurrent(newLogin) { error("Replayed login ticket") })

    val firstAd = BleObservation("aa:bb:cc:dd:ee:ff", "  Vehicle?  ", -55, setOf("180D"))
    val oneAd = BleDiscoveryPolicy.add(emptyList(), firstAd)
    check(oneAd.single().address == "AA:BB:CC:DD:EE:FF")
    check(oneAd.single().advertisedName == "Vehicle?")
    check(BleDiscoveryPolicy.add(oneAd, firstAd.copy(rssi = -46)).size == 1)
    check(BleDiscoveryPolicy.add(oneAd, firstAd.copy(address = "invalid")) == oneAd)
    val hundredAds = (0 until 101).fold(emptyList<BleObservation>()) { list, index ->
        BleDiscoveryPolicy.add(list, BleObservation("AA:BB:CC:DD:EE:%02X".format(index), null, -70))
    }
    check(hundredAds.size == 100)
    // APK-backed BLE discovery hints are not verified device identity or authorization.
    check(BleReference.advertisedNames.size == 18)
    check(BleReference.candidateServiceUuids.size == 14)
    check(BleReferencePolicy.inspect(
        BleObservation("AA:BB:CC:DD:EE:10", "TAILG_AAIFDX_123", -60),
    ).nameHint == "TAILG_AAIFDX")
    check(!BleReferencePolicy.inspect(
        BleObservation("AA:BB:CC:DD:EE:11", "NOT_TAILG_AAIFDX", -60),
    ).isCandidate)
    check(BleReferencePolicy.inspect(
        BleObservation("AA:BB:CC:DD:EE:12", null, -60,
            setOf("0000fbb1-0000-1000-8000-00805f9b34fb")),
    ).isCandidate)
    val discoveryServices = (1..40).map { index ->
        BleGattServiceInfo(
            uuid = "0000%04x-0000-1000-8000-00805f9b34fb".format(index),
            characteristics = (1..40).map { n ->
                BleGattCharacteristicInfo("0000%04x-0000-1000-8000-00805f9b34fb".format(n), true, false, true, false)
            },
        )
    }
    val bounded = BleGattInventoryPolicy.normalize(discoveryServices)
    check(bounded.size == 24)
    check(bounded.sumOf { it.characteristics.size } == 192)
    check(bounded.all { it.characteristics.size <= 24 })
    check(BleGattInventoryPolicy.normalize(listOf(
        BleGattServiceInfo("BAD_UUID", emptyList()),
    )).isEmpty())
    val knownGatt = listOf(
        BleGattServiceInfo(
            "00002001-0000-1000-8000-00805f9b34fb",
            listOf(BleGattCharacteristicInfo(
                "00002002-0000-1000-8000-00805f9b34fb", true, true, false, false,
            )),
        ),
    )
    val knownEvidence = BleGattEvidence.inspect(knownGatt)
    check(knownEvidence.serviceCount == 1 && knownEvidence.characteristicCount == 1)
    check(knownEvidence.matchedServices.size == 1 && knownEvidence.matchedCharacteristics.size == 1)
    check(knownEvidence.hasStaticMatches)
    check(!BleGattEvidence.inspect(listOf(
        BleGattServiceInfo("0000180a-0000-1000-8000-00805f9b34fb", emptyList()),
    )).hasStaticMatches)
    val anonymous = BleGattEvidence.anonymousReport(knownGatt)
    check("00002001-0000-1000-8000-00805f9b34fb" in anonymous)
    check("00002002-0000-1000-8000-00805f9b34fb" in anonymous)
    check("[RW]" in anonymous)
    check("AA:BB:CC:DD:EE:FF" !in anonymous)
    check("pass-token-from-server" !in anonymous)
    check(BleGattEvidence.inspect(discoveryServices).characteristicCount == 192)
    // Android may reorder/duplicate BLE callback events. A stale or duplicated event
    // must never start a second GATT service discovery or change a newer operation.
    val gattGate = BleGattSessionGate()
    val staleOperation = gattGate.begin()
    check(!gattGate.acceptServiceResult(staleOperation)) // no result before CONNECTED
    check(gattGate.acceptConnected(staleOperation))
    check(!gattGate.acceptConnected(staleOperation)) // duplicate CONNECTED
    val currentOperation = gattGate.begin()
    check(!gattGate.acceptServiceResult(staleOperation)) // stale callback from former device
    check(!gattGate.finishIfActive(staleOperation))
    check(gattGate.acceptConnected(currentOperation))
    check(gattGate.acceptServiceResult(currentOperation))
    check(!gattGate.acceptServiceResult(currentOperation))
    check(!gattGate.finishIfActive(currentOperation))
    gattGate.invalidate()
    check(!gattGate.isCurrent(currentOperation))
    val failedOperation = gattGate.begin()
    check(gattGate.finishIfActive(failedOperation)) // connection failure before discovery
    check(!gattGate.acceptConnected(failedOperation))
    gattGate.invalidate()
    check(gattGate.phase == BleGattSessionGate.Phase.IDLE)

    println("domain-smoke: OK; catalog=${FeatureCatalog.all.size} features; control + auth + garage + BLE discovery policies verified")
}

private fun <T> runSync(block: suspend () -> T): T {
    var terminalResult: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) { terminalResult = result }
    })
    return requireNotNull(terminalResult) { "Unexpected suspension in the synchronous smoke gateway" }.getOrThrow()
}
