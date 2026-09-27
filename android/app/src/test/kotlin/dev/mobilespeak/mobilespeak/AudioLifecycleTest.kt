package dev.mobilespeak.mobilespeak

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLifecycleTest {
    @Test
    fun audioIntentWaitsForOwnMemberAndResetsOnReconnect() {
        val own = Member(7, 1, null, "", null, emptyList(), emptyList(), null,
            muted = true, deafened = false, speaking = false)
        val initial = Snapshot(status = "connected", ownClient = 7)
        assertFalse(initial.canApplyAudioState())
        assertFalse(initial.copy(clients = listOf(own.copy(id = 8))).canApplyAudioState())
        val ready = initial.copy(clients = listOf(own))
        // canSend is still false because we have not applied the user's unmute yet.
        assertFalse(ready.canSend)
        assertTrue(ready.canApplyAudioState())
        assertTrue(ready.copy(clients = listOf(own.copy(channel = 2))).canApplyAudioState())
        assertFalse(ready.copy(status = "reconnecting").canApplyAudioState())
        assertFalse(initial.canApplyAudioState())
        assertTrue(ready.canApplyAudioState())
    }

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
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            preferredCommunicationDevice(
                true,
                setOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
            ),
        )
    }

    @Test
    fun externalCommunicationDevicePreventsForcedSpeaker() {
        assertEquals(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            preferredCommunicationDevice(
                true,
                setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADSET),
            ),
        )
    }

    @Test
    fun removingExternalDeviceReturnsToSpeaker() {
        val connected = setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, preferredCommunicationDevice(true, connected))
        assertEquals(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            preferredCommunicationDevice(true, connected - AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
        )
    }

    @Test
    fun earpieceIsNotAnAutomaticPhoneFallback() {
        assertEquals(
            null,
            preferredCommunicationDevice(true, setOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)),
        )
    }

    @Test
    fun disconnectedSessionReleasesRoute() {
        assertEquals(
            null,
            preferredCommunicationDevice(false, setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)),
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

    @Test fun mediaOnlyBluetoothDoesNotSuppressPhoneSpeaker() {
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, preferredCommunicationDevice(true,
            setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
        assertEquals(Int.MAX_VALUE, communicationDevicePriority(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertTrue(communicationDevicePriority(AudioDeviceInfo.TYPE_BLUETOOTH_SCO) < communicationDevicePriority(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertTrue(communicationDevicePriority(AudioDeviceInfo.TYPE_BLE_HEADSET) < communicationDevicePriority(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    }

    @Test fun requestsWaitForActualDeviceAndDeduplicatePendingAndConfirmedTargets() {
        val request = CommunicationRouteRequest()
        val token = request.request(10)!!
        assertTrue(request.isPending(token))
        assertFalse(request.confirm(token, 20))
        assertEquals(null, request.request(10))
        assertTrue(request.confirm(token, 10))
        assertFalse(request.isPending(token))
        assertEquals(null, request.request(10))
        assertFalse(request.confirm(token, 10))
    }

    @Test fun failedRequestFallsBackWithoutRepeatedRetriesUntilDeviceReconnectOrNewSession() {
        val request = CommunicationRouteRequest()
        val token = request.request(10)!!
        assertTrue(request.fail(token))
        assertTrue(10 in request.failedDevices)
        assertEquals(null, request.request(10))
        assertFalse(request.confirm(token, 10))
        assertFalse(request.fail(token))
        val fallback = request.request(20)!!
        assertTrue(request.confirm(fallback, 20))
        request.retainDevices(setOf(20))
        assertTrue(request.failedDevices.isEmpty())
        assertTrue(request.request(10) != null)
        request.fail(request.token)
        request.reset()
        assertEquals(null, request.targetId)
        assertTrue(request.failedDevices.isEmpty())
        assertTrue(request.request(10) != null)
    }

    @Test fun releaseAndRapidTargetChangesInvalidateLateCallbacksAndTimeouts() {
        val request = CommunicationRouteRequest()
        val old = request.request(10)!!
        request.release()
        val next = request.request(20)!!
        assertFalse(request.confirm(old, 10))
        assertFalse(request.fail(old))
        assertFalse(request.isPending(old))
        assertTrue(request.isPending(next))
        request.reset()
        assertFalse(request.confirm(next, 20))
        assertFalse(request.isPending(next))
        assertEquals(null, request.targetId)
    }

    @Test fun realHeadsetReconnectAllowsOneNewRequestEvenWhenAudioPortIdIsRetained() {
        val request = CommunicationRouteRequest()
        val old = request.request(10)!!
        assertTrue(request.confirm(old, 10))
        assertTrue(request.fail(old))
        request.retainDevices(setOf(10, 20)) // Quick HFP reconnect did not remove the cached audio port.
        assertEquals(null, request.request(10))
        request.devicesReconnected(setOf(10))
        val next = request.request(10)!!
        assertEquals(null, request.request(10))
        assertFalse(request.confirm(old, 10))
        assertFalse(request.fail(old))
        assertTrue(request.confirm(next, 10))
        request.devicesReconnected(setOf(10))
        assertEquals(null, request.request(10)) // A duplicate connection event cannot restart an active request.
    }

    @Test fun onlyAnEstablishedAvailableRouteStartsABoundedRecoveryWindow() {
        val request = CommunicationRouteRequest()
        val old = request.request(10)!!
        assertEquals(null, request.recoverAfterLoss(old, true, false, 0))
        request.confirm(old, 10)
        assertEquals(null, request.recoverAfterLoss(old, false, false, 0))
        assertEquals(null, request.recoverAfterLoss(old, true, true, 0))
        repeat(3) { attempt ->
            val token = request.token
            assertEquals(10, request.recoverAfterLoss(token, true, false, attempt.toLong()))
            request.fail(token)
            request.failedDevices.remove(10)
            request.request(10)
            if (attempt == 1) request.confirm(request.token, 10, attempt.toLong())
        }
        assertEquals(null, request.recoverAfterLoss(request.token, true, false, 4))
        assertEquals(null, request.recoverAfterLoss(old, true, false, 4))
        request.fail(request.token)
        assertEquals(null, request.request(10))
    }

    @Test fun aStableConfirmedRecoveryDoesNotDisableTheNextHotSwitch() {
        val request = CommunicationRouteRequest()
        request.request(10)
        request.confirm(request.token, 10)
        var now = 0L
        repeat(3) { switch ->
            now += 2_000
            repeat(3) { attempt ->
                assertEquals(10, request.recoverAfterLoss(request.token, true, false, now + attempt))
                request.fail(request.token)
                request.failedDevices.remove(10)
                request.request(10)
                // A fleeting actual confirmation must not create an unbounded SCO reconnect loop.
                if (attempt == 1) request.confirm(request.token, 10, now + attempt)
            }
            assertEquals(null, request.recoverAfterLoss(request.token, true, false, now + 3))
            // Actual stream confirmation, rather than SCO CONNECTED alone, ends the failed recovery episode.
            now += 4
            assertTrue(request.confirm(request.token, 10, now))
        }
    }

    @Test fun expiredRecoveryCannotRestartPendingRequestsAndNewSessionsDiscardIt() {
        val request = CommunicationRouteRequest()
        val old = request.request(10)!!
        request.confirm(old, 10)
        assertEquals(10, request.recoverAfterLoss(old, true, false, 100))
        request.release()
        request.request(10)
        assertEquals(null, request.recoverAfterLoss(request.token, true, false, 30_100))
        request.reset()
        request.request(10)
        assertEquals(null, request.recoverAfterLoss(request.token, true, false, 30_101))
    }

    @Test fun scoDownmixPreservesTimeAndDoesNotIncreaseLevel() {
        val mono = FloatArray(3)
        downmixStereo(floatArrayOf(1f, 1f, -1f, 1f, .2f, .6f), mono)
        org.junit.Assert.assertArrayEquals(floatArrayOf(1f, 0f, .4f), mono, .0001f)
    }

    @Test fun unavailableScoObservationBetweenConnectingAndConnectedDoesNotAbortBoundedRequest() {
        val request = CommunicationRouteRequest()
        val token = request.request(10)!!
        assertFalse(scoObservationFailed(android.media.AudioManager.SCO_AUDIO_STATE_ERROR, request.confirmed, false))
        assertTrue(request.isPending(token)) // The existing timeout remains armed; no second startBluetoothSco request.
        assertTrue(request.confirm(token, 10))
        assertFalse(scoObservationFailed(android.media.AudioManager.SCO_AUDIO_STATE_ERROR, request.confirmed, true))
        assertTrue(scoObservationFailed(android.media.AudioManager.SCO_AUDIO_STATE_ERROR, request.confirmed, false))
    }
}
