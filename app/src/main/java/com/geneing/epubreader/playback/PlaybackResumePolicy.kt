package com.geneing.epubreader.playback

internal object PlaybackResumePolicy {
    fun shouldResumeAfterLongInterruption(
        explicitlyPaused: Boolean,
        headsetDisconnected: Boolean,
        resumeAfterLongInterruption: Boolean,
    ): Boolean = resumeAfterLongInterruption &&
        !explicitlyPaused &&
        !headsetDisconnected

    fun afterBluetoothReconnect(
        disconnectedOutputWasBluetooth: Boolean,
        wasPlayingBeforeDisconnect: Boolean,
        resumeOnBluetoothReconnect: Boolean,
    ): Boolean = disconnectedOutputWasBluetooth &&
        wasPlayingBeforeDisconnect &&
        resumeOnBluetoothReconnect
}
