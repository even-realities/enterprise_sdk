package com.fzfstudio.ezw_ble.ble

import java.util.concurrent.ConcurrentHashMap

/** Native replacement for Flutter EventChannel; transport callbacks keep their original event order. */
fun interface BleNativeEventSink { fun success(value: Any?) }

/** The copied BLE engine sends through these stable categories without a Flutter embedding. */
enum class BleEC {
    BLE_STATE, SCAN_RESULT, SCAN_STATE, CONNECT_STATUS, RECEIVE_DATA,
    ENTERPRISE_AUTHORIZATION, LOGGER;

    val event: BleNativeEventSink?
        get() = listeners[this]

    fun setListener(listener: BleNativeEventSink?) {
        if (listener == null) listeners.remove(this) else listeners[this] = listener
    }

    companion object {
        private val listeners = ConcurrentHashMap<BleEC, BleNativeEventSink>()

        /** Release app callbacks before a host tears down or replaces an SDK instance. */
        fun clearListeners() { listeners.clear() }
    }
}
