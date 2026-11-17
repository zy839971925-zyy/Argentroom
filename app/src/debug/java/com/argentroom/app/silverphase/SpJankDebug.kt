/*
 * Silverphase Android port — debug-only JankStats logging. GPLv3.
 * Logs every janky frame (logcat tag "SpJank") with its duration and the UI state the editor
 * reports, so morph/ruler/list jank can be measured on a device. Loaded by reflection from
 * MainActivity; this class and androidx.metrics exist only in debug builds.
 */
package com.argentroom.app.silverphase

import com.argentroom.app.*
import android.app.Activity
import android.util.Log
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState

object SpJankDebug {
    @JvmStatic
    fun install(activity: Activity) {
        val window = activity.window
        val holder = PerformanceMetricsState.getHolderForHierarchy(window.decorView)
        holder.state?.putState("screen", activity.javaClass.simpleName)
        JankStats.createAndTrack(window) { frame ->
            if (frame.isJank) {
                Log.d("SpJank", "jank ${frame.frameDurationUiNanos / 1_000_000.0} ms ${frame.states.joinToString { it.key + "=" + it.value }}")
            }
        }
    }
}
