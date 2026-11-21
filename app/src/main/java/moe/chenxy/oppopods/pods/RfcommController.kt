package moe.chenxy.oppopods.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.utils.MediaControl
import moe.chenxy.oppopods.utils.SystemApisUtils
import moe.chenxy.oppopods.utils.miuiStrongToast.MiuiStrongToastUtil
import moe.chenxy.oppopods.utils.miuiStrongToast.MiuiStrongToastUtil.cancelPodsNotificationByMiuiBt
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.NotificationSettings
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsPrefsKey
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.putBatteryStatus
import java.io.IOException
import android.content.SharedPreferences
import java.util.concurrent.Executor

@SuppressLint("MissingPermission", "StaticFieldLeak")
object RfcommController {
    private const val TAG = "OppoPods-RfcommController"
    private const val BATTERY_POLL_INTERVAL_MS = 30_000L

    // Basic Objects
    private val rfcommLock = Any()
    private var socket: BluetoothSocket? = null
    private var mContext: Context? = null
    lateinit var mDevice: BluetoothDevice
    private lateinit var mPrefs: SharedPreferences

    private var scanToken: Any? = null
    var routes: List<MediaRoute2Info> = listOf()
    private lateinit var mediaRouter: MediaRouter2

    // Handshake signals: after sending a query, suspend until the matching
    // response cmd arrives (with timeout) instead of blind fixed delays.
    // CONFLATED keeps only the most recent signal.
    private val productIdResponse = Channel<Unit>(Channel.CONFLATED)
    private val broadcastCodesResponse = Channel<Unit>(Channel.CONFLATED)

    // Status
    private var mShowedConnectedToast = false
    @Volatile
    private var isPodConnected = false
    @Volatile
    private var isRfcommConnected = false
    private var lastTempBatt = 0
    lateinit var currentBatteryParams: BatteryParams
    private var currentAnc: Int = 1
    private var currentColorId: Int = -1
    private var currentGameMode: Boolean = false
    private var currentEqPresetId: Int = -1
    private var currentDeviceEqPresets: List<EqDevicePreset> = emptyList()
    private val pendingDeletedEqIds = mutableSetOf<Int>()
    private var pendingSavedEqName: String? = null
    private var currentSpatialAudioMode: Int = SpatialAudioMode.OFF
    private var currentSpatialSound: Boolean = false
    private var currentNoiseLevel: Int = NoiseLevel.DEEP
    /** -1 = unknown / not in smart mode; otherwise a [NoiseLevel] constant of the
     *  level smart mode is currently auto-applying (LIGHT/MEDIUM/DEEP). */
    private var currentSmartAncLevel: Int = -1
    private var currentAutoPlayPause: Boolean = false
    private var currentDualDevice: Boolean = false
    private var currentConnectedDevices: List<ConnectedDevice> = emptyList()
    private var currentConnectedDevicesReceived: Boolean = false
    @Volatile
    private lateinit var activeProfile: DeviceProfile
    private var rfcommConnectionMethod: RfcommConnectionMethod = RfcommConnectionMethod.UUID
    private var lastGameModeStatusUpdateMs: Long = 0L
    private var firstBatteryReceived: Boolean = false
    private var notificationSettings: NotificationSettings = NotificationSettings()
    private val showConnectionBatteryIslandEnabled: Boolean
        get() = notificationSettings.showConnectionBatteryIsland
    private val showConnectionPopupEnabled: Boolean
        get() = notificationSettings.showConnectionPopup
    private val connectionPopupDismissSeconds: Int
        get() = notificationSettings.connectionPopupDismissSeconds
    private val showConnectionNotificationEnabled: Boolean
        get() = notificationSettings.showConnectionNotification
    private val notificationIslandStyleEnabled: Boolean
        get() = notificationSettings.notificationIslandStyle
    private var lastKnownCaseBattery: Int = 0
    private var lastKnownCaseCharging: Boolean = false
    private var cachedDeviceName: String = ""

    // Polling job
    private var batteryPollJob: kotlinx.coroutines.Job? = null

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            p1?.let { handleUIEvent(it, p0) }
        }
    }

    private fun changeUIAncStatus(status: Int) {
        if (status !in 1..4) return
        Intent(OppoPodsAction.ACTION_PODS_ANC_CHANGED).apply {
            this.putExtra("status", status)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            mContext!!.sendBroadcast(this)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_ANC_CHANGED) {
            putExtra("status", status)
        }
    }

    private fun changeUIBatteryStatus(status: BatteryParams) {
        Intent(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED).apply {
            putBatteryStatus(status)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            mContext!!.sendBroadcast(this)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            putBatteryStatus(status)
        }
    }

    private fun changeUIGameModeStatus(enabled: Boolean) {
        Intent(OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED).apply {
            this.putExtra("enabled", enabled)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            mContext!!.sendBroadcast(this)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED) {
            putExtra("enabled", enabled)
        }
    }

    private fun addEqExtras(intent: Intent) {
        if (!::activeProfile.isInitialized) return
        val namesById = LinkedHashMap<Int, String>()
        activeProfile.eqPresets.forEach { namesById[it.id] = it.name }
        currentDeviceEqPresets.forEach { entry ->
            if (entry.name.isNotBlank()) namesById[entry.id] = entry.name
        }
        intent.putExtra("id", currentEqPresetId)
        intent.putExtra("name", namesById[currentEqPresetId] ?: "")
        intent.putIntegerArrayListExtra("preset_ids", ArrayList(namesById.keys))
        intent.putStringArrayListExtra("preset_names", ArrayList(namesById.values))
        intent.putExtra(
            OppoPodsAction.EXTRA_EQ_ENTRIES_JSON,
            DeviceProfileStore.exportEqEntries(currentDeviceEqPresets),
        )
    }

    private fun changeUIEqStatus() {
        Intent(OppoPodsAction.ACTION_PODS_EQ_PRESET_CHANGED).apply {
            addEqExtras(this)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            mContext?.sendBroadcast(this)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_EQ_PRESET_CHANGED) {
            addEqExtras(this)
        }
    }

    private fun changeUISpatialAudioStatus(mode: Int) {
        val normalizedMode = mode.coerceIn(SpatialAudioMode.OFF, SpatialAudioMode.HEAD_TRACKING)
        Intent(OppoPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED).apply {
            this.putExtra("mode", normalizedMode)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            mContext!!.sendBroadcast(this)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED) {
            putExtra("mode", normalizedMode)
        }
    }

    private fun changeUISpatialSoundStatus(enabled: Boolean) {
        Intent(OppoPodsAction.ACTION_PODS_SPATIAL_SOUND_CHANGED).apply {
            this.putExtra("enabled", enabled)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            mContext!!.sendBroadcast(this)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_SPATIAL_SOUND_CHANGED) {
            putExtra("enabled", enabled)
        }
    }

    private fun changeUINoiseLevelStatus(level: Int) {
        Intent(OppoPodsAction.ACTION_PODS_NOISE_LEVEL_CHANGED).apply {
            this.putExtra("level", level)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            mContext!!.sendBroadcast(this)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_NOISE_LEVEL_CHANGED) {
            putExtra("level", level)
        }
    }

    private fun changeUISmartAncLevel(level: Int) {
        Intent(OppoPodsAction.ACTION_PODS_SMART_ANC_LEVEL_CHANGED).apply {
            this.putExtra("level", level)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            mContext!!.sendBroadcast(this)
        }
    }

    private fun changeUIAutoPlayPauseStatus(enabled: Boolean) {
        Intent(OppoPodsAction.ACTION_PODS_AUTO_PLAY_PAUSE_CHANGED).apply {
            this.putExtra("enabled", enabled)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            mContext!!.sendBroadcast(this)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_AUTO_PLAY_PAUSE_CHANGED) {
            putExtra("enabled", enabled)
        }
    }

    private fun changeUIDualDeviceStatus(enabled: Boolean) {
        Intent(OppoPodsAction.ACTION_PODS_DUAL_DEVICE_CHANGED).apply {
            this.putExtra("enabled", enabled)
            this.putExtra("devices_received", false)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            mContext!!.sendBroadcast(this)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_DUAL_DEVICE_CHANGED) {
            putExtra("enabled", enabled)
        }
    }

    private fun changeUIConnectedDevicesStatus(devices: List<ConnectedDevice>) {
        Intent(OppoPodsAction.ACTION_PODS_CONNECTED_DEVICES_CHANGED).apply {
            this.putParcelableArrayListExtra("devices", ArrayList(devices))
            this.putExtra("devices_received", currentConnectedDevicesReceived)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            mContext!!.sendBroadcast(this)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun sendBtLogBroadcast(isSend: Boolean, packet: ByteArray, label: String?) {
        val context = mContext ?: return
        Intent(OppoPodsAction.ACTION_BT_LOG_ENTRY).apply {
            setPackage(BuildConfig.APPLICATION_ID)
            putExtra(OppoPodsAction.EXTRA_BT_LOG_IS_SEND, isSend)
            putExtra(OppoPodsAction.EXTRA_BT_LOG_HEX, packet.toHexString(HexFormat.UpperCase))
            if (label != null) putExtra(OppoPodsAction.EXTRA_BT_LOG_LABEL, label)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    private fun refreshPodsNotification() {
        val context = mContext ?: return
        if (!::mDevice.isInitialized) return

        if (!showConnectionNotificationEnabled) {
            cancelPodsNotificationByMiuiBt(context, mDevice)
            return
        }

        if (!::currentBatteryParams.isInitialized) return
        MiuiStrongToastUtil.showPodsNotificationByMiuiBt(
            context,
            currentBatteryParams,
            mDevice,
            notificationSettings,
            isRfcommConnected
        )
    }

    fun syncNotificationSettings(
        context: Context?,
        intent: Intent,
        refreshNotification: Boolean = false
    ) {
        val settings = NotificationSettings.fromIntent(intent, notificationSettings)
            .withUpdatedAtIfMissing()
        notificationSettings = settings
        context?.let { cacheNotificationSettings(it, settings) }
        Log.d(
            TAG,
            "Notification settings synced: batteryIsland=$showConnectionBatteryIslandEnabled, popup=$showConnectionPopupEnabled, popupDismiss=${connectionPopupDismissSeconds}s, show=$showConnectionNotificationEnabled, island=$notificationIslandStyleEnabled, updatedAt=${notificationSettings.updatedAt}"
        )
        if (refreshNotification) {
            refreshPodsNotification()
        }
    }

    private fun loadNotificationSettings(context: Context): NotificationSettings {
        reloadPrefs()
        val remoteSettings = NotificationSettings.fromPrefs(mPrefs)
        val cachedSettings = context.getSharedPreferences(
            OppoPodsPrefsKey.NOTIFICATION_SETTINGS_CACHE_PREFS_NAME,
            Context.MODE_PRIVATE
        ).let { NotificationSettings.fromPrefsOrNull(it) }
        if (
            remoteSettings.updatedAt == 0L &&
            mPrefs.contains(OppoPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION) &&
            !remoteSettings.showConnectionNotification
        ) {
            return remoteSettings
        }
        return NotificationSettings.newerOf(remoteSettings, cachedSettings)
    }

    private fun cacheNotificationSettings(context: Context, settings: NotificationSettings) {
        settings.withUpdatedAtIfMissing().writeToPrefs(
            context.getSharedPreferences(
                OppoPodsPrefsKey.NOTIFICATION_SETTINGS_CACHE_PREFS_NAME,
                Context.MODE_PRIVATE
            )
        )
    }

    fun handleUIEvent(intent: Intent, receiverContext: Context? = null) {
        when (intent.action) {
            OppoPodsAction.ACTION_PODS_UI_INIT -> {
                Log.i(TAG, "UI Init")
                if (::currentBatteryParams.isInitialized)
                    changeUIBatteryStatus(currentBatteryParams)
                broadcastResolvedProfile(activeProfile)
                changeUIAncStatus(currentAnc)
                changeUIGameModeStatus(currentGameMode)
                changeUIEqStatus()
                changeUISpatialAudioStatus(currentSpatialAudioMode)
                changeUISpatialSoundStatus(currentSpatialSound)
                changeUINoiseLevelStatus(currentNoiseLevel)
                changeUISmartAncLevel(currentSmartAncLevel)
                changeUIAutoPlayPauseStatus(currentAutoPlayPause)
                changeUIDualDeviceStatus(currentDualDevice)
                changeUIConnectedDevicesStatus(currentConnectedDevices)
                Intent(OppoPodsAction.ACTION_PODS_CONNECTED).apply {
                    this.putExtra("device_name", mDevice.name ?: cachedDeviceName)
                    this.`package` = BuildConfig.APPLICATION_ID
                    this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    mContext!!.sendBroadcast(this)
                }
                sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                    putExtra("device_name", mDevice.name ?: cachedDeviceName)
                }
            }
            OppoPodsAction.ACTION_ANC_SELECT -> {
                val status = intent.getIntExtra("status", 0)
                setANCMode(status)
            }
            OppoPodsAction.ACTION_REFRESH_STATUS -> {
                val allowReconnect = intent.getBooleanExtra(
                    OppoPodsAction.EXTRA_ALLOW_RFCOMM_RECONNECT,
                    false
                )
                queryStatus(allowReconnect)
            }
            OppoPodsAction.ACTION_GAME_MODE_SET -> {
                val enabled = intent.getBooleanExtra("enabled", false)
                setGameMode(enabled)
            }
            OppoPodsAction.ACTION_EQ_PRESET_SET -> {
                setEqPreset(intent.getIntExtra("id", -1))
            }
            OppoPodsAction.ACTION_EQ_PRESET_SAVE -> {
                saveEqPresetFromIntent(intent)
            }
            OppoPodsAction.ACTION_EQ_PRESET_DELETE -> {
                val entry = DeviceProfileStore.parseEqEntries(
                    intent.getStringExtra(OppoPodsAction.EXTRA_EQ_ENTRIES_JSON)
                ).firstOrNull()
                if (entry != null) deleteEqPreset(entry)
            }
            OppoPodsAction.ACTION_SPATIAL_AUDIO_SET -> {
                val mode = intent.getIntExtra("mode", SpatialAudioMode.OFF)
                setSpatialAudioMode(mode)
            }
            OppoPodsAction.ACTION_SPATIAL_SOUND_SET -> {
                val enabled = intent.getBooleanExtra("enabled", false)
                setSpatialSound(enabled)
            }
            OppoPodsAction.ACTION_NOISE_LEVEL_SET -> {
                val level = intent.getIntExtra("level", NoiseLevel.DEEP)
                setNoiseLevel(level)
            }
            OppoPodsAction.ACTION_AUTO_PLAY_PAUSE_SET -> {
                val enabled = intent.getBooleanExtra("enabled", false)
                setAutoPlayPause(enabled)
            }
            OppoPodsAction.ACTION_DUAL_DEVICE_SET -> {
                val enabled = intent.getBooleanExtra("enabled", false)
                setDualDevice(enabled)
            }
            OppoPodsAction.ACTION_CYCLE_ANC -> {
                cycleAnc()
            }
            OppoPodsAction.ACTION_NOTIFICATION_SETTINGS_CHANGED -> {
                syncNotificationSettings(receiverContext ?: mContext, intent, refreshNotification = true)
            }
            OppoPodsAction.ACTION_ACTIVE_PROFILE_CHANGED -> {
                val jsonStr = intent.getStringExtra(OppoPodsAction.EXTRA_PROFILE_JSON)
                activeProfile = runCatching {
                    if (jsonStr != null) DeviceProfileStore.parse(jsonStr)
                    else DeviceProfileStore.resolveProfile(
                        receiverContext ?: mContext!!, mPrefs, cachedDeviceName
                    )
                }.getOrDefault(activeProfile)
                Log.d(TAG, "Active device profile synced: ${activeProfile.name} (${activeProfile.id})")
            }
        }
    }

    private fun currentBatterySnapshot(): BatteryParams {
        return if (::currentBatteryParams.isInitialized) {
            BatteryParams(
                currentBatteryParams.left?.copy(),
                currentBatteryParams.right?.copy(),
                currentBatteryParams.case?.copy()
            )
        } else {
            BatteryParams()
        }
    }

    private fun batteryInfoToPodParams(
        info: BatteryParser.BatteryInfo?,
        previous: PodParams?,
        preserveMissing: Boolean
    ): PodParams {
        if (info != null) {
            return PodParams(info.level, info.isCharging, true, previous?.rawStatus ?: 0)
        }
        if (preserveMissing && previous != null) return previous.copy()
        return PodParams(0, false, false, previous?.rawStatus ?: 0)
    }

    private fun caseInfoToPodParams(
        info: BatteryParser.BatteryInfo?,
        previous: PodParams?,
        preserveMissing: Boolean
    ): PodParams {
        if (info != null && (info.level > 0 || info.isCharging)) {
            lastKnownCaseBattery = info.level
            lastKnownCaseCharging = info.isCharging
            return PodParams(info.level, info.isCharging, true, previous?.rawStatus ?: 0)
        }
        if (preserveMissing && previous != null) return previous.copy()
        return PodParams(lastKnownCaseBattery, lastKnownCaseCharging, false, previous?.rawStatus ?: 0)
    }

    fun handleBatteryChanged(result: BatteryParser.BatteryResult, preserveMissing: Boolean = false) {
        val previous = currentBatterySnapshot()
        val batteryParams = BatteryParams(
            left = batteryInfoToPodParams(result.left, previous.left, preserveMissing),
            right = batteryInfoToPodParams(result.right, previous.right, preserveMissing),
            case = caseInfoToPodParams(result.case, previous.case, preserveMissing)
        )
        publishBatteryParams(batteryParams)
        // 首次收到电量后补查一次自定义 EQ 列表，防止握手期间 0x8122 响应丢失
        // 导致连接后自定义音效列表为空（参考 OppoPodsManager 的 _wasConnected 补查逻辑）
        if (!firstBatteryReceived) {
            firstBatteryReceived = true
            if (activeProfile.customEqVisible) queryEqDetails()
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun publishBatteryParams(batteryParams: BatteryParams) {
        val context = mContext ?: return
        val left = batteryParams.left ?: PodParams()
        val right = batteryParams.right ?: PodParams()
        val case = batteryParams.case ?: PodParams()

        if (BuildConfig.DEBUG) {
            Log.v(
                TAG,
                "batt left ${left.battery}/${left.isCharging} right ${right.battery}/${right.isCharging} case ${case.battery}/${case.isCharging}"
            )
        }

        val shouldShowToast = !mShowedConnectedToast
        if (shouldShowToast) {
            // Wait until at least one connected ear has valid battery data
            val hasValidData = (left.isConnected && left.battery > 0) ||
                    (right.isConnected && right.battery > 0)
            if (!hasValidData) return
        }

        currentBatteryParams = batteryParams

        if (shouldShowToast) {
            mShowedConnectedToast = true
            if (showConnectionBatteryIslandEnabled) {
                MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(
                    context,
                    batteryParams,
                    notificationSettings
                )
            }
            if (showConnectionPopupEnabled) {
                showConnectionPopup(context, batteryParams)
            }
        }
        if (showConnectionNotificationEnabled) {
            MiuiStrongToastUtil.showPodsNotificationByMiuiBt(
                context,
                batteryParams,
                mDevice,
                notificationSettings,
                isRfcommConnected
            )
        } else {
            cancelPodsNotificationByMiuiBt(context, mDevice)
        }
        changeUIBatteryStatus(batteryParams)

        lastTempBatt = if (left.isConnected && right.isConnected)
            minOf(left.battery, right.battery)
        else if (left.isConnected)
            left.battery
        else if (right.isConnected)
            right.battery
        else SystemApisUtils.BATTERY_LEVEL_UNKNOWN

        setRegularBatteryLevel(lastTempBatt)
    }

    private fun showConnectionPopup(context: Context, batteryParams: BatteryParams) {
        try {
            Intent().apply {
                setClassName(BuildConfig.APPLICATION_ID, "moe.chenxy.oppopods.ConnectionPopupActivity")
                putBatteryStatus(batteryParams)
                putExtra(OppoPodsAction.EXTRA_COLOR_ID, currentColorId)
                putExtra("device_name", currentDeviceDisplayName())
                putExtra(
                    OppoPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECONDS,
                    connectionPopupDismissSeconds
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show connection popup", e)
        }
    }

    private fun currentDeviceDisplayName(): String {
        return if (::mDevice.isInitialized) {
            mDevice.alias?.takeIf { it.isNotBlank() }
                ?: mDevice.name
                ?: cachedDeviceName
        } else {
            cachedDeviceName
        }
    }

    private val routeCallback = object : MediaRouter2.RouteCallback() {
        override fun onRoutesUpdated(routes: List<MediaRoute2Info>) {
            Log.v(TAG, "routes updated: $routes")
            this@RfcommController.routes = routes
        }
    }

    private fun startRoutesScan() {
        runCatching {
            val executor = Executor { p0 ->
                CoroutineScope(Dispatchers.IO).launch { p0?.run() }
            }
            val preferredFeature = listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO, MediaRoute2Info.FEATURE_LIVE_VIDEO)
            mediaRouter.registerRouteCallback(executor, routeCallback, RouteDiscoveryPreference.Builder(preferredFeature, true).build())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                scanToken = mediaRouter.requestScan(MediaRouter2.ScanRequest.Builder().build())
            }
        }.onFailure { Log.w(TAG, "startRoutesScan failed", it) }
    }

    private fun stopRoutesScan() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                scanToken?.let { mediaRouter.cancelScanRequest(it as MediaRouter2.ScanToken) }
            }
            mediaRouter.unregisterRouteCallback(routeCallback)
        }.onFailure { Log.w(TAG, "stopRoutesScan failed", it) }
    }

    fun connectPod(context: Context, device: BluetoothDevice, prefs: SharedPreferences) {
        mContext = context
        mDevice = device
        mPrefs = prefs
        cachedDeviceName = device.name ?: ""
        currentColorId = -1
        reloadPrefs()
        DeviceModelRegistry.ensureLoaded(context)
        activeProfile = runCatching {
            DeviceProfileStore.resolveProfile(context, mPrefs, cachedDeviceName)
        }.getOrElse { DeviceProfileStore.fallbackProfile() }
        Log.d(
            TAG,
            "Active device profile: ${activeProfile.name} (${activeProfile.id}), " +
                    "mode=${DeviceProfileStore.profileMode(mPrefs).preferenceValue}"
        )
        notificationSettings = loadNotificationSettings(context)
        cacheNotificationSettings(context, notificationSettings)
        rfcommConnectionMethod = RfcommConnectionMethod.fromPreference(
            mPrefs.getString(RfcommConnectionMethod.PREF_KEY, null)
        )
        Log.d(
            TAG,
            "Notification settings initial: batteryIsland=$showConnectionBatteryIslandEnabled, popup=$showConnectionPopupEnabled, popupDismiss=${connectionPopupDismissSeconds}s, show=$showConnectionNotificationEnabled, island=$notificationIslandStyleEnabled"
        )
        Log.d(TAG, "RFCOMM connection method initial: ${rfcommConnectionMethod.preferenceValue}")

        context.registerReceiver(broadcastReceiver, IntentFilter().apply {
            this.addAction(OppoPodsAction.ACTION_ANC_SELECT)
            this.addAction(OppoPodsAction.ACTION_PODS_UI_INIT)
            this.addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
            this.addAction(OppoPodsAction.ACTION_GAME_MODE_SET)
            this.addAction(OppoPodsAction.ACTION_EQ_PRESET_SET)
            this.addAction(OppoPodsAction.ACTION_EQ_PRESET_SAVE)
            this.addAction(OppoPodsAction.ACTION_EQ_PRESET_DELETE)
            this.addAction(OppoPodsAction.ACTION_SPATIAL_AUDIO_SET)
            this.addAction(OppoPodsAction.ACTION_SPATIAL_SOUND_SET)
            this.addAction(OppoPodsAction.ACTION_NOISE_LEVEL_SET)
            this.addAction(OppoPodsAction.ACTION_AUTO_PLAY_PAUSE_SET)
            this.addAction(OppoPodsAction.ACTION_DUAL_DEVICE_SET)
            this.addAction(OppoPodsAction.ACTION_CYCLE_ANC)
            this.addAction(OppoPodsAction.ACTION_NOTIFICATION_SETTINGS_CHANGED)
            this.addAction(OppoPodsAction.ACTION_ACTIVE_PROFILE_CHANGED)
        }, Context.RECEIVER_EXPORTED)

        Intent(OppoPodsAction.ACTION_PODS_CONNECTED).apply {
            this.putExtra("device_name", cachedDeviceName)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
            putExtra("device_name", cachedDeviceName)
        }

        MediaControl.mContext = mContext
        mediaRouter = MediaRouter2.getInstance(mContext!!)
        startRoutesScan()

        isPodConnected = true
        firstBatteryReceived = false

        // Start persistent RFCOMM connection and battery polling
        CoroutineScope(Dispatchers.IO).launch {
            if (retryRfcommConnect()) {
                // Handshake packet has no response; keep only a short pacing delay.
                sendPacketSafe(OppoPackets.buildHandshake(), "handshake")
                delay(150.milliseconds)
                // Query product id: await the 0x8103 response. On a whitelist match
                // handleOppoPacket rebuilds activeProfile and fires the status
                // queries itself, so no blind waits are needed here.
                sendPacketSafe(OppoPackets.buildQueryProductId(), "query product id")
                val productIdReceived = awaitResponse(productIdResponse, timeoutMs = 2000)
                // Query earbud color id (official app sends 0x010B after the handshake).
                sendPacketSafe(OppoPackets.buildQueryColorId(), "query color id")
                // Query broadcast codes: await the 0x8200 response (its handler subscribes).
                sendPacketSafe(OppoPackets.buildQueryBroadcastCodes(), "query broadcast codes")
                awaitResponse(broadcastCodesResponse, timeoutMs = 1500)
                if (!productIdReceived) {
                    // Fallback status query when 0x8103 missed/timed out (the handler
                    // already fires it on a successful match, so avoid double-sending).
                    delay(150.milliseconds)
                    sendStatusQueryPackets()
                }
            }
        }

        // Start battery polling
        batteryPollJob = CoroutineScope(Dispatchers.IO).launch {
            delay(2.seconds) // Wait for initial connection
            while (isPodConnected) {
                delay(BATTERY_POLL_INTERVAL_MS.milliseconds)
                if (isPodConnected) {
                    queryStatus(allowReconnect = false)
                }
            }
        }
    }

    /**
     * Right after A2DP connects the SPP channel may not be ready yet, so the
     * first attempt often fails. Retry with bounded exponential backoff instead
     * of a single fixed 500ms delay: 250ms/500ms/1000ms between attempts, up to
     * 4 tries. If it still fails, later control/query operations reconnect via
     * sendPacketSafe(allowReconnect=true).
     */
    private suspend fun retryRfcommConnect(): Boolean {
        val maxAttempts = 4
        val baseDelayMs = 250L
        for (attempt in 0 until maxAttempts) {
            val reason = if (attempt == 0) "initial connect" else "initial connect retry #$attempt"
            if (connectRfcomm(reason)) return true
            if (attempt < maxAttempts - 1) delay((baseDelayMs * (1L shl attempt)).milliseconds)
        }
        Log.w(TAG, "Initial RFCOMM connect failed after $maxAttempts attempts; will retry on the next control/query operation")
        return false
    }

    private suspend fun awaitResponse(channel: Channel<Unit>, timeoutMs: Long): Boolean {
        return withTimeoutOrNull(timeoutMs) { channel.receive() } != null
    }

    private fun sendExternalPodsStatusBroadcast(action: String, fill: Intent.() -> Unit = {}) {
        val ctx = mContext ?: return
        listOf("com.milink.service", "com.xiaomi.bluetooth", "com.android.settings").forEach { targetPackage ->
            Intent(action).apply {
                if (::mDevice.isInitialized) {
                    putExtra("address", mDevice.address)
                    putExtra("device_name", mDevice.name ?: cachedDeviceName)
                }
                fill()
                setPackage(targetPackage)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                ctx.sendBroadcast(this)
            }
        }
    }

    private fun broadcastResolvedProfile(profile: DeviceProfile) {
        val context = mContext ?: return
        Intent(OppoPodsAction.ACTION_PODS_PROFILE_CHANGED).apply {
            setPackage(BuildConfig.APPLICATION_ID)
            putExtra(OppoPodsAction.EXTRA_PROFILE_JSON, DeviceProfileStore.exportJson(profile))
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    private fun refreshRfcommConnectionMethod() {
        if (::mPrefs.isInitialized) {
            reloadPrefs()
            rfcommConnectionMethod = RfcommConnectionMethod.fromPreference(
                mPrefs.getString(RfcommConnectionMethod.PREF_KEY, null)
            )
        }
    }

    private fun reloadPrefs() {
        if (!::mPrefs.isInitialized) return
        runCatching {
            mPrefs.javaClass.methods.firstOrNull {
                it.name == "reload" && it.parameterTypes.isEmpty()
            }?.invoke(mPrefs)
        }
    }

    private fun connectRfcomm(reason: String): Boolean {
        if (!isPodConnected || mContext == null || !::mDevice.isInitialized) {
            Log.d(TAG, "Skip RFCOMM connect: podConnected=$isPodConnected, reason=$reason")
            return false
        }

        synchronized(rfcommLock) {
            if (isRfcommConnected && socket != null) {
                return true
            }

            refreshRfcommConnectionMethod()
            closeRfcommSocketLocked()

            return try {
                Log.d(
                    TAG,
                    "RFCOMM connecting: reason=$reason, method=${rfcommConnectionMethod.preferenceValue}"
                )
                val connectedSocket = OppoRfcommSocketFactory.connect(
                    mDevice,
                    TAG,
                    rfcommConnectionMethod
                )
                socket = connectedSocket
                isRfcommConnected = true
                startPacketReader(connectedSocket)
                Log.d(TAG, "RFCOMM connected: reason=$reason")
                refreshPodsNotification()
                true
            } catch (e: IOException) {
                Log.e(TAG, "RFCOMM connect failed: reason=$reason", e)
                closeRfcommSocketLocked()
                false
            }
        }
    }

    private fun closeRfcommSocketLocked() {
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "RFCOMM socket close failed", e)
        } finally {
            socket = null
            isRfcommConnected = false
        }
    }

    private fun markRfcommDisconnected(
        reason: String,
        failedSocket: BluetoothSocket? = null,
        error: Throwable? = null
    ) {
        if (error != null) {
            Log.e(TAG, "RFCOMM disconnected: $reason", error)
        } else {
            Log.d(TAG, "RFCOMM disconnected: $reason")
        }

        synchronized(rfcommLock) {
            if (failedSocket == null || socket === failedSocket) {
                closeRfcommSocketLocked()
                refreshPodsNotification()
            }
        }
    }

    private fun isActiveRfcommSocket(targetSocket: BluetoothSocket): Boolean {
        return synchronized(rfcommLock) {
            isRfcommConnected && socket === targetSocket
        }
    }

    private fun startPacketReader(readerSocket: BluetoothSocket) {
        CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(1024)
            val framer = OppoPacketFramer()
            try {
                val inputStream = readerSocket.inputStream
                while (isPodConnected && isActiveRfcommSocket(readerSocket)) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        framer.append(buffer, bytesRead).forEach { packet ->
                            handleOppoPacket(packet)
                        }
                    } else if (bytesRead == -1) {
                        Log.d(TAG, "RFCOMM stream ended")
                        break
                    }
                }
            } catch (e: IOException) {
                if (isPodConnected && isActiveRfcommSocket(readerSocket)) {
                    markRfcommDisconnected("read error", readerSocket, e)
                }
                return@launch
            }

            if (isPodConnected && isActiveRfcommSocket(readerSocket)) {
                markRfcommDisconnected("reader stopped", readerSocket)
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun handleOppoPacket(packet: ByteArray) {
        sendBtLogBroadcast(isSend = false, packet = packet, label = BtLogLabeler.labelRecv(packet))
        if (BuildConfig.DEBUG) {
            Log.v(TAG, "Received: ${packet.toHexString(HexFormat.UpperCase)}")
        }

        // Try parse the earbud color id (official app: 0x010B -> 0x810B)
        val colorId = ColorIdParser.parse(packet)
        if (colorId != null) {
            Log.d(TAG, "Color id received: $colorId")
            currentColorId = colorId
            return
        }

        // Try parse as battery response (query response, Cmd=0x8106)
        val batteryResult = BatteryParser.parse(packet)
        if (batteryResult != null) {
            handleBatteryChanged(batteryResult)
            return
        }

        // Try parse as active battery report (unsolicited, Cmd=0x0204, type=0x01)
        val activeResult = BatteryParser.parseActiveReport(packet)
        if (activeResult != null) {
            handleBatteryChanged(activeResult, preserveMissing = true)
            return
        }

        // Try parse 0x8103 product id -> rebuild profile from the bundled model whitelist
        val productId = ProductIdParser.parse(packet)
        if (productId != null) {
            Log.d(TAG, "Product id received: $productId")
            productIdResponse.trySend(Unit)
            val context = mContext
            if (context != null) {
                val resolved = runCatching {
                    DeviceProfileStore.profileForProductId(context, mPrefs, productId)
                }.getOrNull()
                if (resolved != null) {
                    activeProfile = resolved
                    currentDeviceEqPresets = emptyList()
                    currentEqPresetId = -1
                    pendingDeletedEqIds.clear()
                    pendingSavedEqName = null
                    // profile 重建后允许下次电量回调再补查一次 EQ 列表
                    firstBatteryReceived = false
                    broadcastResolvedProfile(resolved)
                    changeUIEqStatus()
                    Log.d(TAG, "Auto-identified as ${resolved.name} ($productId)")
                    CoroutineScope(Dispatchers.IO).launch { sendStatusQueryPackets() }
                } else {
                    Log.d(TAG, "Product id $productId not applied (not in auto mode or unknown model)")
                }
            }
            return
        }

        val currentEq = EqParser.parseCurrent(packet)
        if (currentEq != null) {
            Log.d(TAG, "EQ preset received: $currentEq")
            currentEqPresetId = currentEq
            changeUIEqStatus()
            return
        }

        val deviceEqPresets = EqParser.parseAll(packet)
        if (deviceEqPresets.isNotEmpty() || EqParser.isAllResponse(packet)) {
            Log.d(TAG, "Device EQ presets received: ${deviceEqPresets.size}")
            val validEntries = deviceEqPresets.filter { it.id !in pendingDeletedEqIds }
            currentDeviceEqPresets = validEntries.map {
                EqDevicePreset(
                    id = it.id,
                    name = it.name,
                    selected = it.selected,
                    minValue = it.minValue,
                    maxValue = it.maxValue,
                    frequencies = it.frequencies,
                    gains = it.gains,
                )
            }
            validEntries.firstOrNull { it.selected }?.id?.let { currentEqPresetId = it }
            if (validEntries.isEmpty()) currentEqPresetId = -1
            pendingSavedEqName?.let { name ->
                currentDeviceEqPresets.firstOrNull { it.name == name }?.id?.let { id ->
                    currentEqPresetId = id
                    pendingSavedEqName = null
                }
            }
            changeUIEqStatus()
            return
        }

        // Try parse as ANC mode response
        val ancResult = AncModeParser.parse(
            packet,
            activeProfile.ancIndexToName,
            activeProfile.isLegacyAnc
        )
        if (ancResult != null) {
            Log.d(TAG, "ANC mode received: ${ancResult.mode}, noiseLevel=${ancResult.noiseLevel}")
            currentAnc = when (ancResult.mode) {
                NoiseControlMode.OFF -> 1
                NoiseControlMode.NOISE_CANCELLATION -> 2
                NoiseControlMode.TRANSPARENCY -> 3
                NoiseControlMode.ADAPTIVE -> 4
            }
            if (ancResult.noiseLevel != null) {
                currentNoiseLevel = ancResult.noiseLevel
                changeUINoiseLevelStatus(ancResult.noiseLevel)
            }
            changeUIAncStatus(currentAnc)
            return
        }

        // Smart-mode current noise-reduction level (cmd 0x0204 type 03 key 04)
        val smartLevel = SmartAncLevelParser.parse(packet)
        if (smartLevel != null) {
            Log.d(TAG, "Smart ANC current level: $smartLevel")
            if (smartLevel != currentSmartAncLevel) {
                currentSmartAncLevel = smartLevel
                changeUISmartAncLevel(smartLevel)
            }
            return
        }

        // Try parse as batch query response for game mode (Cmd=0x810D)
        val gameModeResult = GameModeParser.parseForFeature(packet, activeProfile.gameModeFeatureId())
        if (gameModeResult != null) {
            Log.d(TAG, "Game mode received: $gameModeResult")
            lastGameModeStatusUpdateMs = SystemClock.elapsedRealtime()
            currentGameMode = gameModeResult
            changeUIGameModeStatus(gameModeResult)
            return
        }

        // Try parse 0x8200 broadcast codes response
        val broadcastCodes = BroadcastCodesParser.parse(packet)
        if (broadcastCodes != null) {
            Log.d(TAG, "Broadcast codes received: $broadcastCodes")
            broadcastCodesResponse.trySend(Unit)
            CoroutineScope(Dispatchers.IO).launch {
                delay(100.milliseconds)
                sendPacketSafe(OppoPackets.buildSubscribeBroadcast(broadcastCodes), "subscribe broadcast")
            }
            return
        }

        // Try parse batch status for autoPlayPause / dualDevice / spatialSound
        val batchStatus = GameModeParser.parseStatus(packet)
        if (batchStatus != null) {
            batchStatus.autoPlayPause?.let {
                currentAutoPlayPause = it
                changeUIAutoPlayPauseStatus(it)
            }
            batchStatus.dualDevice?.let {
                currentDualDevice = it
                changeUIDualDeviceStatus(it)
            }
            batchStatus.spatialSound?.let {
                currentSpatialSound = it
                changeUISpatialSoundStatus(it)
            }
            return
        }

        // Try parse 0x0204 connected devices notification (eventCode=0x06)
        val connectedDevicesResult = ConnectedDevicesParser.parse(packet)
        if (connectedDevicesResult != null) {
            Log.d(TAG, "Connected devices received: $connectedDevicesResult")
            currentConnectedDevices = connectedDevicesResult
            currentConnectedDevicesReceived = true
            changeUIConnectedDevicesStatus(connectedDevicesResult)
            return
        }

        val spatialAudioResult = SpatialAudioParser.parseModeNotify(packet)
        if (spatialAudioResult != null) {
            Log.d(TAG, "Spatial audio mode received: $spatialAudioResult")
            currentSpatialAudioMode = spatialAudioResult
            changeUISpatialAudioStatus(spatialAudioResult)
            return
        }

        val spatialAudioSetStatus = SpatialAudioParser.parseSetResponseStatus(packet)
        if (spatialAudioSetStatus != null) {
            Log.d(TAG, "Spatial audio set response: $spatialAudioSetStatus")
            return
        }

        val setFeatureResult = SwitchFeatureSetParser.parse(packet)
        if (setFeatureResult != null) {
            Log.d(TAG, "Switch feature response: status=${setFeatureResult.status}, value=${setFeatureResult.value}")
            return
        }

        // Unknown packet - log in debug
        if (BuildConfig.DEBUG) {
            Log.v(TAG, "Unknown OPPO packet: ${packet.toHexString(HexFormat.UpperCase)}")
        }
    }

    fun disconnectedPod(context: Context, device: BluetoothDevice) {
        isPodConnected = false
        batteryPollJob?.cancel()

        synchronized(rfcommLock) {
            closeRfcommSocketLocked()
        }

        mContext?.let {
            stopRoutesScan()
            cancelPodsNotificationByMiuiBt(context, device)
            Intent(OppoPodsAction.ACTION_PODS_DISCONNECTED).apply {
                this.`package` = BuildConfig.APPLICATION_ID
                this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(this)
            }
            it.unregisterReceiver(broadcastReceiver)
        }

        mShowedConnectedToast = false
        lastKnownCaseBattery = 0
        lastKnownCaseCharging = false
        currentSpatialAudioMode = SpatialAudioMode.OFF
        currentSpatialSound = false
        currentEqPresetId = -1
        currentDeviceEqPresets = emptyList()
        pendingDeletedEqIds.clear()
        pendingSavedEqName = null
        currentConnectedDevices = emptyList()
        currentConnectedDevicesReceived = false
        cachedDeviceName = ""
        mContext = null
        MediaControl.mContext = null
    }

    /**
     * Releases process-owned resources before a LibXposed API 102 hot reload.
     * The replacement generation reconnects on the next Bluetooth state event.
     */
    fun shutdownForHotReload() {
        batteryPollJob?.cancel()
        batteryPollJob = null
        synchronized(rfcommLock) {
            closeRfcommSocketLocked()
        }
        mContext?.let { context ->
            runCatching { context.unregisterReceiver(broadcastReceiver) }
        }
        if (::mediaRouter.isInitialized) {
            runCatching { stopRoutesScan() }
        }
        scanToken = null
        routes = emptyList()
        isPodConnected = false
        isRfcommConnected = false
        mContext = null
        MediaControl.mContext = null
    }

    private fun writePacket(targetSocket: BluetoothSocket, packet: ByteArray, reason: String): Boolean {
        try {
            sendBtLogBroadcast(isSend = true, packet = packet, label = BtLogLabeler.labelSend(packet))
            targetSocket.outputStream.write(packet)
            targetSocket.outputStream.flush()
            return true
        } catch (e: IOException) {
            markRfcommDisconnected("send failed: $reason", targetSocket, e)
            return false
        }
    }

    private fun sendPacketSafe(
        packet: ByteArray,
        reason: String = "send packet",
        allowReconnect: Boolean = true
    ): Boolean {
        if (allowReconnect) {
            if (!connectRfcomm(reason)) return false
        }

        val targetSocket = synchronized(rfcommLock) { socket } ?: run {
            Log.d(TAG, "Skip packet: RFCOMM disconnected and reconnect not allowed, reason=$reason")
            return false
        }

        if (writePacket(targetSocket, packet, reason)) {
            return true
        }

        if (!allowReconnect) return false
        if (!connectRfcomm("$reason retry")) return false

        val retrySocket = synchronized(rfcommLock) { socket } ?: return false
        return writePacket(retrySocket, packet, "$reason retry")
    }

    fun setGameMode(enabled: Boolean) {
        Log.d(TAG, "setGameMode: $enabled")
        if (currentGameMode == enabled) {
            changeUIGameModeStatus(enabled)
            Log.d(TAG, "setGameMode skipped duplicate: $enabled")
            return
        }
        currentGameMode = enabled
        changeUIGameModeStatus(enabled)
        CoroutineScope(Dispatchers.IO).launch {
            sendGameModePackets(enabled)
        }
    }

    fun setSpatialAudioMode(mode: Int) {
        val normalizedMode = mode.coerceIn(SpatialAudioMode.OFF, SpatialAudioMode.HEAD_TRACKING)
        Log.d(TAG, "setSpatialAudioMode: $normalizedMode")
        currentSpatialAudioMode = normalizedMode
        changeUISpatialAudioStatus(normalizedMode)
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(activeProfile.spatialPacket(normalizedMode), "set spatial audio mode")
        }
    }

    fun setSpatialSound(enabled: Boolean) {
        Log.d(TAG, "setSpatialSound: $enabled")
        currentSpatialSound = enabled
        changeUISpatialSoundStatus(enabled)
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(activeProfile.spatialSoundPacket(enabled), "set spatial sound")
        }
    }

    fun setEqPreset(id: Int) {
        if (id < 0) return
        Log.d(TAG, "setEqPreset: $id")
        currentEqPresetId = id
        changeUIEqStatus()
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(activeProfile.eqPacket(id), "set EQ preset")
        }
    }

    private fun customEqFrequencies(): List<Int> =
        activeProfile.customEqFrequencies.ifEmpty { EqDefaults.FREQUENCIES }

    private fun saveEqPresetFromIntent(intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val name = intent.getStringExtra("name")?.trim().orEmpty()
        val frequencies = intent.getIntegerArrayListExtra("frequencies")?.toList().orEmpty()
        val gains = intent.getIntegerArrayListExtra("gains")?.toList().orEmpty()
        val minValue = intent.getIntExtra("min_value", -6)
        val maxValue = intent.getIntExtra("max_value", 6)
        saveEqPreset(id, name, frequencies, gains, minValue, maxValue)
    }

    fun saveEqPreset(
        id: Int,
        name: String,
        frequencies: List<Int>,
        gains: List<Int>,
        minValue: Int = -6,
        maxValue: Int = 6,
    ) {
        if (!activeProfile.customEqVisible || name.isBlank()) return
        pendingSavedEqName = name
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(
                OppoPackets.buildSaveEqualizer(
                    id = id,
                    name = name,
                    frequencies = frequencies.ifEmpty { customEqFrequencies() },
                    gains = gains,
                    minValue = minValue,
                    maxValue = maxValue,
                ),
                "save EQ preset",
            )
            delay(450.milliseconds)
            queryEqDetails()
        }
    }

    private fun deleteEqPreset(entry: EqDevicePreset) {
        if (!activeProfile.customEqVisible || entry.id <= 0) return
        pendingDeletedEqIds += entry.id
        if (currentEqPresetId == entry.id) {
            currentEqPresetId = -1
            changeUIEqStatus()
        }
        CoroutineScope(Dispatchers.IO).launch {
            val packet = if (entry.frequencies.isNotEmpty() && entry.gains.isNotEmpty()) {
                OppoPackets.buildDeleteEqualizer(entry)
            } else {
                OppoPackets.buildDeleteEqualizer(entry.id)
            }
            sendPacketSafe(packet, "delete EQ preset")
            delay(450.milliseconds)
            queryEqDetails()
            delay(900.milliseconds)
            pendingDeletedEqIds.remove(entry.id)
            queryEqDetails()
        }
    }

    private fun queryEqDetails() {
        if (!activeProfile.customEqVisible) return
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(activeProfile.packet(ProfileKeys.QUERY_EQ), "query EQ")
            delay(80.milliseconds)
            sendPacketSafe(activeProfile.packet(ProfileKeys.QUERY_EQ_ALL), "query all EQ presets")
        }
    }

    fun setNoiseLevel(level: Int) {
        Log.d(TAG, "setNoiseLevel: $level")
        currentNoiseLevel = level
        changeUINoiseLevelStatus(level)
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(activeProfile.noiseLevelPacket(level), "set noise level")
        }
    }

    fun setAutoPlayPause(enabled: Boolean) {
        Log.d(TAG, "setAutoPlayPause: $enabled")
        currentAutoPlayPause = enabled
        changeUIAutoPlayPauseStatus(enabled)
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(activeProfile.autoPlayPausePacket(enabled), "set auto play pause")
        }
    }

    fun setDualDevice(enabled: Boolean) {
        Log.d(TAG, "setDualDevice: $enabled")
        currentDualDevice = enabled
        currentConnectedDevicesReceived = false
        changeUIDualDeviceStatus(enabled)
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(activeProfile.dualDevicePacket(enabled), "set dual device")
        }
    }

    fun cycleAnc() {
        val next = when (currentAnc) {
            2 -> if (activeProfile.adaptiveVisible) 4 else 3  // NC → Adaptive（若启用）或 Transparency
            4 -> 3  // Adaptive → Transparency
            3 -> 1  // Transparency → OFF
            else -> 2  // OFF or unknown → NC
        }
        setANCMode(next)
    }

    fun setANCMode(mode: Int) {
        Log.d(TAG, "setANCMode: $mode")
        currentAnc = mode  // 乐观更新，与 AppRfcommController 保持一致
        if (mode !in 1..4) return
        val packet = activeProfile.ancPacket(mode)
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(packet, "set ANC mode")
        }
    }

    private suspend fun sendGameModePackets(enabled: Boolean) {
        for ((index, packet) in activeProfile.gameModePackets(enabled).withIndex()) {
            if (index > 0) delay(120.milliseconds)
            if (!sendPacketSafe(packet, "set game mode")) return
        }
    }

    private suspend fun sendStatusQueryPackets(allowReconnect: Boolean = false) {
        if (!sendPacketSafe(activeProfile.packet(ProfileKeys.QUERY_STATUS), "query status", allowReconnect)) return
        delay(50.milliseconds)
        if (!sendPacketSafe(activeProfile.packet(ProfileKeys.QUERY_BATTERY), "query battery", allowReconnect)) return
        delay(50.milliseconds)
        if (!sendPacketSafe(activeProfile.packet(ProfileKeys.QUERY_ANC), "query ANC", allowReconnect)) return
        if (activeProfile.eqPresets.isNotEmpty()) {
            delay(50.milliseconds)
            if (!sendPacketSafe(activeProfile.packet(ProfileKeys.QUERY_EQ), "query EQ", allowReconnect)) return
        }
        if (activeProfile.customEqVisible) {
            delay(50.milliseconds)
            sendPacketSafe(activeProfile.packet(ProfileKeys.QUERY_EQ_ALL), "query all EQ presets", allowReconnect)
        }
    }

    /**
     * Combo query strategy: send batch query (wake + game mode), then battery, then ANC.
     */
    fun queryStatus(allowReconnect: Boolean = false) {
        CoroutineScope(Dispatchers.IO).launch {
            sendStatusQueryPackets(allowReconnect)
        }
    }

    fun setRegularBatteryLevel(level: Int) {
        try {
            val service = mContext?.let { ctx ->
                var cls: Class<*>? = ctx.javaClass
                while (cls != null) {
                    runCatching {
                        return@let cls.getDeclaredField("mAdapterService").apply { isAccessible = true }.get(ctx)
                    }
                    cls = cls.superclass
                }
                null
            } ?: return
            var cls: Class<*>? = service.javaClass
            while (cls != null) {
                runCatching {
                    val method = cls.getDeclaredMethod(
                        "setBatteryLevel",
                        BluetoothDevice::class.java,
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType
                    )
                    method.isAccessible = true
                    method.invoke(service, mDevice, level, false)
                    return
                }
                cls = cls.superclass
            }
        } catch (e: Exception) {
            Log.e(TAG, "setRegularBatteryLevel failed", e)
        }
    }
}
