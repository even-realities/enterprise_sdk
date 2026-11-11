package com.fzfstudio.ezw_ble.ble.models

import java.io.Serializable
import java.util.UUID

/// 蓝牙私有服务 （Private Service = PS）
data class BlePrivateService(
    //  服务本体
    val service: String,
    //  写特征
    val writeChars: String? = null,
    //  读特征
    val readChars: String? = null,
    //  服务所属类型：0 = 基础服务，1 = OTA，其他由用户自定义
    val type: Int = 0,
): Serializable {

    // This native-only flag is absent from the serialized legacy configuration.
    @Transient internal var standaloneOta: Boolean = false
        private set

    /** Standalone microphone support is optional until an audio command requests it. */
    @Transient internal var standaloneAudio: Boolean = false
        private set
    internal val optionalStandalone: Boolean get() = standaloneOta || standaloneAudio

    companion object {
        internal fun standaloneG2Audio() = BlePrivateService(
            "00002760-08C2-11E1-9073-0E8AC72E6450",
            "00002760-08C2-11E1-9073-0E8AC72E6401",
            "00002760-08C2-11E1-9073-0E8AC72E6402", 2,
        ).apply { standaloneAudio = true }

        /** Only the standalone enterprise 1001/0001/0002 channel is optional for ordinary business. */
        internal fun standaloneG2Ota() = BlePrivateService(
            "00002760-08C2-11E1-9073-0E8AC72E1001",
            "00002760-08C2-11E1-9073-0E8AC72E0001",
            "00002760-08C2-11E1-9073-0E8AC72E0002", 1,
        ).apply { standaloneOta = true }
    }

    val serviceUUID: UUID
        get() = UUID.fromString(service)
    val writeCharsUUID: UUID?
        get() = if (writeChars == null) null else UUID.fromString(writeChars)
    val readCharsUUID: UUID?
        get() = if (readChars == null) null else UUID.fromString(readChars)

}