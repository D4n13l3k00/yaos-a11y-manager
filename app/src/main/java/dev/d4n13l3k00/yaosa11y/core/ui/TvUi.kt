package dev.d4n13l3k00.yaosa11y.core.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.TextView

object TvColors {
    val background = Color.rgb(16, 19, 23)
    val surface = Color.rgb(28, 34, 43)
    val focus = Color.rgb(44, 103, 148)
    val pressed = Color.rgb(38, 91, 130)
    val primaryText = Color.WHITE
    val secondaryText = Color.rgb(166, 179, 191)
    val success = Color.rgb(129, 216, 161)
    val warning = Color.rgb(255, 183, 77)
    val error = Color.rgb(255, 128, 128)
}

fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density + 0.5f).toInt()

fun Context.roundedDrawable(color: Int, radiusDp: Int): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

fun Context.tvFocusBackground(radiusDp: Int = 11): StateListDrawable =
    StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_focused),
            roundedDrawable(TvColors.focus, radiusDp),
        )
        addState(
            intArrayOf(android.R.attr.state_pressed),
            roundedDrawable(TvColors.pressed, radiusDp),
        )
        addState(intArrayOf(), roundedDrawable(TvColors.surface, radiusDp))
    }

fun TextView.applyTvActionStyle(
    iconRes: Int = 0,
    horizontalPaddingDp: Int = 18,
    verticalPaddingDp: Int = 12,
    radiusDp: Int = 11,
) {
    textSize = 16f
    gravity = Gravity.CENTER
    setTextColor(TvColors.primaryText)
    setPadding(
        context.dp(horizontalPaddingDp),
        context.dp(verticalPaddingDp),
        context.dp(horizontalPaddingDp),
        context.dp(verticalPaddingDp),
    )
    background = context.tvFocusBackground(radiusDp)
    isFocusable = true
    isClickable = true
    if (iconRes != 0) {
        setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
        compoundDrawablePadding = context.dp(9)
    }
}

fun TextView.enableTvMarquee() {
    isSingleLine = true
    ellipsize = TextUtils.TruncateAt.MARQUEE
    marqueeRepeatLimit = -1
    isSelected = true
}

fun View.redirectDpadLeftTo(target: View) {
    nextFocusLeftId = target.id
    redirectDpad(KeyEvent.KEYCODE_DPAD_LEFT, target)
}

fun View.redirectDpadRightTo(target: View) {
    nextFocusRightId = target.id
    redirectDpad(KeyEvent.KEYCODE_DPAD_RIGHT, target)
}

private fun View.redirectDpad(key: Int, target: View) {
    setOnKeyListener { _, keyCode, event ->
        keyCode == key &&
            event.action == KeyEvent.ACTION_DOWN &&
            target.requestFocus()
    }
}
