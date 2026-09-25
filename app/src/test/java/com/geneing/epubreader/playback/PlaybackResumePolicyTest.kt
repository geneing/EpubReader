package com.geneing.epubreader.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResumePolicyTest {
    @Test
    fun longInterruptionResumeRequiresPreference() {
        assertFalse(PlaybackResumePolicy.shouldResumeAfterLongInterruption(false, false, false))
        assertTrue(PlaybackResumePolicy.shouldResumeAfterLongInterruption(false, false, true))
    }

    @Test
    fun explicitPauseAndHeadsetDisconnectPreventFocusResume() {
        assertFalse(
            PlaybackResumePolicy.shouldResumeAfterLongInterruption(true, false, true),
        )
        assertFalse(
            PlaybackResumePolicy.shouldResumeAfterLongInterruption(false, true, true),
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
