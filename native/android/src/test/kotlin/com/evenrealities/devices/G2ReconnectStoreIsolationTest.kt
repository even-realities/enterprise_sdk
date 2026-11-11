package com.evenrealities.devices

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.fzfstudio.ezw_ble.ble.BleReconnectStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class G2ReconnectStoreIsolationTest {
    @Test fun coldStartAndResetCannotReadOrClearTheFlutterTransportsPreferences() {
        // Stop at the system boundary to capture which preference file each operation opens.
        class PreferenceAccess(val file: String) : RuntimeException()
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                assertEquals(Context.MODE_PRIVATE, mode)
                throw PreferenceAccess(name)
            }
        }
        val store = BleReconnectStore()
        val operations = listOf<() -> Unit>(
            { store.restoreConfigs(context) },
            { store.persistConfigs(context, emptyList()) },
            { store.clearTargets(context) },
            { store.drainEvents(context) },
        )
        for (operation in operations) {
            val access = assertThrows(PreferenceAccess::class.java) { operation() }
            assertEquals("even_devices_standalone_g2_auto_reconnect", access.file)
        }
    }
}
