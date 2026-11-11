package com.fzfstudio.ezw_ble.ble

import com.fzfstudio.ezw_ble.ble.models.BleConfig
import com.fzfstudio.ezw_ble.ble.models.BlePrivateService
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class BleStandaloneOtaReadinessTest {
    // Only the factory used by the standalone enterprise G2 registry marks OTA as optional.
    private val ota = BlePrivateService.standaloneG2Ota()
    private val required = listOf(0, 2, 3).map { BlePrivateService("required", type = it) }

    @Test fun ordinaryReadyNeedsEveryRequiredChannelEvenIfOtaIsPresent() {
        val config = BleConfig.empty().copy(privateServices = required + ota)
        val businessOnly = setOf(0, 2, 3)
        assertTrue(config.hasRequiredBusinessChannels { it in businessOnly })
        for (missing in businessOnly) {
            assertFalse(config.hasRequiredBusinessChannels { it != missing })
        }
        assertFalse(BleConfig.empty().copy(privateServices = listOf(ota)).hasRequiredBusinessChannels { true })
    }

    @Test fun standaloneAudioIsOptionalWithoutWeakeningLegacyStreamReadiness() {
        val audio = BlePrivateService.standaloneG2Audio()
        val config = BleConfig.empty().copy(privateServices = required.filter { it.type != 2 } + audio + ota)
        assertTrue(config.hasRequiredBusinessChannels { it in setOf(0, 3) })
        assertFalse(config.hasRequiredBusinessChannels { it == 0 })
        val json = Gson().toJsonTree(audio).asJsonObject
        assertFalse(json.has("standaloneAudio"))
        json.addProperty("standaloneAudio", true)
        val legacy = Gson().fromJson(json, BlePrivateService::class.java)
        assertFalse(config.copy(privateServices = required.filter { it.type != 2 } + legacy)
            .hasRequiredBusinessChannels { it != 2 })
    }

    @Test fun legacyConfigCannotOptOutOfItsOtaCccdThroughSerializedInput() {
        // Legacy and restored Gson configurations use required services even with identical UUIDs.
        val json = Gson().toJsonTree(ota).asJsonObject
        assertFalse(json.has("standaloneOta"))
        json.addProperty("standaloneOta", true)
        val legacy = Gson().fromJson(json, BlePrivateService::class.java)
        val config = BleConfig.empty().copy(privateServices = required + legacy)
        assertFalse(config.hasRequiredBusinessChannels { it != 1 })
        assertTrue(config.hasRequiredBusinessChannels { true })
    }
}
