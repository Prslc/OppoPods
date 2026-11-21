package moe.chenxy.oppopods.utils

import moe.chenxy.oppopods.R

/** Shared battery level -> drawable mapping for the popup and the custom notification. */
object BatteryIconRes {
    fun forLevel(level: Int, isCharging: Boolean): Int {
        val index = when {
            level <= 10 -> 1
            level <= 20 -> 2
            level <= 30 -> 3
            level <= 40 -> 4
            level <= 50 -> 5
            level <= 60 -> 6
            level <= 70 -> 7
            level <= 80 -> 8
            level <= 90 -> 9
            else -> 10
        }
        return if (isCharging) {
            when (index) {
                1 -> R.drawable.charge_1
                2 -> R.drawable.charge_2
                3 -> R.drawable.charge_3
                4 -> R.drawable.charge_4
                5 -> R.drawable.charge_5
                6 -> R.drawable.charge_6
                7 -> R.drawable.charge_7
                8 -> R.drawable.charge_8
                9 -> R.drawable.charge_9
                else -> R.drawable.charge_10
            }
        } else {
            when (index) {
                1 -> R.drawable.common_1
                2 -> R.drawable.common_2
                3 -> R.drawable.common_3
                4 -> R.drawable.common_4
                5 -> R.drawable.common_5
                6 -> R.drawable.common_6
                7 -> R.drawable.common_7
                8 -> R.drawable.common_8
                9 -> R.drawable.common_9
                else -> R.drawable.common_10
            }
        }
    }
}
