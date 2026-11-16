package com.example.wdghud

/**
 * G5A44: pure decision logic for Android's BluetoothDevice bond states.
 * These are the Android SDK's stable BOND_NONE / BOND_BONDING / BOND_BONDED
 * values. Initial bonding is UI-owned; background service cannot launch it.
 */
internal object BleBondPolicy {
    const val NONE = 10
    const val BONDING = 11
    const val BONDED = 12

    enum class ForegroundAction { CONNECT, PAIR, WAIT, BLOCK }

    fun foreground(state: Int, pairingRejected: Boolean): ForegroundAction = when (state) {
        BONDED -> ForegroundAction.CONNECT
        BONDING -> ForegroundAction.WAIT
        NONE -> if (pairingRejected) ForegroundAction.BLOCK else ForegroundAction.PAIR
        else -> ForegroundAction.BLOCK
    }

    fun backgroundMayConnect(state: Int): Boolean = state == BONDED
}
