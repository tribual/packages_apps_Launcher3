/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.taskbar

import android.graphics.Insets
import android.graphics.Region
import android.os.Binder
import android.os.IBinder
import android.view.Gravity
import android.view.InsetsFrameProvider
import android.view.InsetsFrameProvider.SOURCE_DISPLAY
import android.view.InsetsSource.FLAG_INSETS_ROUNDED_CORNER
import android.view.InsetsSource.FLAG_SUPPRESS_SCRIM
import android.view.ViewTreeObserver
import android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME
import android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION
import android.view.WindowInsets
import android.view.WindowInsets.Type.mandatorySystemGestures
import android.view.WindowInsets.Type.navigationBars
import android.view.WindowInsets.Type.systemGestures
import android.view.WindowInsets.Type.tappableElement
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD
import android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION
import androidx.core.graphics.toRegion
import com.android.internal.policy.GestureNavigationSettingsObserver
import com.android.launcher3.DeviceProfile
import com.android.launcher3.R
import com.android.launcher3.anim.AlphaUpdateListener
import com.android.launcher3.taskbar.TaskbarControllers.LoggableTaskbarController
import com.android.launcher3.util.DisplayController
import java.io.PrintWriter

/** Handles the insets that Taskbar provides to underlying apps and the IME. */
class TaskbarInsetsController(val context: TaskbarActivityContext) : LoggableTaskbarController {

    companion object {
        private const val INDEX_LEFT = 0
        private const val INDEX_RIGHT = 1
    }

    /** The bottom insets taskbar provides to the IME when IME is visible. */
    val taskbarHeightForIme: Int = context.resources.getDimensionPixelSize(R.dimen.taskbar_ime_size)
    private val touchableRegion: Region = Region()
    private val insetsOwner: IBinder = Binder()
    private val deviceProfileChangeListener = { _: DeviceProfile ->
        onTaskbarOrBubblebarWindowHeightOrInsetsChanged()
    }
    private val gestureNavSettingsObserver =
        GestureNavigationSettingsObserver(
            context.mainThreadHandler,
            context,
            this::onTaskbarOrBubblebarWindowHeightOrInsetsChanged
        )

    // Initialized in init.
    private lateinit var controllers: TaskbarControllers
    private lateinit var windowLayoutParams: WindowManager.LayoutParams

    fun init(controllers: TaskbarControllers) {
        this.controllers = controllers
        windowLayoutParams = context.windowLayoutParams
        onTaskbarOrBubblebarWindowHeightOrInsetsChanged()

        context.addOnDeviceProfileChangeListener(deviceProfileChangeListener)
        gestureNavSettingsObserver.registerForCallingUser()
    }

    fun onDestroy() {
        context.removeOnDeviceProfileChangeListener(deviceProfileChangeListener)
        gestureNavSettingsObserver.unregister()
    }

    fun onTaskbarOrBubblebarWindowHeightOrInsetsChanged() {
        val tappableHeight = controllers.taskbarStashController.tappableHeightToReportToApps
        // We only report tappableElement height for unstashed, persistent taskbar,
        // which is also when we draw the rounded corners above taskbar.
        val insetsRoundedCornerFlag =
            if (tappableHeight > 0) {
                FLAG_INSETS_ROUNDED_CORNER
            } else {
                0
            }
        if (context.isGestureNav) {
            windowLayoutParams.providedInsets =
                arrayOf(
                    InsetsFrameProvider(insetsOwner, 0, navigationBars())
                        .setFlags(
                            FLAG_SUPPRESS_SCRIM or insetsRoundedCornerFlag,
                            FLAG_SUPPRESS_SCRIM or FLAG_INSETS_ROUNDED_CORNER
                        ),
                    InsetsFrameProvider(insetsOwner, 0, tappableElement()),
                    InsetsFrameProvider(insetsOwner, 0, mandatorySystemGestures()),
                    InsetsFrameProvider(insetsOwner, INDEX_LEFT, systemGestures())
                        .setSource(SOURCE_DISPLAY),
                    InsetsFrameProvider(insetsOwner, INDEX_RIGHT, systemGestures())
                        .setSource(SOURCE_DISPLAY)
                )
        } else {
            windowLayoutParams.providedInsets = getButtonNavInsets(insetsRoundedCornerFlag)
            if (windowLayoutParams.paramsForRotation != null) {
                for (layoutParams in windowLayoutParams.paramsForRotation) {
                    layoutParams.providedInsets = getButtonNavInsets(insetsRoundedCornerFlag)
                }
            }
        }

        val taskbarTouchableHeight = controllers.taskbarStashController.touchableHeight
        val bubblesTouchableHeight =
            if (controllers.bubbleControllers.isPresent)
                controllers.bubbleControllers.get().bubbleStashController.touchableHeight
            else 0
        val touchableHeight = Math.max(taskbarTouchableHeight, bubblesTouchableHeight)

        if (
            controllers.bubbleControllers.isPresent &&
                controllers.bubbleControllers.get().bubbleStashController.isBubblesShowingOnHome
        ) {
            val iconBounds =
                controllers.bubbleControllers.get().bubbleBarViewController.bubbleBarBounds
            touchableRegion.set(
                iconBounds.left,
                iconBounds.top,
                iconBounds.right,
                iconBounds.bottom
            )
        } else {
            touchableRegion.set(
                0,
                windowLayoutParams.height - touchableHeight,
                context.deviceProfile.widthPx,
                windowLayoutParams.height
            )
        }

        val gravity = windowLayoutParams.gravity
        for (provider in windowLayoutParams.providedInsets) {
            setProviderInsets(provider, gravity)
        }

        if (windowLayoutParams.paramsForRotation != null) {
            // Add insets for navbar rotated params
            for (layoutParams in windowLayoutParams.paramsForRotation) {
                for (provider in layoutParams.providedInsets) {
                    setProviderInsets(provider, layoutParams.gravity)
                }
            }
        }

        context.notifyUpdateLayoutParams()
    }

    private fun getButtonNavInsets(insetsRoundedCornerFlag: Int): Array<InsetsFrameProvider> {
        return arrayOf(
                    InsetsFrameProvider(insetsOwner, 0, navigationBars())
                        .setFlags(
                            insetsRoundedCornerFlag,
                            (FLAG_SUPPRESS_SCRIM or FLAG_INSETS_ROUNDED_CORNER)
                        ),
                    InsetsFrameProvider(insetsOwner, 0, tappableElement()),
                    InsetsFrameProvider(insetsOwner, 0, mandatorySystemGestures()))
    }

    private fun setProviderInsets(provider: InsetsFrameProvider, gravity: Int) {
        val contentHeight = controllers.taskbarStashController.contentHeightToReportToApps
        val tappableHeight = controllers.taskbarStashController.tappableHeightToReportToApps
        val res = context.resources
        if (provider.type == navigationBars() || provider.type == mandatorySystemGestures()) {
            provider.insetsSize = getInsetsByNavMode(contentHeight, gravity)
        } else if (provider.type == tappableElement()) {
            provider.insetsSize = getInsetsByNavMode(tappableHeight, gravity)
        } else if (provider.type == systemGestures() && provider.index == INDEX_LEFT) {
            provider.insetsSize =
                    Insets.of(
                            gestureNavSettingsObserver.getLeftSensitivityForCallingUser(res),
                            0,
                            0,
                            0
                    )
        } else if (provider.type == systemGestures() && provider.index == INDEX_RIGHT) {
            provider.insetsSize =
                    Insets.of(
                            0,
                            0,
                            gestureNavSettingsObserver.getRightSensitivityForCallingUser(res),
                            0
                    )
        }

        val imeInsetsSize = getInsetsByNavMode(taskbarHeightForIme, gravity)
        val insetsSizeOverride =
                arrayOf(
                        InsetsFrameProvider.InsetsSizeOverride(TYPE_INPUT_METHOD, imeInsetsSize),
                )
        // Use 0 tappableElement insets for the VoiceInteractionWindow when gesture nav is enabled.
        val visInsetsSizeForGestureNavTappableElement = getInsetsByNavMode(0, gravity)
        val insetsSizeOverrideForGestureNavTappableElement =
                arrayOf(
                        InsetsFrameProvider.InsetsSizeOverride(TYPE_INPUT_METHOD, imeInsetsSize),
                        InsetsFrameProvider.InsetsSizeOverride(
                                TYPE_VOICE_INTERACTION,
                                visInsetsSizeForGestureNavTappableElement
                        ),
                )
        if (context.isGestureNav && provider.type == tappableElement()) {
            provider.insetsSizeOverrides = insetsSizeOverrideForGestureNavTappableElement
        } else if (provider.type != systemGestures()) {
            // We only override insets at the bottom of the screen
            provider.insetsSizeOverrides = insetsSizeOverride
        }
    }

    /**
     * @return [Insets] where the [inset] is either used as a bottom inset or
     * right/left inset if using 3 button nav
     */
    private fun getInsetsByNavMode(inset: Int, gravity: Int): Insets {
        if ((gravity and Gravity.BOTTOM) != 0) {
            // Taskbar or portrait phone mode
            return Insets.of(0, 0, 0, inset)
        }

        // TODO(b/230394142): seascape
        val isSeascape = (gravity and Gravity.START) != 0
        val leftInset = if (isSeascape) inset else 0
        val rightInset = if (isSeascape) 0 else inset
        return Insets.of(leftInset , 0, rightInset, 0)
    }

    /**
     * Called to update the touchable insets.
     *
     * @see ViewTreeObserver.InternalInsetsInfo.setTouchableInsets
     */
    fun updateInsetsTouchability(insetsInfo: ViewTreeObserver.InternalInsetsInfo) {
        insetsInfo.touchableRegion.setEmpty()
        // Always have nav buttons be touchable
        controllers.navbarButtonsViewController.addVisibleButtonsRegion(
            context.dragLayer,
            insetsInfo.touchableRegion
        )
        val bubbleBarVisible =
            controllers.bubbleControllers.isPresent &&
                controllers.bubbleControllers.get().bubbleBarViewController.isBubbleBarVisible()
        var insetsIsTouchableRegion = true
        if (context.dragLayer.alpha < AlphaUpdateListener.ALPHA_CUTOFF_THRESHOLD) {
            // Let touches pass through us.
            insetsInfo.setTouchableInsets(TOUCHABLE_INSETS_REGION)
        } else if (
            controllers.navbarButtonsViewController.isImeVisible &&
                controllers.taskbarStashController.isStashed
        ) {
            insetsInfo.setTouchableInsets(TOUCHABLE_INSETS_REGION)
        } else if (!controllers.uiController.isTaskbarTouchable) {
            // Let touches pass through us.
            insetsInfo.setTouchableInsets(TOUCHABLE_INSETS_REGION)
        } else if (controllers.taskbarDragController.isSystemDragInProgress) {
            // Let touches pass through us.
            insetsInfo.setTouchableInsets(TOUCHABLE_INSETS_REGION)
        } else if (context.isTaskbarWindowFullscreen) {
            // Intercept entire fullscreen window.
            insetsInfo.setTouchableInsets(TOUCHABLE_INSETS_FRAME)
            insetsIsTouchableRegion = false
        } else if (
            controllers.taskbarViewController.areIconsVisible() ||
                context.isNavBarKidsModeActive ||
                bubbleBarVisible
        ) {
            // Taskbar has some touchable elements, take over the full taskbar area
            if (
                controllers.uiController.isInOverview &&
                    DisplayController.isTransientTaskbar(context)
            ) {
                insetsInfo.touchableRegion.set(
                    controllers.taskbarActivityContext.dragLayer.lastDrawnTransientRect.toRegion()
                )
            } else {
                insetsInfo.touchableRegion.set(touchableRegion)
            }
            insetsInfo.setTouchableInsets(TOUCHABLE_INSETS_REGION)
            insetsIsTouchableRegion = false
        } else {
            insetsInfo.setTouchableInsets(TOUCHABLE_INSETS_REGION)
        }
        context.excludeFromMagnificationRegion(insetsIsTouchableRegion)
    }

    override fun dumpLogs(prefix: String, pw: PrintWriter) {
        pw.println(prefix + "TaskbarInsetsController:")
        pw.println("$prefix\twindowHeight=${windowLayoutParams.height}")
        for (provider in windowLayoutParams.providedInsets) {
            pw.print(
                "$prefix\tprovidedInsets: (type=" +
                    WindowInsets.Type.toString(provider.type) +
                    " insetsSize=" +
                    provider.insetsSize
            )
            if (provider.insetsSizeOverrides != null) {
                pw.print(" insetsSizeOverrides={")
                for ((i, overrideSize) in provider.insetsSizeOverrides.withIndex()) {
                    if (i > 0) pw.print(", ")
                    pw.print(overrideSize)
                }
                pw.print("})")
            }
            pw.println()
        }
    }
}
