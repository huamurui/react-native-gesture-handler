package com.swmansion.gesturehandler.react

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.facebook.react.bridge.JavaOnlyMap
import com.facebook.react.bridge.ReactContext
import com.facebook.react.uimanager.RootView
import com.facebook.soloader.SoLoader
import com.swmansion.gesturehandler.core.GestureHandler
import com.swmansion.gesturehandler.core.NativeViewGestureHandler
import com.swmansion.gesturehandler.core.TapGestureHandler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class RNGestureHandlerRootHelperTest {
  private lateinit var registry: RNGestureHandlerRegistry
  private lateinit var wrapper: DispatchingView
  private lateinit var target: RecordingView
  private lateinit var tap: TapGestureHandler
  private var downTime = 0L
  private var nextTag = 1

  @Before
  fun setUp() {
    SoLoader.setInTestMode()
    // Only the JS module lookup is mocked. The helper, arbitration, gesture handlers, and
    // native ViewGroup dispatch all run normally, without starting a React runtime.
    val reactContext = mock(ReactContext::class.java)
    `when`(reactContext.getNativeModule(RNGestureHandlerModule::class.java))
      .thenReturn(mock(RNGestureHandlerModule::class.java))
    registry = RNGestureHandlerRegistry()
    RNGestureHandlerModule.registries[MODULE_ID] = registry
    val context = RuntimeEnvironment.getApplication()
    val root = TestRootView(context)
    wrapper = DispatchingView(context).apply { id = 100 }
    target = RecordingView(context).apply { id = 101 }
    root.addView(wrapper, FrameLayout.LayoutParams(300, 300))
    wrapper.addView(target, FrameLayout.LayoutParams(300, 300))
    root.layout(0, 0, 300, 300)
    wrapper.layout(0, 0, 300, 300)
    target.layout(0, 0, 300, 300)
    wrapper.helper = RNGestureHandlerRootHelper(reactContext, wrapper, MODULE_ID)
    tap = TapGestureHandler()
    attach(tap, target)
  }

  @After
  fun tearDown() {
    registry.dropAllHandlers()
    RNGestureHandlerModule.registries.remove(MODULE_ID)
  }

  @Test
  fun tapActivatingOnUpCancelsTheNativeTouch() {
    send(MotionEvent.ACTION_DOWN)
    assertTrue(target.isPressed)
    send(MotionEvent.ACTION_UP)

    assertEquals(GestureHandler.STATE_END, tap.state)
    assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), target.actions)
    assertFalse(target.isPressed)
    assertEquals(1, target.cancelCount)
  }

  @Test
  fun finalUpAfterAnotherPointerLiftsCancelsTheNativeTouchOnce() {
    send(MotionEvent.ACTION_DOWN)
    sendTwoPointers(MotionEvent.ACTION_POINTER_DOWN)
    sendTwoPointers(MotionEvent.ACTION_POINTER_UP)
    send(MotionEvent.ACTION_UP)

    assertEquals(GestureHandler.STATE_END, tap.state)
    assertEquals(
      listOf(
        MotionEvent.ACTION_DOWN,
        MotionEvent.ACTION_POINTER_DOWN,
        MotionEvent.ACTION_POINTER_UP,
        MotionEvent.ACTION_CANCEL,
      ),
      target.actions,
    )
    assertEquals(1, target.cancelCount)
    assertFalse(target.isPressed)
  }

  @Test
  fun disabledTapAllowsTheNativeTouchToFinish() {
    TapGestureHandler.Factory().updateConfig(tap, JavaOnlyMap.of("enabled", false))
    send(MotionEvent.ACTION_DOWN)
    send(MotionEvent.ACTION_UP)

    assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), target.actions)
    assertFalse(target.isPressed)
  }

  @Test
  fun holdingPastTapTimeoutAllowsTheNativeTouchToFinish() {
    send(MotionEvent.ACTION_DOWN)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
    send(MotionEvent.ACTION_UP)

    assertEquals(GestureHandler.STATE_FAILED, tap.state)
    assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), target.actions)
  }

  @Test
  fun moveInterceptionDoesNotSendAnotherCancelOnUp() {
    TapGestureHandler.Factory().updateConfig(tap, JavaOnlyMap.of("enabled", false))
    attach(
      object : GestureHandler() {
        override fun onHandle(event: MotionEvent, sourceEvent: MotionEvent) {
          when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin()
            MotionEvent.ACTION_MOVE -> activate()
            MotionEvent.ACTION_UP -> end()
          }
        }
      },
      target,
    )
    send(MotionEvent.ACTION_DOWN)
    send(MotionEvent.ACTION_MOVE)
    send(MotionEvent.ACTION_UP)

    assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), target.actions)
    assertEquals(1, target.cancelCount)
  }

  @Test
  fun nativeCancellationCannotCancelTheWinningHandler() {
    TapGestureHandler.Factory().updateConfig(tap, JavaOnlyMap.of("enabled", false))
    val winner = object : GestureHandler() {
      override fun onHandle(event: MotionEvent, sourceEvent: MotionEvent) {
        when (event.actionMasked) {
          MotionEvent.ACTION_DOWN -> begin()
          MotionEvent.ACTION_MOVE -> activate()
          MotionEvent.ACTION_UP -> end()
        }
      }
    }
    attach(winner, target, GestureHandler.ACTION_TYPE_JS_FUNCTION_OLD_API)
    target.onCancel = { target.parent.requestDisallowInterceptTouchEvent(true) }
    send(MotionEvent.ACTION_DOWN)
    send(MotionEvent.ACTION_MOVE)

    assertEquals(GestureHandler.STATE_ACTIVE, winner.state)
    send(MotionEvent.ACTION_UP)
    assertEquals(GestureHandler.STATE_END, winner.state)
    assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), target.actions)
  }

  @Test
  fun cancelledTouchDoesNotPoisonTheNextStream() {
    send(MotionEvent.ACTION_DOWN)
    send(MotionEvent.ACTION_CANCEL)
    assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), target.actions)
    send(MotionEvent.ACTION_DOWN)
    target.actions.clear()
    send(MotionEvent.ACTION_UP)

    assertEquals(listOf(MotionEvent.ACTION_CANCEL), target.actions)
    assertFalse(target.isPressed)
  }

  @Test
  fun nextStreamCanFinishNormallyAfterAnInterceptedTap() {
    send(MotionEvent.ACTION_DOWN)
    send(MotionEvent.ACTION_UP)
    assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), target.actions)
    TapGestureHandler.Factory().updateConfig(tap, JavaOnlyMap.of("enabled", false))
    send(MotionEvent.ACTION_DOWN)
    target.actions.clear()
    send(MotionEvent.ACTION_UP)

    assertEquals(listOf(MotionEvent.ACTION_UP), target.actions)
    assertFalse(target.isPressed)
  }

  @Test
  fun nativeContainerEndingOnUpIsNotCancelledButItsOrdinaryChildIs() {
    // The Native handler feeds UP directly to the container's onTouchEvent. Its child still
    // needs CANCEL, but sending CANCEL to the container would interrupt native scrolling.
    val nativeContainer = NativeContainer(target.context).apply { id = 102 }
    wrapper.removeView(target)
    wrapper.addView(nativeContainer, FrameLayout.LayoutParams(300, 300))
    nativeContainer.addView(target, FrameLayout.LayoutParams(300, 300))
    nativeContainer.layout(0, 0, 300, 300)
    target.layout(0, 0, 300, 300)
    TapGestureHandler.Factory().updateConfig(tap, JavaOnlyMap.of("enabled", false))
    attach(NativeViewGestureHandler(), nativeContainer)

    send(MotionEvent.ACTION_DOWN)
    send(MotionEvent.ACTION_UP)

    assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), target.actions)
    assertEquals(listOf(MotionEvent.ACTION_UP), nativeContainer.actions)
    assertEquals(0, nativeContainer.cancelCount)
    assertEquals(0, nativeContainer.nestedScrollStops)
  }

  @Test
  fun simultaneousTapDoesNotCancelTheNativeContainerThatReceivedUp() {
    val nativeContainer = NativeContainer(target.context).apply { id = 102 }
    wrapper.removeView(target)
    wrapper.addView(nativeContainer, FrameLayout.LayoutParams(300, 300))
    nativeContainer.addView(target, FrameLayout.LayoutParams(300, 300))
    nativeContainer.layout(0, 0, 300, 300)
    target.layout(0, 0, 300, 300)
    attach(NativeViewGestureHandler(), nativeContainer)
    nativeContainer.simultaneousHandler = tap

    send(MotionEvent.ACTION_DOWN)
    send(MotionEvent.ACTION_UP)

    assertEquals(GestureHandler.STATE_END, tap.state)
    assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), target.actions)
    assertEquals(listOf(MotionEvent.ACTION_UP), nativeContainer.actions)
    assertEquals(0, nativeContainer.nestedScrollStops)
  }

  private fun attach(
    handler: GestureHandler,
    view: View,
    actionType: Int = GestureHandler.ACTION_TYPE_NATIVE_DETECTOR,
  ) {
    handler.tag = nextTag++
    registry.registerHandler(handler)
    registry.attachHandlerToView(handler.tag, view.id, actionType)
  }

  private fun send(action: Int) {
    if (action == MotionEvent.ACTION_DOWN) {
      downTime = SystemClock.uptimeMillis()
    }
    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 50f, 50f, 0)
    try {
      wrapper.dispatchTouchEvent(event)
    } finally {
      event.recycle()
    }
  }

  private fun sendTwoPointers(action: Int) {
    val pointers = Array(2) { index ->
      MotionEvent.PointerProperties().apply {
        id = index
        toolType = MotionEvent.TOOL_TYPE_FINGER
      }
    }
    val coordinates = Array(2) { index ->
      MotionEvent.PointerCoords().apply {
        x = 50f + index * 10f
        y = 50f
        pressure = 1f
        size = 1f
      }
    }
    val event = MotionEvent.obtain(
      downTime,
      SystemClock.uptimeMillis(),
      action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
      2,
      pointers,
      coordinates,
      0,
      0,
      1f,
      1f,
      0,
      0,
      InputDevice.SOURCE_TOUCHSCREEN,
      0,
    )
    try {
      wrapper.dispatchTouchEvent(event)
    } finally {
      event.recycle()
    }
  }

  private class DispatchingView(context: Context) : FrameLayout(context) {
    lateinit var helper: RNGestureHandlerRootHelper

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
      if (disallowIntercept) {
        helper.requestDisallowInterceptTouchEvent()
      }
      super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
      // Match the root view's stale Android child-touch-target cleanup on a new stream.
      if (event.actionMasked == MotionEvent.ACTION_DOWN) {
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
      }
      if (helper.dispatchTouchEvent(event)) {
        return true
      }
      helper.onNativeDispatchStart()
      val handled = super.dispatchTouchEvent(event)
      helper.onNativeDispatchEnd(event)
      return handled
    }
  }

  private class RecordingView(context: Context) : View(context) {
    var onCancel: (() -> Unit)? = null
    val actions = mutableListOf<Int>()
    val cancelCount get() = actions.count { it == MotionEvent.ACTION_CANCEL }

    override fun onTouchEvent(event: MotionEvent): Boolean {
      actions.add(event.actionMasked)
      if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
        onCancel?.invoke()
      }
      isPressed = event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL
      return true
    }
  }

  private class NativeContainer(context: Context) :
    FrameLayout(context),
    NativeViewGestureHandler.NativeViewGestureHandlerHook {
    val actions = mutableListOf<Int>()
    var nestedScrollStops = 0
    var simultaneousHandler: GestureHandler? = null
    val cancelCount get() = actions.count { it == MotionEvent.ACTION_CANCEL }

    override fun shouldRecognizeSimultaneously(handler: GestureHandler): Boolean? =
      if (handler === simultaneousHandler) true else null
    override fun canActivate(view: View) = true
    override fun shouldCancelRootViewGestureHandlerIfNecessary() = true
    override fun onTouchEvent(event: MotionEvent): Boolean {
      actions.add(event.actionMasked)
      return true
    }
    override fun stopNestedScroll() {
      nestedScrollStops++
      super.stopNestedScroll()
    }
  }

  private class TestRootView(context: Context) :
    FrameLayout(context),
    RootView {
    override fun onChildStartedNativeGesture(childView: View?, ev: MotionEvent) = Unit
    override fun onChildEndedNativeGesture(childView: View, ev: MotionEvent) = Unit
    override fun handleException(t: Throwable) = throw t
  }

  companion object {
    private const val MODULE_ID = 1000
  }
}
