package com.trailpieces.app.tutorial

import android.content.Context

/** Survives process death. Cleared when app storage is wiped. */
class TutorialStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isComplete(): Boolean = prefs.getBoolean(KEY, false)

    fun markComplete() {
        prefs.edit().putBoolean(KEY, true).commit()
    }

    private companion object {
        const val FILE = "trail_pieces"
        const val KEY = "tutorial_complete"
    }
}
