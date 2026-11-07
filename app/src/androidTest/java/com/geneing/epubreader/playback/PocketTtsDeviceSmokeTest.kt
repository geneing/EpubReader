package com.geneing.epubreader.playback

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pockettts.PocketTtsConfig
import dev.pockettts.PocketTtsEngine
import dev.pockettts.PocketTtsModels
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device smoke test for the provisioned Pocket TTS runtime. It needs the model
 * pack in the app's external files directory (`scripts/provision-pocket-models.ps1`)
 * and does not touch the UI, so it can run without unlocking the screen.
 */
@RunWith(AndroidJUnit4::class)
class PocketTtsDeviceSmokeTest {

    @Test
    fun synthesizesSpeechWithProvisionedModels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = PocketTtsModels.default(context)
        val config = PocketTtsConfig.default(context, models)
        val missing = PocketTtsEngine.requiredFiles(config).filterNot(models.store::exists)
        assumeTrue("Pocket models are not provisioned on this device: $missing", missing.isEmpty())
        PocketTtsEngine(context, config).use { engine ->
            val result = engine.synthesize("EpubReader is ready to read this book aloud.")
            Log.i(
                "PocketTtsSmoke",
                "placement=${config.placement.label} samples=${result.audio.size} " +
                    "frames=${result.frames} ms=${result.ms} firstChunk=${result.profile.firstChunkMs}",
            )
            assertTrue("Pocket TTS produced no audio", result.audio.isNotEmpty())
        }
    }
}
