package com.geneing.epubreader.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResumePolicyTest {
    @Test
    fun shortAudioFocusInterruptionResumesByDefault() {
        assertTrue(
            PlaybackResumePolicy.afterAudioFocusGain(
                playbackWasInterrupted = true,
                explicitlyPaused = false,
                headsetDisconnected = false,
                interruptionDurationMs = 29_999,
                resumeAfterLongInterruption = false,
                longInterruptionThresholdMs = 30_000,
            ),
        )
    }

    @Test
    fun longAudioFocusInterruptionRequiresPreference() {
        assertFalse(
            PlaybackResumePolicy.afterAudioFocusGain(
                playbackWasInterrupted = true,
                explicitlyPaused = false,
                headsetDisconnected = false,
                interruptionDurationMs = 30_001,
                resumeAfterLongInterruption = false,
                longInterruptionThresholdMs = 30_000,
            ),
        )
        assertTrue(
            PlaybackResumePolicy.afterAudioFocusGain(
                playbackWasInterrupted = true,
                explicitlyPaused = false,
                headsetDisconnected = false,
                interruptionDurationMs = 30_001,
                resumeAfterLongInterruption = true,
                longInterruptionThresholdMs = 30_000,
            ),
        )
    }

    @Test
    fun explicitPauseAndHeadsetDisconnectPreventFocusResume() {
        assertFalse(
            PlaybackResumePolicy.afterAudioFocusGain(
                playbackWasInterrupted = true,
                explicitlyPaused = true,
                headsetDisconnected = false,
                interruptionDurationMs = 1_000,
                resumeAfterLongInterruption = true,
                longInterruptionThresholdMs = 30_000,
            ),
        )
        assertFalse(
            PlaybackResumePolicy.afterAudioFocusGain(
                playbackWasInterrupted = true,
                explicitlyPaused = false,
                headsetDisconnected = true,
                interruptionDurationMs = 1_000,
                resumeAfterLongInterruption = true,
                longInterruptionThresholdMs = 30_000,
            ),
        )
    }

    @Test
    fun bluetoothReconnectResumesOnlyWhenEnabledAndPreviouslyPlaying() {
        assertTrue(PlaybackResumePolicy.afterBluetoothReconnect(true, true, true))
        assertFalse(PlaybackResumePolicy.afterBluetoothReconnect(true, true, false))
        assertFalse(PlaybackResumePolicy.afterBluetoothReconnect(true, false, true))
        assertFalse(PlaybackResumePolicy.afterBluetoothReconnect(false, true, true))
    }
}
