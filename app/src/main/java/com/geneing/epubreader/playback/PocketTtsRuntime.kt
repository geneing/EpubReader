package com.geneing.epubreader.playback

import android.content.Context
import dev.pockettts.PocketTtsEngine

/**
 * Process-wide Pocket TTS engine. Loading the graphs costs seconds and ~150 MB
 * of native memory, so narration sessions share one instance instead of paying
 * that cost per book.
 */
internal object PocketTtsRuntime {

    @Volatile
    private var engine: PocketTtsEngine? = null

    fun engine(context: Context): PocketTtsEngine {
        engine?.let { return it }
        return synchronized(this) {
            engine ?: PocketTtsEngine(context.applicationContext).also { engine = it }
        }
    }
}
