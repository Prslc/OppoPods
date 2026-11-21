package moe.chenxy.oppopods.hook

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.xzakota.hyper.notification.focus.FocusNotification
import moe.chenxy.oppopods.pods.DeviceProfileStore
import moe.chenxy.oppopods.utils.BatteryIconRes
import moe.chenxy.oppopods.utils.FocusIslandUtil
import moe.chenxy.oppopods.utils.SystemApisUtils
import moe.chenxy.oppopods.utils.SystemApisUtils.cancelAsUser
import moe.chenxy.oppopods.utils.SystemApisUtils.notifyAsUser
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.NotificationSettings
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsPrefsKey
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
import moe.chenxy.oppopods.R

@SuppressLint("MissingPermission")
object MiBluetoothToastHook : HookContext() {
    private const val NOTIFICATION_ID = 10003
    private const val NOTIFICATION_TAG_PREFIX = "BTHeadset"
    private const val LEGACY_ISLAND_NOTIFICATION_TAG_PREFIX = "BTHeadsetIsland"
    private const val CONNECTION_CHANNEL_ID = "oppopods_connection_notification"
    private const val CONNECTION_CHANNEL_NAME = "OppoPods"
    private const val PENDING_INTENT_FLAGS =
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    // ANC 模式本地缓存，用于循环切换和状态同步（1=关 2=降噪 3=通透 4=自适应）
    // 通过接收 ACTION_PODS_ANC_CHANGED 广播与 RfcommController 保持同步
    private var localAncMode = 1
    private var notificationReceiver: BroadcastReceiver? = null
    private var notificationReceiverContext: Context? = null

    override fun onHook() {
        var notificationSettings = NotificationSettings.fromPrefs(prefs)
        val lastNotificationIslandStyle = mutableMapOf<String, Boolean>()
        var lastNotificationDevice: BluetoothDevice? = null
        var lastNotificationBatteryParams: BatteryParams? = null
        var lastNotificationRfcommConnected: Boolean = true

        fun notificationTag(address: String): String {
            return "$NOTIFICATION_TAG_PREFIX$address"
        }

        fun legacyIslandNotificationTag(address: String): String {
            return "$LEGACY_ISLAND_NOTIFICATION_TAG_PREFIX$address"
        }

        fun cancelNotificationByTag(notificationManager: NotificationManager, tag: String) {
            notificationManager.cancelAsUser(
                tag,
                NOTIFICATION_ID,
                SystemApisUtils.getUserAllUserHandle()
            )
        }

        fun cancelAllPodsNotifications(context: Context) {
            try {
                val notificationManager = context.getSystemService("notification") as NotificationManager
                notificationManager.activeNotifications
                    .filter {
                        it.id == NOTIFICATION_ID &&
                            (it.tag?.startsWith(NOTIFICATION_TAG_PREFIX) == true ||
                                it.tag?.startsWith(LEGACY_ISLAND_NOTIFICATION_TAG_PREFIX) == true)
                    }
                    .mapNotNull { it.tag }
                    .forEach { cancelNotificationByTag(notificationManager, it) }
                lastNotificationIslandStyle.clear()
                lastNotificationDevice = null
                lastNotificationBatteryParams = null
            } catch (e: Exception) {
                Log.e("OppoPods", "Failed to cancel active Pod Notifications!", e)
            }
        }

        fun cachedNotificationSettings(context: Context): NotificationSettings? {
            return context.getSharedPreferences(
                OppoPodsPrefsKey.NOTIFICATION_SETTINGS_CACHE_PREFS_NAME,
                Context.MODE_PRIVATE
            ).let { NotificationSettings.fromPrefsOrNull(it) }
        }

        fun cacheNotificationSettings(context: Context, settings: NotificationSettings) {
            settings.withUpdatedAtIfMissing().writeToPrefs(
                context.getSharedPreferences(
                    OppoPodsPrefsKey.NOTIFICATION_SETTINGS_CACHE_PREFS_NAME,
                    Context.MODE_PRIVATE
                )
            )
        }

        fun loadNotificationSettings(context: Context): NotificationSettings {
            reloadRemotePrefs()
            val remoteSettings = NotificationSettings.fromPrefs(prefs)
            if (
                remoteSettings.updatedAt == 0L &&
                prefs.contains(OppoPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION) &&
                !remoteSettings.showConnectionNotification
            ) {
                return remoteSettings
            }
            return NotificationSettings.newerOf(remoteSettings, cachedNotificationSettings(context))
        }

        fun effectiveNotificationSettings(
            intent: Intent? = null,
            context: Context? = null
        ): NotificationSettings {
            val intentSettings = NotificationSettings.fromIntent(intent, notificationSettings)
            return NotificationSettings.newerOf(
                intentSettings,
                context?.let { cachedNotificationSettings(it) }
            )
        }

        fun syncNotificationSettings(context: Context, intent: Intent) {
            notificationSettings = NotificationSettings.fromIntent(intent, notificationSettings)
                .withUpdatedAtIfMissing()
            cacheNotificationSettings(context, notificationSettings)
            Log.d(
                "OppoPods",
                "Notification settings synced in MiBluetooth: batteryIsland=${notificationSettings.showConnectionBatteryIsland}, popup=${notificationSettings.showConnectionPopup}, popupDismiss=${notificationSettings.connectionPopupDismissSeconds}s, show=${notificationSettings.showConnectionNotification}, island=${notificationSettings.notificationIslandStyle}, updatedAt=${notificationSettings.updatedAt}"
            )
        }

        fun deleteIntent(context: Context, bluetoothDevice: BluetoothDevice): PendingIntent? {
            val intent = Intent("com.android.bluetooth.headset.notification.cancle").apply {
                setPackage("com.android.bluetooth")
                putExtra("android.bluetooth.device.extra.DEVICE", bluetoothDevice)
            }
            return PendingIntent.getBroadcast(context, 0, intent, PENDING_INTENT_FLAGS)
        }

        fun createPodsNotification(
            bluetoothDevice: BluetoothDevice?,
            context: Context,
            batteryParams: BatteryParams,
            showNotificationAsIsland: Boolean = notificationSettings.showNotificationAsIsland,
            rfcommConnected: Boolean = true
        ) {
            val miheadset_notification_Box = context.resources.getIdentifier("miheadset_notification_Box", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_LeftEar = context.resources.getIdentifier("miheadset_notification_LeftEar", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_RightEar = context.resources.getIdentifier("miheadset_notification_RightEar", "string", "com.xiaomi.bluetooth")
            val system_notification_accent_color = context.resources.getIdentifier("system_notification_accent_color", "color", "android")
            if (bluetoothDevice == null) {
                Log.e("OppoPods", "createPodsNotification: btDevice null")
                return
            }
            try {
                val address: String = bluetoothDevice.address
                var alias: String? = bluetoothDevice.alias
                if (alias?.isEmpty() == true) {
                    alias = bluetoothDevice.name
                }
                val notificationTitle = if (rfcommConnected) {
                    alias ?: ""
                } else {
                    "${alias ?: ""}（已断开）"
                }

                val caseBattStr = if (batteryParams.case != null && batteryParams.case!!.isConnected)
                    "${context.resources.getString(miheadset_notification_Box)}：${batteryParams.case!!.battery} %" +
                            "${if (batteryParams.case!!.isCharging) " ⚡" else ""}\n"
                else ""
                val leftEar = if (batteryParams.left != null && batteryParams.left!!.isConnected)
                    "${context.resources.getString(miheadset_notification_LeftEar)}：${batteryParams.left!!.battery} %" +
                        (if (batteryParams.left!!.isCharging) " ⚡" else "")
                else ""
                val leftToRight = if (batteryParams.left?.isConnected == true && batteryParams.right?.isConnected == true) " | " else ""
                val rightEar = if (batteryParams.right != null && batteryParams.right!!.isConnected)
                    "$leftToRight${context.resources.getString(miheadset_notification_RightEar)}：${batteryParams.right!!.battery} %" +
                        (if (batteryParams.right!!.isCharging) " ⚡" else "")
                else ""

                val contentText: String = caseBattStr + leftEar + rightEar
                val notificationManager = context.getSystemService("notification") as NotificationManager
                val activeNotificationTag = notificationTag(address)
                val previousIslandStyle = lastNotificationIslandStyle[address]
                cancelNotificationByTag(notificationManager, legacyIslandNotificationTag(address))
                if (previousIslandStyle == true && !showNotificationAsIsland) {
                    cancelNotificationByTag(notificationManager, activeNotificationTag)
                }
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        CONNECTION_CHANNEL_ID,
                        CONNECTION_CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        setSound(null, null)
                        setAllowBubbles(true)
                    }
                )
                // 循环切换降噪模式：降噪 → 自适应 → 通透 → 关，指定 package 确保广播路由到 com.android.bluetooth 进程
                val ancCycleIntent = Intent(OppoPodsAction.ACTION_CYCLE_ANC)
                ancCycleIntent.setPackage("com.android.bluetooth")
                ancCycleIntent.setIdentifier("BTHeadset$address")
                val moduleContext = context.createPackageContext(
                    "moe.chenxy.oppopods", Context.CONTEXT_IGNORE_SECURITY
                )
                val moduleResources = moduleContext.resources
                // 按名字解析资源 ID，避免模块更新后资源 ID 移位导致跨进程取到错图；
                // release 的 resopt 会折叠资源名，查找失败时回退到编译期 R 常量。
                val boxId = moduleResources.getIdentifier("img_box", "drawable", "moe.chenxy.oppopods")
                    .takeIf { it != 0 } ?: R.drawable.img_box
                val boxBitmap = BitmapFactory.decodeResource(moduleResources, boxId)
                val headsetIcon = if (boxBitmap != null) {
                    Icon.createWithBitmap(boxBitmap)
                } else {
                    // 取图失败也不能让整条通知创建失败
                    Icon.createWithResource(context, android.R.drawable.stat_sys_data_bluetooth)
                }

                fun buildCustomViews(): Pair<RemoteViews, RemoteViews> {
                    val packageName = moduleContext.packageName
                    val big = RemoteViews(packageName, R.layout.notification_pods_big)
                    val small = RemoteViews(packageName, R.layout.notification_pods_small)
                    val segmentColor = moduleContext.getColor(R.color.notification_pods_segment_text)
                    val segmentSelectedColor =
                        moduleContext.getColor(R.color.notification_pods_segment_selected_text)

                    big.setTextViewText(R.id.tv_notification_device_name, notificationTitle)
                    small.setTextViewText(R.id.tv_notification_small_title, notificationTitle)
                    if (boxBitmap != null) {
                        big.setImageViewBitmap(R.id.iv_notification_device_icon, boxBitmap)
                        small.setImageViewBitmap(R.id.iv_notification_small_icon, boxBitmap)
                    }

                    fun bindBattery(
                        rowId: Int,
                        iconId: Int,
                        pctId: Int,
                        pod: PodParams?
                    ): Boolean {
                        val connected = pod?.isConnected == true
                        big.setViewVisibility(rowId, if (connected) View.VISIBLE else View.GONE)
                        if (connected && pod != null) {
                            big.setTextViewText(pctId, "${pod.battery}%")
                            big.setImageViewResource(
                                iconId,
                                BatteryIconRes.forLevel(pod.battery, pod.isCharging)
                            )
                        }
                        return connected
                    }

                    val leftConnected = bindBattery(
                        R.id.row_notification_left,
                        R.id.iv_notification_left_battery,
                        R.id.tv_notification_left_pct,
                        batteryParams.left
                    )
                    val rightConnected = bindBattery(
                        R.id.row_notification_right,
                        R.id.iv_notification_right_battery,
                        R.id.tv_notification_right_pct,
                        batteryParams.right
                    )
                    val caseConnected = bindBattery(
                        R.id.row_notification_case,
                        R.id.iv_notification_case_battery,
                        R.id.tv_notification_case_pct,
                        batteryParams.case
                    )

                    val stateParts = mutableListOf<String>()
                    if (leftConnected) stateParts +=
                        "${moduleContext.getString(R.string.connection_popup_left_ear)} ${batteryParams.left!!.battery}%"
                    if (rightConnected) stateParts +=
                        "${moduleContext.getString(R.string.connection_popup_right_ear)} ${batteryParams.right!!.battery}%"
                    if (caseConnected) stateParts +=
                        "${moduleContext.getString(R.string.connection_popup_case)} ${batteryParams.case!!.battery}%"
                    small.setTextViewText(
                        R.id.tv_notification_small_state,
                        stateParts.joinToString("  ")
                    )

                    val adaptiveVisible = runCatching {
                        DeviceProfileStore.resolveProfile(context, prefs)
                    }.getOrNull()?.adaptiveVisible == true
                    big.setViewVisibility(
                        R.id.tv_notification_anc_adaptive,
                        if (adaptiveVisible) View.VISIBLE else View.GONE
                    )

                    val segments = mutableListOf<Pair<Int, Int>>()
                    segments += R.id.tv_notification_anc_transparency to 3
                    segments += R.id.tv_notification_anc_on to 2
                    segments += R.id.tv_notification_anc_off to 1
                    if (adaptiveVisible) segments += R.id.tv_notification_anc_adaptive to 4
                    for ((viewId, mode) in segments) {
                        if (localAncMode == mode) {
                            big.setInt(
                                viewId,
                                "setBackgroundResource",
                                R.drawable.notification_pods_segment_selected
                            )
                            big.setTextColor(viewId, segmentSelectedColor)
                        } else {
                            big.setInt(viewId, "setBackgroundColor", android.graphics.Color.TRANSPARENT)
                            big.setTextColor(viewId, segmentColor)
                        }
                        val intent = Intent(OppoPodsAction.ACTION_ANC_SELECT).apply {
                            setPackage("com.android.bluetooth")
                            putExtra("status", mode)
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        }
                        big.setOnClickPendingIntent(
                            viewId,
                            PendingIntent.getBroadcast(
                                context,
                                300 + mode,
                                intent,
                                PENDING_INTENT_FLAGS
                            )
                        )
                    }

                    return small to big
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    Intent("chen.action.oppopods.show_pods_ui").apply {
                        setClassName("moe.chenxy.oppopods", "moe.chenxy.oppopods.PopupActivity")
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val focusExtras = if (showNotificationAsIsland) FocusNotification.buildV3 {
                    val logo = createPicture("key_headset", headsetIcon)
                    enableFloat = true
                    ticker = notificationTitle
                    updatable = true
//                    tickerPic = logo

                    iconTextInfo {
                        animIconInfo{
                            type = 0
                            src = logo
                        }
                        title = notificationTitle
                        content = contentText
                    }

                    island {
                        islandProperty = 1
                        bigIslandArea {
                            imageTextInfoLeft {
                                type = 1
                                picInfo {
                                    type = 1
                                    pic = logo
                                }
                            }
                            imageTextInfoRight {
                                type = 2
                                textInfo {
                                    title = notificationTitle
                                    content = contentText
                                }
                            }
                        }
                    }


                    textButton {
                        addActionInfo {
                            val ancLabel = moduleContext.getString(R.string.cycle_anc)
                            val ancAction = Notification.Action.Builder(
                                Icon.createWithResource(context, android.R.drawable.ic_lock_silent_mode),
                                ancLabel,
                                PendingIntent.getBroadcast(context, 1, ancCycleIntent, PENDING_INTENT_FLAGS)
                            ).build()
                            action = createAction("key_anc_cycle", ancAction)
                            actionTitle = ancLabel
                        }
                    }
                } else null
                // AOD 息屏显示：左右耳电量拼合后注入 aodTitle
                if (focusExtras != null) {
                    val aodParts = mutableListOf<String>()
                    if (batteryParams.left?.isConnected == true)
                        aodParts.add("L ${batteryParams.left!!.battery}%")
                    if (batteryParams.right?.isConnected == true)
                        aodParts.add("R ${batteryParams.right!!.battery}%")
                    val aodTitle = aodParts.joinToString(" | ")
                    try {
                        val json = org.json.JSONObject(focusExtras.getString("miui.focus.param") ?: "{}")
                        val pv2 = json.optJSONObject("param_v2") ?: org.json.JSONObject()
                        pv2.put("aodTitle", aodTitle)
                        pv2.put("aodPic", "key_headset")
                        json.put("param_v2", pv2)
                        focusExtras.putString("miui.focus.param", json.toString())
                    } catch (_: Exception) {}
                }
                val customViews = buildCustomViews()
                val builder = Notification.Builder(context, CONNECTION_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setWhen(0L)
                    .setTicker(notificationTitle)
                    .setDefaults(-1)
                    .setContentTitle(notificationTitle)
                    .setContentText(contentText)
                    .setContentIntent(pendingIntent)
                    .setDeleteIntent(deleteIntent(context, bluetoothDevice))
                    .setColor(context.getColor(system_notification_accent_color))
                    .apply { focusExtras?.let { addExtras(it) } }
                    .addExtras(Bundle().apply {
                        putString("android.substName", moduleContext.getString(R.string.app_name))
                    })
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                if (customViews != null) {
                    builder.setStyle(Notification.DecoratedCustomViewStyle())
                        .setCustomContentView(customViews.first)
                        .setCustomBigContentView(customViews.second)
                }
                notificationManager.notifyAsUser(
                    activeNotificationTag,
                    NOTIFICATION_ID,
                    builder.build(),
                    SystemApisUtils.getUserAllUserHandle()
                )
                lastNotificationIslandStyle[address] = showNotificationAsIsland
                lastNotificationDevice = bluetoothDevice
                lastNotificationBatteryParams = batteryParams
                lastNotificationRfcommConnected = rfcommConnected
            } catch (e: Exception) {
                Log.e("OppoPods", "Failed to create Pod Notification", e)
            }
        }

        fun cancelNotification(bluetoothDevice: BluetoothDevice, context: Context) {
            try {
                val address = bluetoothDevice.address
                if (address.isNotEmpty()) {
                    val notificationManager = context.getSystemService("notification") as NotificationManager
                    cancelNotificationByTag(notificationManager, notificationTag(address))
                    cancelNotificationByTag(notificationManager, legacyIslandNotificationTag(address))
                    lastNotificationIslandStyle.remove(address)
                    lastNotificationDevice = null
                    lastNotificationBatteryParams = null
                }
            } catch (e: Exception) {
                Log.e("OppoPods", "Failed to cancel Pod Notification!", e)
            }
        }


        fun registerNotificationReceiver(context: Context) {
            if (notificationReceiver != null) return
            notificationSettings = loadNotificationSettings(context)
            cacheNotificationSettings(context, notificationSettings)
            if (!notificationSettings.showConnectionNotification) {
                cancelAllPodsNotifications(context)
            }

            val broadcastReceiver = object : BroadcastReceiver() {
                override fun onReceive(p0: Context?, p1: Intent?) {
                    if (p1?.action == "chen.action.oppopods.sendstrongtoast") {
                        val settings = effectiveNotificationSettings(p1, context)
                        if (!settings.showConnectionBatteryIsland) {
                            Log.d("OppoPods", "Temporary battery island suppressed by settings")
                            return
                        }
                        val batteryParams = p1.getParcelableExtra(
                            "batteryParams",
                            BatteryParams::class.java
                        ) ?: return
                        FocusIslandUtil.showBatteryIsland(
                            context,
                            batteryParams
                        )
                    } else if (p1?.action == "chen.action.oppopods.updatepodsnotification") {
                        val batteryParams = p1.getParcelableExtra("batteryParams", BatteryParams::class.java)
                        val device = p1.getParcelableExtra("device", BluetoothDevice::class.java)
                        val settings = effectiveNotificationSettings(p1, context)
                        if (settings.showConnectionNotification && batteryParams != null) {
                            createPodsNotification(
                                device,
                                context,
                                batteryParams,
                                settings.showNotificationAsIsland,
                                p1.getBooleanExtra(OppoPodsAction.EXTRA_RFCOMM_CONNECTED, true)
                            )
                        } else if (device != null) {
                            cancelNotification(device, context)
                        } else {
                            cancelAllPodsNotifications(context)
                        }
                    } else if (p1?.action == "chen.action.oppopods.cancelpodsnotification") {
                        val device = p1.getParcelableExtra(
                            "device",
                            BluetoothDevice::class.java
                        ) ?: return
                        cancelNotification(device, context)
                    } else if (p1?.action == OppoPodsAction.ACTION_PODS_ANC_CHANGED) {
                        // 同步耳机实际 ANC 状态到本地缓存，确保下次循环切换时状态准确
                        localAncMode = p1.getIntExtra("status", 1)
                        val lastDevice = lastNotificationDevice
                        val lastBattery = lastNotificationBatteryParams
                        if (lastDevice != null && lastBattery != null) {
                            createPodsNotification(
                                lastDevice,
                                context,
                                lastBattery,
                                notificationSettings.showNotificationAsIsland,
                                lastNotificationRfcommConnected
                            )
                        }
                    } else if (p1?.action == OppoPodsAction.ACTION_NOTIFICATION_SETTINGS_CHANGED) {
                        syncNotificationSettings(context, p1)
                        val lastDevice = lastNotificationDevice
                        val lastBatteryParams = lastNotificationBatteryParams
                        if (!notificationSettings.showConnectionNotification) {
                            if (lastDevice != null) {
                                cancelNotification(lastDevice, context)
                            } else {
                                cancelAllPodsNotifications(context)
                            }
                        } else if (lastDevice != null && lastBatteryParams != null) {
                            createPodsNotification(
                                lastDevice,
                                context,
                                lastBatteryParams,
                                notificationSettings.showNotificationAsIsland,
                                lastNotificationRfcommConnected
                            )
                        }
                    } else if (p1?.action == OppoPodsAction.ACTION_CYCLE_ANC) {
                        // 循环切换降噪模式：按当前机型的 adaptiveVisible 决定是否经过自适应档。
                        val profile = runCatching {
                            DeviceProfileStore.resolveProfile(context, prefs)
                        }.getOrNull()
                        val adaptiveEnabled = profile?.adaptiveVisible == true
                        localAncMode = when (localAncMode) {
                            2 -> if (adaptiveEnabled) 4 else 3  // NC → Adaptive（若启用）或 Transparency
                            4 -> 3  // Adaptive → Transparency
                            3 -> 1  // Transparency → OFF
                            else -> 2  // OFF → NC
                        }
                        Intent(OppoPodsAction.ACTION_ANC_SELECT).apply {
                            setPackage("com.android.bluetooth")
                            putExtra("status", localAncMode)
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                            p0?.sendBroadcast(this)
                        }
                    }
                }
            }

            val intentFilter = IntentFilter("chen.action.oppopods.sendstrongtoast")
            intentFilter.addAction("chen.action.oppopods.updatepodsnotification")
            intentFilter.addAction("chen.action.oppopods.cancelpodsnotification")
            intentFilter.addAction(OppoPodsAction.ACTION_CYCLE_ANC)
            // 监听耳机实际 ANC 状态变更广播，保持 localAncMode 与 RfcommController 同步
            intentFilter.addAction(OppoPodsAction.ACTION_PODS_ANC_CHANGED)
            intentFilter.addAction(OppoPodsAction.ACTION_NOTIFICATION_SETTINGS_CHANGED)
            context.registerReceiver(broadcastReceiver, intentFilter,
                Context.RECEIVER_EXPORTED)
            notificationReceiver = broadcastReceiver
            notificationReceiverContext = context.applicationContext ?: context
        }

        hookConstructorAfter(findConstructorByParamCount("com.android.bluetooth.ble.app.MiuiBluetoothNotification", 2)) {
            val context = getObjectField(instance, "mContext") as Context
            registerNotificationReceiver(context)
        }

        // 模块热重载后 MiuiBluetoothNotification 已经构造过，不会再触发构造函数回调；
        // 此时直接沿用应用上下文注册接收器，否则模块更新后通知与超级岛会永久失效。
        currentApplicationContext()?.let { registerNotificationReceiver(it) }
    }

    private fun currentApplicationContext(): Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()

    override fun onHotReloading() {
        notificationReceiver?.let { receiver ->
            runCatching { notificationReceiverContext?.unregisterReceiver(receiver) }
        }
        notificationReceiver = null
        notificationReceiverContext = null
    }
}
