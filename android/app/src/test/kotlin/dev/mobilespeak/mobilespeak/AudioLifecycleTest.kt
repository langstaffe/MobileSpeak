package dev.mobilespeak.mobilespeak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLifecycleTest {
    @Test
    fun backgroundDoesNotDisablePreparedMicrophone() {
        assertTrue(wantsForegroundMicrophone(true, true, false, false))
        assertTrue(allowsMicrophoneCapture(true, true, false, false, true))
        assertFalse(allowsMicrophoneCapture(true, true, true, false, true))
        assertFalse(allowsMicrophoneCapture(true, true, false, true, true))
        assertFalse(allowsMicrophoneCapture(false, true, false, false, true))
    }

    @Test
    fun connectedAudioDependsOnlyOnSessionAndUserSettings() {
        assertTrue(shouldPlayAudio(connected = true, deafened = false))
        assertTrue(shouldCaptureAudio(connected = true, captureRequested = true))
        assertFalse(shouldCaptureAudio(connected = true, captureRequested = false))
        assertFalse(shouldPlayAudio(connected = true, deafened = true))
        assertFalse(shouldPlayAudio(connected = false, deafened = false))
        assertFalse(shouldCaptureAudio(connected = false, captureRequested = true))
    }

    @Test
    fun microphonePolicyPreservesMuteDeafenPermissionAndForegroundServiceRules() {
        assertFalse(allowsMicrophoneCapture(true, true, true, false, true))
        assertFalse(allowsMicrophoneCapture(true, true, false, true, true))
        assertFalse(allowsMicrophoneCapture(true, false, false, false, true))
        assertFalse(allowsMicrophoneCapture(true, true, false, false, false))
        assertTrue(allowsMicrophoneCapture(true, true, false, false, true))
    }
}
