package com.prayerhome.quran.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.prayerhome.quran.R

object Fonts {
    private fun w(n: Int) = FontWeight(n)
    val Outfit = FontFamily(Font(R.font.outfit_400, w(400)), Font(R.font.outfit_500, w(500)), Font(R.font.outfit_600, w(600)))
    val Literata = FontFamily(Font(R.font.literata_400, w(400)), Font(R.font.literata_500, w(500)), Font(R.font.literata_600, w(600)))
    /**
     * Gold Leaf's sans, set one step heavier than asked for (400 draws the 500 face, and so on): dark text on
     * the light parchment washes out at the regular weights on a TV.
     */
    val Figtree = FontFamily(Font(R.font.figtree_500, w(400)), Font(R.font.figtree_600, w(500)), Font(R.font.figtree_700, w(600)))
    /** Gold Leaf's serif: moderate stroke contrast, so its thin strokes stay solid at TV distance. */
    val Lora = FontFamily(
        Font(R.font.lora_500, w(500)), Font(R.font.lora_600, w(600)), Font(R.font.lora_700, w(700)),
        Font(R.font.lora_500_italic, w(500), FontStyle.Italic), Font(R.font.lora_600_italic, w(600), FontStyle.Italic),
    )
    val ReemKufi = FontFamily(Font(R.font.reemkufi_400, w(400)), Font(R.font.reemkufi_500, w(500)))
    val ElMessiri = FontFamily(Font(R.font.elmessiri_500, w(500)), Font(R.font.elmessiri_600, w(600)))
    val ArefRuqaa = FontFamily(Font(R.font.arefruqaa_700, w(700)))
}

/** A Quran text face, with the size and line height the designs use for it. */
class QuranFont(val name: String, val desc: String, val family: FontFamily, val lineHeight: Float, val size: Int)

val QURAN_FONTS = listOf(
    QuranFont("Scheherazade New", "Traditional Naskh", FontFamily(Font(R.font.q_scheherazade)), 2.15f, 56),
    QuranFont("Amiri Quran", "Classic Mushaf style", FontFamily(Font(R.font.q_amiriquran)), 2.25f, 52),
    QuranFont("Harmattan", "Soft, rounded Naskh", FontFamily(Font(R.font.q_harmattan)), 2.05f, 58),
    QuranFont("Lateef", "Elegant, open Naskh", FontFamily(Font(R.font.q_lateef)), 2.0f, 66),
)

enum class Deco { AMBER, INDIGO, GOLD }

/** One of the three designs: Q2 Night Amber, Q4 Midnight Indigo, Q13 Gold Leaf. */
class QTheme(
    val name: String,
    val deco: Deco,
    /** The Quran face the design pairs with this theme (index into [QURAN_FONTS]). */
    val pairedFont: Int,
    val dark: Boolean,
    val bg: Color,
    val panel: Color,
    val line: Color,
    /** Buttons, focus rings, progress. */
    val accent: Color,
    val onAccent: Color,
    val text: Color,
    val muted: Color,
    val card: Color,
    val border: Color,
    val popup: Color,
    /** Verse markers and the basmala. */
    val gold: Color,
    /** Arabic UI labels (surah names in lists, headings). */
    val arAccent: Color,
    val verse: Color,
    val recited: Color,
    val pick: Color,
    val playerBg: Color,
    val scrim: Color,
    val badgeStroke: Color,
    val badgeText: Color,
    val track: Color,
    val fill: Color,
    val knob: Color,
    val iconOnCard: Color,
    val monoBg: Color,
    val monoFg: Color,
    val ui: FontFamily,
    val display: FontFamily,
    val displayWeight: Int,
    val arUi: FontFamily,
    val arUiWeight: Int,
    val sideW: Int,
    val railW: Int,
    val colW: Int,
    val topPad: Int,
    val gap: Int,
    /** Corner radius of toolbar buttons (999 = pill). */
    val toolRadius: Int,
    val markerSize: Int,
)

private fun c(v: Long) = Color(0xFF000000 or v)

val THEMES = listOf(
    QTheme(
        "Night Amber", Deco.AMBER, 0, true,
        bg = c(0x1A1816), panel = c(0x211E1B), line = c(0x34302A), accent = c(0xD5A657), onAccent = c(0x1A1816),
        text = c(0xE9DFC8), muted = c(0xA39882), card = c(0x2A2520), border = c(0x5A4A2E), popup = c(0x24201C),
        gold = c(0xD5A657), arAccent = c(0xD5A657), verse = c(0xE9DFC8), recited = c(0x3A2F1F), pick = c(0x2A2520),
        playerBg = c(0x211E1B), scrim = Color(26, 24, 22, 194), badgeStroke = c(0x6B5F4B), badgeText = c(0xC9BEA6),
        track = c(0x34302A), fill = c(0xD5A657), knob = c(0xE9DFC8), iconOnCard = c(0xE9DFC8),
        monoBg = c(0xD5A657), monoFg = c(0x1A1816),
        ui = Fonts.Outfit, display = Fonts.Outfit, displayWeight = 500, arUi = Fonts.ReemKufi, arUiWeight = 500,
        sideW = 540, railW = 104, colW = 1100, topPad = 34, gap = 16, toolRadius = 12, markerSize = 50,
    ),
    QTheme(
        "Midnight Indigo", Deco.INDIGO, 3, true,
        bg = c(0x0F1424), panel = c(0x131A2C), line = c(0x232B45), accent = c(0x9DB8E0), onAccent = c(0x0F1424),
        text = c(0xDCE2EE), muted = c(0x8F9AB5), card = c(0x1B2440), border = c(0x34426B), popup = c(0x161E33),
        gold = c(0xD8C08A), arAccent = c(0xD8C08A), verse = c(0xC3CBDD), recited = c(0x1E2846), pick = c(0x1B2440),
        playerBg = c(0x141B30), scrim = Color(15, 20, 36, 194), badgeStroke = c(0x4A5780), badgeText = c(0xB5BFD4),
        track = c(0x232B45), fill = c(0x9DB8E0), knob = c(0xF2F5FA), iconOnCard = c(0xDCE2EE),
        monoBg = c(0xD8C08A), monoFg = c(0x0F1424),
        ui = Fonts.Literata, display = Fonts.Literata, displayWeight = 500, arUi = Fonts.ElMessiri, arUiWeight = 600,
        sideW = 520, railW = 100, colW = 1100, topPad = 34, gap = 14, toolRadius = 12, markerSize = 50,
    ),
    QTheme(
        "Gold Leaf", Deco.GOLD, 2, false,
        // A soft, warm parchment rather than near-white: easy on the eyes on a TV in a dim room.
        bg = c(0xE4D9C1), panel = c(0xDACDB0), line = c(0xC9B993), accent = c(0x8A6620), onAccent = c(0xF6EEDC),
        text = c(0x26221C), muted = c(0x5C5446), card = c(0xD6C59E), border = c(0xC4B28A), popup = c(0xEAE0CA),
        gold = c(0xAD893C), arAccent = c(0x7A5A17), verse = c(0x26221C), recited = c(0xD8C7A0), pick = c(0xEFE6D2),
        playerBg = c(0xDCCFB2), scrim = Color(228, 217, 193, 204), badgeStroke = c(0xB39C6A), badgeText = c(0x7A5A17),
        track = c(0xCDBC95), fill = c(0x9C772C), knob = c(0x7A5A17), iconOnCard = c(0x7A5A17),
        monoBg = c(0x8A6620), monoFg = c(0xF6EEDC),
        ui = Fonts.Figtree, display = Fonts.Lora, displayWeight = 600, arUi = Fonts.ArefRuqaa, arUiWeight = 700,
        sideW = 480, railW = 100, colW = 1120, topPad = 30, gap = 14, toolRadius = 999, markerSize = 48,
    ),
)
