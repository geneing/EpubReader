package com.geneing.epubreader.playback

internal object PlaybackResumePolicy {
    fun afterAudioFocusGain(
        playbackWasInterrupted: Boolean,
        explicitlyPaused: Boolean,
        headsetDisconnected: Boolean,
        interruptionDurationMs: Long,
        resumeAfterLongInterruption: Boolean,
        longInterruptionThresholdMs: Long,
    ): Boolean = playbackWasInterrupted &&
        !explicitlyPaused &&
        !headsetDisconnected &&
        (interruptionDurationMs <= longInterruptionThresholdMs || resumeAfterLongInterruption)

    fun afterBluetoothReconnect(
        disconnectedOutputWasBluetooth: Boolean,
        wasPlayingBeforeDisconnect: Boolean,
        resumeOnBluetoothReconnect: Boolean,
    ): Boolean = disconnectedOutputWasBluetooth &&
        wasPlayingBeforeDisconnect &&
        resumeOnBluetoothReconnect
}
