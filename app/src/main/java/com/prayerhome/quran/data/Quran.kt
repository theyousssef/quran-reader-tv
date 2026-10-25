package com.prayerhome.quran.data

import android.content.Context

/** A verse address: surah 1..114, ayah 1..n. */
data class Ref(val surah: Int, val ayah: Int) {
    val index: Int get() = Quran.indexOf(surah, ayah)
}

/**
 * The bundled Tanzil Uthmani text (verbatim in assets; the APK stores it deflated, ~300 KB) plus surah, juz and page metadata.
 * Verses are addressed either as [Ref] or by a global 0-based index 0..6235.
 */
class Quran private constructor(private val verses: Array<String>) {

    /** Verse text for display; verse 1 of each surah (except Al-Fatiha) has the basmala prefix removed. */
    fun text(index: Int): String = verses[index]
    fun text(s: Int, a: Int): String = verses[indexOf(s, a)]

    /** The basmala as written in Al-Fatiha 1:1, shown above every surah except At-Tawbah. */
    val basmala: String get() = verses[0]

    companion object {
        const val VERSES = 6236
        const val PAGES = 604

        private val surahStart = IntArray(115).also { a ->
            for (s in 1..114) a[s] = a[s - 1] + (if (s == 1) 0 else Meta.ayahs[s - 2])
        }

        fun ayahs(s: Int) = Meta.ayahs[s - 1]
        fun nameAr(s: Int) = Meta.nameAr[s - 1]
        fun nameEn(s: Int) = Meta.nameEn[s - 1]
        fun meccan(s: Int) = Meta.meccan[s - 1] == 1
        fun indexOf(s: Int, a: Int) = surahStart[s] + a - 1
        fun surahStart(s: Int) = surahStart[s]

        fun refOf(index: Int): Ref {
            var lo = 1; var hi = 114
            while (lo < hi) { val m = (lo + hi + 1) / 2; if (surahStart[m] <= index) lo = m else hi = m - 1 }
            return Ref(lo, index - surahStart[lo] + 1)
        }

        /** Mushaf page (1..604) the verse starts on. */
        fun pageOf(index: Int): Int = upper(Meta.pageStart, index)
        fun juzOf(index: Int): Int = upper(Meta.juzStart, index)
        fun juzStart(juz: Int): Ref = refOf(Meta.juzStart[juz - 1])

        /** 1-based position of the last start ≤ index. */
        private fun upper(starts: IntArray, index: Int): Int {
            var lo = 0; var hi = starts.size - 1
            while (lo < hi) { val m = (lo + hi + 1) / 2; if (starts[m] <= index) lo = m else hi = m - 1 }
            return lo + 1
        }

        /** Surahs after which no basmala is recited: Al-Fatiha's basmala is its verse 1, At-Tawbah has none. */
        fun hasBasmalaHeader(s: Int) = s != 1 && s != 9

        fun load(ctx: Context): Quran {
            val out = arrayOfNulls<String>(VERSES)
            var lastWordOfBasmala: String? = null
            ctx.assets.open("quran-uthmani.txt").use { raw ->
                raw.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        if (line.isEmpty() || !line[0].isDigit()) continue // Tanzil's licence block
                        val p1 = line.indexOf('|'); val p2 = line.indexOf('|', p1 + 1)
                        val s = line.substring(0, p1).toInt(); val a = line.substring(p1 + 1, p2).toInt()
                        var t = line.substring(p2 + 1)
                        if (s == 1 && a == 1) lastWordOfBasmala = t.substringAfterLast(' ') + " "
                        if (a == 1 && hasBasmalaHeader(s)) {
                            // Tanzil prefixes verse 1 with the basmala (sometimes as بِّسْمِ); it is shown and recited separately.
                            val k = t.indexOf(lastWordOfBasmala!!)
                            if (k in 0..40) t = t.substring(k + lastWordOfBasmala!!.length)
                        }
                        out[indexOf(s, a)] = t
                    }
                }
            }
            @Suppress("UNCHECKED_CAST")
            return Quran(out as Array<String>)
        }

        private val arabicDigits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
        fun arabicNumber(n: Int): String = n.toString().map { arabicDigits[it - '0'] }.joinToString("")

        val juzNames = arrayOf(
            "الم", "سيقول", "تلك الرسل", "لن تنالوا", "والمحصنات", "لا يحب الله", "وإذا سمعوا", "ولو أننا",
            "قال الملأ", "واعلموا", "يعتذرون", "وما من دابة", "وما أبرئ", "ربما", "سبحان الذي", "قال ألم",
            "اقترب", "قد أفلح", "وقال الذين", "أمن خلق", "اتل ما أوحي", "ومن يقنت", "وما لي", "فمن أظلم",
            "إليه يرد", "حم", "قال فما خطبكم", "قد سمع الله", "تبارك الذي", "عم",
        )
    }
}
