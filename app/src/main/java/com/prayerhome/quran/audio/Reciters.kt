package com.prayerhome.quran.audio

/** A reciter on everyayah.com; per-verse files live at https://everyayah.com/data/<folder>/SSSAAA.mp3 */
data class Reciter(val folder: String, val mono: String, val en: String, val ar: String, val style: String, val short: String)

object Reciters {
    val all = listOf(
        Reciter("Alafasy_128kbps", "AF", "Mishary Alafasy", "مشاري العفاسي", "Murattal", "Alafasy"),
        Reciter("Husary_128kbps", "HS", "Mahmoud Khalil Al-Husary", "محمود خليل الحصري", "Murattal", "Al-Husary"),
        Reciter("Minshawy_Murattal_128kbps", "MN", "Mohamed Siddiq El-Minshawi", "محمد صديق المنشاوي", "Murattal", "El-Minshawi"),
        Reciter("Minshawy_Mujawwad_192kbps", "MJ", "El-Minshawi (Mujawwad)", "المنشاوي مجود", "Mujawwad", "Minshawi · Mujawwad"),
        Reciter("Abdul_Basit_Murattal_192kbps", "AB", "Abdul Basit Abdul Samad", "عبد الباسط عبد الصمد", "Murattal", "Abdul Basit"),
        Reciter("Abdul_Basit_Mujawwad_128kbps", "AM", "Abdul Basit (Mujawwad)", "عبد الباسط مجود", "Mujawwad", "Abdul Basit · Mujawwad"),
        Reciter("Abdurrahmaan_As-Sudais_192kbps", "SD", "Abdul Rahman Al-Sudais", "عبد الرحمن السديس", "Murattal", "Al-Sudais"),
        Reciter("MaherAlMuaiqly128kbps", "MQ", "Maher Al-Muaiqly", "ماهر المعيقلي", "Murattal", "Al-Muaiqly"),
        Reciter("Ghamadi_40kbps", "GH", "Saad Al-Ghamdi", "سعد الغامدي", "Murattal", "Al-Ghamdi"),
        Reciter("Yasser_Ad-Dussary_128kbps", "DS", "Yasser Al-Dosari", "ياسر الدوسري", "Murattal", "Al-Dosari"),
    )

    fun byFolder(folder: String) = all.firstOrNull { it.folder == folder } ?: all[0]

    fun url(r: Reciter, surah: Int, ayah: Int) =
        "https://everyayah.com/data/${r.folder}/%03d%03d.mp3".format(surah, ayah)
}
