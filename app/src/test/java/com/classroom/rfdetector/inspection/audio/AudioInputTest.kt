package com.classroom.rfdetector.inspection.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.*
import org.junit.Test

class AudioInputTest {
    @Test fun anyVerifiedUnsilencedBuiltInRouteIsUsable() {
        val builtin = AudioDeviceInfo.TYPE_BUILTIN_MIC
        // The routing decision only uses the actual device type, not the preferred device ID.
        assertNull(inputFailure(builtin, false))
        assertNotNull(inputFailure(AudioDeviceInfo.TYPE_USB_DEVICE, false))
        assertNotNull(inputFailure(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, false))
        assertNotNull(inputFailure(builtin, true))
        assertNotNull(inputFailure(builtin, null))
    }
}
