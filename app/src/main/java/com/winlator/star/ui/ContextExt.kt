package com.winlator.star.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Compose's LocalContext is often a ContextThemeWrapper (e.g. inside a themed dialog/sheet
 * subtree), not the Activity itself, so a bare `context as Activity` cast throws
 * ClassCastException. Walk the wrapper chain to recover the hosting Activity instead.
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
