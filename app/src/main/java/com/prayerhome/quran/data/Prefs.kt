package com.prayerhome.quran.data

import android.content.Context

/** Small persisted settings and reading state (SharedPreferences, app-private). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)

    var theme: Int
        get() = sp.getInt("theme", 0)
        set(v) = sp.edit().putInt("theme", v).apply()

    var font: Int
        get() = sp.getInt("font", 0)
        set(v) = sp.edit().putInt("font", v).apply()

    /** Text size offset in design px, added to the chosen font's default size. */
    var sizeDelta: Int
        get() = sp.getInt("sizeDelta", 0)
        set(v) = sp.edit().putInt("sizeDelta", v).apply()

    var reciter: String
        get() = sp.getString("reciter", null) ?: "Alafasy_128kbps"
        set(v) = sp.edit().putString("reciter", v).apply()

    /** Exact scroll position: list item index + pixel offset, and the verse that was at the top. */
    var scrollItem: Int
        get() = sp.getInt("scrollItem", -1)
        set(v) = sp.edit().putInt("scrollItem", v).apply()
    var scrollOffset: Int
        get() = sp.getInt("scrollOffset", 0)
        set(v) = sp.edit().putInt("scrollOffset", v).apply()
    var topVerse: Int
        get() = sp.getInt("topVerse", 0)
        set(v) = sp.edit().putInt("topVerse", v).apply()

    /** The reading position when the app was last left (kept for reference; resuming uses [scrollItem]). */
    var continueVerse: Int
        get() = sp.getInt("continueVerse", -1)
        set(v) = sp.edit().putInt("continueVerse", v).apply()

    /** The single bookmarked verse (global index). There is always one: Al-Fatiha 1 by default, and an
     *  older "no bookmark" (-1) reads as Al-Fatiha 1. */
    var bookmark: Int
        get() = sp.getInt("bookmark", 0).let { if (it in 0 until Quran.VERSES) it else 0 }
        set(v) = sp.edit().putInt("bookmark", v).apply()

    var repeat: Boolean
        get() = sp.getBoolean("repeat", false)
        set(v) = sp.edit().putBoolean("repeat", v).apply()
}
