package com.simplereader.app.ui

import android.view.View
import android.view.ViewParent

/** Keeps the reader's unchanged XML parent lookup safe when the parent has no resource id. */
val ViewParent.id: Int
    get() = (this as? View)?.id ?: View.NO_ID
