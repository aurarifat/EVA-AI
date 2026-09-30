package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("EVA", appName)
  }

  @Test
  fun `verify OverlayLifecycleOwner lifecycle state flow`() {
    val owner = com.example.eva.overlay.OverlayLifecycleOwner()
    owner.onCreate()
    assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, owner.lifecycle.currentState)
    org.junit.Assert.assertNotNull(owner.viewModelStore)
    org.junit.Assert.assertNotNull(owner.savedStateRegistry)

    owner.onDestroy()
    assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
  }

  @Test
  fun `launch MainActivity verification`() {
    val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java)
    controller.create().start().resume()
    val activity = controller.get()
    org.junit.Assert.assertNotNull(activity)
  }

  @Test
  fun `verify WakeWordListener matchesWakeWord variants`() {
    val matcher = com.example.eva.voice.WakeWordListener.Companion
    org.junit.Assert.assertTrue(matcher.matchesWakeWord("Hi EVA"))
    org.junit.Assert.assertTrue(matcher.matchesWakeWord("hey eva"))
    org.junit.Assert.assertTrue(matcher.matchesWakeWord("hello eva what's the weather"))
    org.junit.Assert.assertTrue(matcher.matchesWakeWord("hi eeva"))
    org.junit.Assert.assertTrue(matcher.matchesWakeWord("ok eva"))
    org.junit.Assert.assertFalse(matcher.matchesWakeWord("hello world"))
    org.junit.Assert.assertFalse(matcher.matchesWakeWord("something completely different"))
  }

  @Test
  fun `verify LocalWakeWordModel feature extraction on synthetic audio`() {
    val model = com.example.eva.voice.LocalWakeWordModel(sensitivity = 0.6f)
    val syntheticSamples = ShortArray(512) { i ->
      (kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 16000.0) * 16000.0).toInt().toShort()
    }
    val features = model.extractFeatures(syntheticSamples)
    org.junit.Assert.assertTrue(features.rms > 0.05f)
    org.junit.Assert.assertTrue(features.zcr in 0.01f..0.20f)
    org.junit.Assert.assertEquals(8, features.bandEnergies.size)

    val (detected, conf) = model.processFrame(features)
    // A single continuous pure sine tone will not trigger a 4-phoneme wake word
    org.junit.Assert.assertFalse(detected)
    org.junit.Assert.assertEquals(0f, conf, 0.01f)
  }

  @Test
  fun `verify WakeWordDetectionService service lifecycle`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    com.example.eva.voice.WakeWordDetectionService.startService(context)
    com.example.eva.voice.WakeWordDetectionService.stopService(context)
  }
}
