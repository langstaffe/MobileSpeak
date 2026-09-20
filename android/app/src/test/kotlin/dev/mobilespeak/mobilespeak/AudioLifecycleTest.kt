package dev.mobilespeak.mobilespeak

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
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

    @Test
    fun builtInSpeakerWinsOverEarpiece() {
        assertEquals(
            CommunicationRouteTarget.SPEAKER,
            communicationRouteTarget(
                true,
                setOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
            ),
        )
    }

    @Test
    fun externalCommunicationDevicePreventsForcedSpeaker() {
        assertEquals(
            CommunicationRouteTarget.EXTERNAL,
            communicationRouteTarget(
                true,
                setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADSET),
            ),
        )
    }

    @Test
    fun removingExternalDeviceReturnsToSpeaker() {
        val connected = setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        assertEquals(CommunicationRouteTarget.EXTERNAL, communicationRouteTarget(true, connected))
        assertEquals(
            CommunicationRouteTarget.SPEAKER,
            communicationRouteTarget(true, connected - AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
        )
    }

    @Test
    fun earpieceIsOnlyUsedWhenSpeakerIsUnavailable() {
        assertEquals(
            CommunicationRouteTarget.EARPIECE,
            communicationRouteTarget(true, setOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)),
        )
    }

    @Test
    fun disconnectedSessionReleasesRoute() {
        assertEquals(
            CommunicationRouteTarget.RELEASE,
            communicationRouteTarget(false, setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)),
        )
    }

    @Test
    fun foregroundReturnReappliesRouteWithoutChangingAudioGates() {
        val muted = false
        val deafened = false
        assertTrue(shouldReapplyCommunicationRoute(true, false, true))
        assertTrue(shouldReassertAudioState(connected = true, reapplyRequested = true))
        assertTrue(shouldPlayAudio(true, deafened))
        assertTrue(shouldCaptureAudio(true, !muted && !deafened))
    }

    @Test
    fun backgroundWithoutRouteEventDoesNotReapplyOrStopAudio() {
        assertFalse(shouldReapplyCommunicationRoute(true, false, false))
        assertFalse(shouldReassertAudioState(connected = true, reapplyRequested = false))
        assertFalse(shouldReassertAudioState(connected = false, reapplyRequested = true))
        assertTrue(shouldPlayAudio(true, deafened = false))
        assertTrue(shouldCaptureAudio(true, captureRequested = true))
    }
}
