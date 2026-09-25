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
}
