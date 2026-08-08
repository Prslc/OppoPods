package moe.chenxy.oppopods.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams

/** Resolved battery display values for a single pod. */
class BatteryDisplayState(
    val isConnected: Boolean,
    val level: Int,
    val hasLastKnown: Boolean,
    val lastKnownLevel: Int
)

/**
 * Resolves how a pod's battery should be shown. While a pod is disconnected
 * (e.g. case lid closed) the last known level is retained so the UI does not
 * fall back to an empty battery icon; [-1] level means no data has ever arrived.
 */
@Composable
fun rememberBatteryDisplay(pod: PodParams?): BatteryDisplayState {
    val isConnected = pod?.isConnected == true
    val level = pod?.battery?.coerceIn(0, 100) ?: 0
    var lastKnownLevel by remember { mutableIntStateOf(-1) }
    if (level > 0) {
        lastKnownLevel = level
    }
    return BatteryDisplayState(isConnected, level, lastKnownLevel >= 0, lastKnownLevel)
}
