// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.theme

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * The system "Remove animations" / animator-duration-scale preference. Optional motion (the reader-entry
 * transition) must fall back to its documented minimal/no-motion behavior when this is true; ShelfOS never
 * infers this from a device model.
 */
fun Context.reducedMotionEnabled(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) !ValueAnimator.areAnimatorsEnabled()
    else Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
