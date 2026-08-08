package moe.chenxy.oppopods.hook

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.util.Log
import moe.chenxy.oppopods.pods.RfcommController
import moe.chenxy.oppopods.utils.SystemApisUtils.setIconVisibility
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction

@SuppressLint("StaticFieldLeak", "MissingPermission")
object HeadsetStateDispatcher : HookContext() {
    private const val TAG = "OppoPods-Bluetooth"
    private var notificationSettingsReceiverRegistered = false
    private var notificationSettingsContext: Context? = null
    private var notificationSettingsReceiver: BroadcastReceiver? = null

    override fun onHook() {
        hookA2dpConnectionState()
        reconnectConnectedPod()
    }

    @SuppressLint("PrivateApi")
    private fun reconnectConnectedPod() {
        val ctx = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? Context
        }.getOrNull() ?: return
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                if (profile != BluetoothProfile.A2DP || proxy !is BluetoothA2dp) return
                runCatching {
                    for (device in proxy.connectedDevices) {
                        if (isOppoPod(device)) {
                            Log.d(TAG, "reconnect after hot reload: ${runCatching { device.address }.getOrNull()} ${runCatching { device.name }.getOrNull()}")
                            RfcommController.connectPod(ctx, device, prefs)
                            return
                        }
                    }
                }.onFailure { Log.w(TAG, "reconnectConnectedPod failed", it) }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
    }

    // The A2dpService connection-state callback name differs across system
    // versions: connectionStateChanged on newer HyperOS/Android, the older
    // handleConnectionStateChanged elsewhere. Try each and only skip failures,
    // so a missing method never crashes the whole bluetooth-process module load.
    private fun hookA2dpConnectionState() {
        val className = "com.android.bluetooth.a2dp.A2dpService"
        val candidateMethods = listOf("connectionStateChanged", "handleConnectionStateChanged")
        for (methodName in candidateMethods) {
            val hooked = runCatching {
                hookAfter(findMethodByParamCount(className, methodName, 3)) {
                    val currState = args[2] as Int
                    val fromState = args[1] as Int
                    val device = args[0] as BluetoothDevice?
                    val handler = getObjectField(instance, "mHandler") as Handler
                    if (device == null || currState == fromState) {
                        return@hookAfter
                    }
                    handler.post {
                        Log.d(TAG, "A2DP Connection State: $currState, isOppoPod ${isOppoPod(device)}")
                        val context = instance as ContextWrapper
                        registerNotificationSettingsReceiver(context)
                        if (!isOppoPod(device)) return@post

                        val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
                        if (currState == BluetoothHeadset.STATE_CONNECTED) {
                            statusBarManager.setIconVisibility("wireless_headset", true)
                            RfcommController.connectPod(context, device, prefs)
                        } else if (currState == BluetoothHeadset.STATE_DISCONNECTING || currState == BluetoothHeadset.STATE_DISCONNECTED) {
                            statusBarManager.setIconVisibility("wireless_headset", false)
                            RfcommController.disconnectedPod(context, device)
                        }
                    }
                }
                true
            }.onSuccess {
                Log.d(TAG, "hooked A2dpService.$methodName")
            }.onFailure {
                Log.w(TAG, "hook A2dpService.$methodName skipped", it)
            }.getOrDefault(false)
            if (hooked) return
        }
    }

    override fun onHotReloading() {
        notificationSettingsReceiver?.let { receiver ->
            runCatching { notificationSettingsContext?.unregisterReceiver(receiver) }
        }
        notificationSettingsReceiver = null
        notificationSettingsContext = null
        notificationSettingsReceiverRegistered = false
        RfcommController.shutdownForHotReload()
    }

    private fun registerNotificationSettingsReceiver(context: Context) {
        if (notificationSettingsReceiverRegistered) return
        val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    if (intent?.action != OppoPodsAction.ACTION_NOTIFICATION_SETTINGS_CHANGED) return
                    RfcommController.syncNotificationSettings(
                        receiverContext ?: context,
                        intent,
                        refreshNotification = false
                    )
                }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(OppoPodsAction.ACTION_NOTIFICATION_SETTINGS_CHANGED),
            Context.RECEIVER_EXPORTED
        )
        notificationSettingsContext = context.applicationContext ?: context
        notificationSettingsReceiver = receiver
        notificationSettingsReceiverRegistered = true
    }

    /**
     * Detect OPPO earphones by checking if the device name contains "oppo" (case insensitive).
     */
    @SuppressLint("MissingPermission")
    fun isOppoPod(device: BluetoothDevice): Boolean {
        val name = device.name ?: return false
        return name.contains("oppo", ignoreCase = true)
    }
}
