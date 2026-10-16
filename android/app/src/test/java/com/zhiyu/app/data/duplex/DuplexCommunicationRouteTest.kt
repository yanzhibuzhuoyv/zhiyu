package com.zhiyu.app.data.duplex

import android.media.AudioDeviceInfo
import org.junit.Assert.*
import org.junit.Test

class DuplexCommunicationRouteTest {
    @Test fun videoCallsUseTheLoudspeakerWhenNoHeadsetIsConnected() {
        assertTrue(DuplexCommunicationSession.priority(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) <
            DuplexCommunicationSession.priority(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
    }
    @Test fun wiredAndBluetoothHeadsetsStillHavePriorityOverTheLoudspeaker() {
        listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO).forEach {
            assertTrue(DuplexCommunicationSession.priority(it) < DuplexCommunicationSession.priority(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        }
    }
}
