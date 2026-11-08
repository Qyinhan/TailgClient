package dev.tailg.client

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.tailg.core.cloud.CloudFailure
import dev.tailg.core.cloud.CloudGateway
import dev.tailg.core.cloud.CloudLoginResult
import dev.tailg.core.cloud.SessionVault
import dev.tailg.core.domain.GarageLoadState
import dev.tailg.core.domain.GaragePageMerger
import dev.tailg.core.domain.GarageSnapshot
import dev.tailg.core.domain.HumanChallengeReceipt
import dev.tailg.core.domain.SmsLoginInput
import dev.tailg.core.domain.VehicleSelectionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ClientUiState(
    val hasSession: Boolean = false,
    val selectedVehicleId: String? = null,
    val busy: Boolean = false,
    val loadingMore: Boolean = false,
    val notice: String = "",
    val garage: GarageLoadState = GarageLoadState.NotLoaded,
    val bleRunning: Boolean = false,
    val bleDevices: List<dev.tailg.core.domain.BleObservation> = emptyList(),
    val gattTarget: String? = null,
    val gattState: dev.tailg.core.domain.BleGattState = dev.tailg.core.domain.BleGattState.Idle,
    val bleNotice: String = "扫描仅用于设备发现，不会配对、连接或发送控制命令。",
)

/** Only explicit user events may trigger network requests. Never log credentials or challenge proofs. */
class ClientViewModel(app: Application) : AndroidViewModel(app) {
    private val gateway = CloudGateway(SessionVault(app))
    private val bleScanner = dev.tailg.core.ble.AndroidBleScanner(app)
    private val gattInspector = dev.tailg.core.ble.AndroidGattInspector(app)
    private val mutableState = MutableStateFlow(ClientUiState(hasSession = gateway.hasSession()))
    val state: StateFlow<ClientUiState> = mutableState.asStateFlow()
    private var lastSmsSuccessAt = 0L
    private var lastSmsAttemptAt = 0L
    // A late HTTP response must not restore a garage after the user has logged out.
    private var sessionEpoch = 0
    private var bleScanEpoch = 0
    private var gattEpoch = 0
    private var scanTimeoutJob: kotlinx.coroutines.Job? = null

    fun requestSms(phone: String, ticket: String, randstr: String) {
        if (mutableState.value.busy) return
        val now = SystemClock.elapsedRealtime()
        if (lastSmsSuccessAt != 0L && now - lastSmsSuccessAt < 60_000L) {
            mutableState.update { it.copy(notice = "短信请求过于频繁，请稍后再试") }
            return
        }
        if (lastSmsAttemptAt != 0L && now - lastSmsAttemptAt < 10_000L) {
            mutableState.update { it.copy(notice = "请勿频繁提交验证码，请稍后再试") }
            return
        }
        val challenge = HumanChallengeReceipt(ticket.trim(), randstr.trim())
        challenge.validate()?.let { error ->
            mutableState.update { it.copy(notice = error) }
            return
        }
        lastSmsAttemptAt = now
        val epoch = sessionEpoch
        launchTask {
            withContext(Dispatchers.IO) { gateway.verifyChallengeAndSendSms(phone.trim(), challenge) }
            if (epoch != sessionEpoch) return@launchTask
            lastSmsSuccessAt = SystemClock.elapsedRealtime()
            mutableState.update { it.copy(notice = "服务器已接受短信请求；实际送达仍需验证") }
        }
    }

    fun login(phone: String, smsCode: String, macCode: String) {
        if (mutableState.value.busy || mutableState.value.garage == GarageLoadState.Loading) return
        val input = SmsLoginInput(phone.trim(), smsCode.trim(), macCode.trim())
        input.validate()?.let { error ->
            mutableState.update { it.copy(notice = error) }
            return
        }
        val epoch = sessionEpoch
        // This ticket is taken before coroutine dispatch; logout invalidates it in the vault.
        val loginGeneration = gateway.sessionGeneration()
        launchTask {
            val result = withContext(Dispatchers.IO) { gateway.login(input, loginGeneration) }
            if (epoch != sessionEpoch) return@launchTask
            when (result) {
                is CloudLoginResult.SessionReady -> {
                    mutableState.update {
                        it.copy(hasSession = true, notice = "已收到会话令牌，正在读取车辆", garage = GarageLoadState.Loading)
                    }
                    loadFirstPage(epoch)
                }
                is CloudLoginResult.PendingVerification -> {
                    mutableState.update { it.copy(notice = result.message) }
                }
            }
        }
    }

    /** Refresh deliberately discards previous pages; the server may have changed vehicle bindings. */
    fun refreshGarage() {
        if (!gateway.hasSession()) {
            mutableState.update { it.copy(hasSession = false, garage = GarageLoadState.NotLoaded, selectedVehicleId = null) }
            return
        }
        if (mutableState.value.busy || mutableState.value.loadingMore || mutableState.value.garage == GarageLoadState.Loading) return
        val epoch = sessionEpoch
        mutableState.update { it.copy(garage = GarageLoadState.Loading, notice = "") }
        viewModelScope.launch { loadFirstPage(epoch) }
    }

    /** Local selection never implies vehicle ownership or an enabled control protocol. */
    fun selectVehicle(id: String) {
        val loaded = (mutableState.value.garage as? GarageLoadState.Ready)?.vehicles.orEmpty()
        val selected = VehicleSelectionPolicy.select(id, loaded) ?: return
        mutableState.update { it.copy(selectedVehicleId = selected, notice = "已切换本地查看车辆；控车功能仍未启用") }
    }

    /** Pagination is read-only, bounded, explicit, and never retries itself. */
    fun loadMoreGarage() {
        val current = mutableState.value
        val snapshot = (current.garage as? GarageLoadState.Ready)?.snapshot ?: return
        val nextPage = snapshot.nextPageIndex ?: return
        if (current.busy || current.loadingMore || !current.hasSession) return
        val epoch = sessionEpoch
        mutableState.update { it.copy(loadingMore = true, notice = "") }
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { gateway.loadVehiclePage(nextPage) }
                if (epoch != sessionEpoch) return@launch
                val merged = GaragePageMerger.append(snapshot, page)
                mutableState.update {
                    it.copy(
                        hasSession = true,
                        garage = GarageLoadState.Ready(merged),
                        selectedVehicleId = VehicleSelectionPolicy.reconcile(it.selectedVehicleId, merged.vehicles),
                        notice = if (!merged.hasMore && page.vehicles.size == page.pageSize) "已停止重复分页；如有车辆遗漏请刷新后核对" else "",
                    )
                }
            } catch (failure: CloudFailure) {
                if (epoch != sessionEpoch) return@launch
                onGarageFailure(failure, preserveLoadedPage = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (epoch == sessionEpoch) mutableState.update { it.copy(notice = "加载更多失败，请手动重试") }
            } finally {
                if (epoch == sessionEpoch) mutableState.update { it.copy(loadingMore = false) }
            }
        }
    }

    /** Passive BLE scan, initiated exclusively by the user. */
    fun startBleScan() {
        if (mutableState.value.bleRunning) return
        cancelGattInspection()
        bleScanEpoch++
        val myScan = bleScanEpoch
        mutableState.update {
            it.copy(bleDevices = emptyList(), bleNotice = "正在发现附近蓝牙广播…", bleRunning = false)
        }
        val result = bleScanner.start(
            onObservation = { observed ->
                if (myScan == bleScanEpoch && mutableState.value.bleRunning) {
                    mutableState.update {
                        it.copy(bleDevices = dev.tailg.core.domain.BleDiscoveryPolicy.add(it.bleDevices, observed))
                    }
                }
            },
            onError = { message ->
                if (myScan == bleScanEpoch) {
                    stopBleScan()
                    mutableState.update { it.copy(bleNotice = message) }
                }
            },
        )
        when (result) {
            dev.tailg.core.domain.BleScanStart.Started -> {
                mutableState.update { it.copy(bleRunning = true, bleNotice = "扫描中（15 秒后自动停止）") }
                scanTimeoutJob?.cancel()
                scanTimeoutJob = viewModelScope.launch {
                    kotlinx.coroutines.delay(15_000L)
                    if (myScan == bleScanEpoch) stopBleScan()
                }
            }
            dev.tailg.core.domain.BleScanStart.PermissionRequired ->
                mutableState.update { it.copy(bleNotice = "需要授予蓝牙扫描权限") }
            dev.tailg.core.domain.BleScanStart.BluetoothDisabled ->
                mutableState.update { it.copy(bleNotice = "请先开启系统蓝牙") }
            dev.tailg.core.domain.BleScanStart.NotSupported ->
                mutableState.update { it.copy(bleNotice = "设备不支持 BLE 扫描") }
            is dev.tailg.core.domain.BleScanStart.Failed ->
                mutableState.update { it.copy(bleNotice = result.reason) }
        }
    }

    fun blePermissions(): Array<String> = bleScanner.missingPermissions()

    fun blePermissionDenied() {
        mutableState.update { it.copy(bleNotice = "蓝牙权限被拒绝；未开始扫描") }
    }

    fun stopBleScan() {
        bleScanEpoch++
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        bleScanner.stop()
        mutableState.update {
            it.copy(bleRunning = false, bleNotice = "扫描已停止，结果只保留在当前应用进程")
        }
    }

    /** Explicit selection only; never connect to a guessed or unobserved address. */
    fun inspectBleGatt(address: String, userAuthorized: Boolean) {
        if (!userAuthorized) {
            mutableState.update { it.copy(bleNotice = "请先确认您拥有该设备或获得检查授权") }
            return
        }
        val observed = mutableState.value.bleDevices.any { it.address == address.uppercase() }
        if (!observed) {
            mutableState.update { it.copy(bleNotice = "请先扫描并选择当前发现的设备") }
            return
        }
        val device = bleScanner.observedDevice(address)
        if (device == null) {
            mutableState.update { it.copy(bleNotice = "该设备已不在当前扫描结果中，请重新扫描") }
            return
        }
        if (mutableState.value.bleRunning) stopBleScan()
        cancelGattInspection()
        val operation = ++gattEpoch
        mutableState.update {
            it.copy(gattTarget = address.uppercase(), gattState = dev.tailg.core.domain.BleGattState.Connecting)
        }
        gattInspector.inspect(device) { next ->
            if (gattEpoch == operation) {
                mutableState.update { it.copy(gattTarget = address.uppercase(), gattState = next) }
            }
        }
    }

    fun cancelGattInspection() {
        gattEpoch++
        gattInspector.close()
        mutableState.update {
            it.copy(gattTarget = null, gattState = dev.tailg.core.domain.BleGattState.Idle)
        }
    }

    fun leaveBleScreen() {
        cancelGattInspection()
        stopBleScan()
        bleScanner.clearObservations()
        mutableState.update { it.copy(bleDevices = emptyList()) }
    }

    override fun onCleared() {
        gattInspector.close()
        bleScanner.clearObservations()
        super.onCleared()
    }

    fun logout() {
        leaveBleScreen()
        sessionEpoch++
        gateway.logout()
        mutableState.value = ClientUiState(notice = "已清除本地会话")
    }

    private suspend fun loadFirstPage(epoch: Int) {
        try {
            val firstPage = withContext(Dispatchers.IO) { gateway.loadVehiclePage(1) }
            if (epoch != sessionEpoch) return
            val snapshot = GaragePageMerger.append(GarageSnapshot(), firstPage)
            mutableState.update {
                it.copy(
                    hasSession = true,
                    garage = GarageLoadState.Ready(snapshot),
                    selectedVehicleId = VehicleSelectionPolicy.reconcile(it.selectedVehicleId, snapshot.vehicles),
                    notice = "车辆分页已读取（尚待在线兼容性验证）",
                )
            }
        } catch (failure: CloudFailure) {
            if (epoch == sessionEpoch) onGarageFailure(failure, preserveLoadedPage = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (epoch == sessionEpoch) mutableState.update {
                it.copy(garage = GarageLoadState.Failed("车辆列表加载失败，请手动重试"))
            }
        }
    }

    private fun onGarageFailure(failure: CloudFailure, preserveLoadedPage: Boolean) {
        if (failure.unauthorized || !gateway.hasSession()) {
            mutableState.update {
                it.copy(hasSession = false, loadingMore = false, garage = GarageLoadState.NotLoaded, selectedVehicleId = null, notice = "登录会话已失效，请重新登录")
            }
        } else if (preserveLoadedPage) {
            mutableState.update { it.copy(notice = failure.displayMessage) }
        } else {
            mutableState.update {
                it.copy(hasSession = true, garage = GarageLoadState.Failed(failure.displayMessage), notice = "车库接口未通过验证")
            }
        }
    }

    private fun launchTask(task: suspend () -> Unit) {
        val epoch = sessionEpoch
        mutableState.update { it.copy(busy = true, notice = "") }
        viewModelScope.launch {
            try {
                task()
            } catch (error: CloudFailure) {
                if (epoch == sessionEpoch) mutableState.update { it.copy(notice = error.displayMessage) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (epoch == sessionEpoch) mutableState.update {
                    it.copy(notice = "操作未完成，请检查网络与接口兼容性")
                }
            } finally {
                // Logout owns the new UI state; obsolete jobs cannot change its notice or busy flag.
                if (epoch == sessionEpoch) mutableState.update { it.copy(busy = false) }
            }
        }
    }
}
