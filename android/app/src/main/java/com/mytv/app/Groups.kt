package com.mytv.app

object Groups {
    const val OTHER = "أخرى"
    const val ADULT = "للكبار +18"
    const val SPORTS = "رياضة"

    /** iptv-org category names -> Arabic group names. */
    private val CATEGORIES = mapOf(
        "Entertainment" to "ترفيه عام",
        "Family" to "ترفيه عام",
        "Lifestyle" to "ترفيه عام",
        "Cooking" to "ترفيه عام",
        "Travel" to "ترفيه عام",
        "Shop" to "ترفيه عام",
        "Movies" to "أفلام",
        "Series" to "مسلسلات",
        "Comedy" to "كوميديا",
        "Music" to "موسيقى",
        "Kids" to "أطفال",
        "Animation" to "أطفال",
        "News" to "أخبار",
        "Business" to "أخبار",
        "Weather" to "أخبار",
        "Sports" to SPORTS,
        "Outdoor" to SPORTS,
        "Documentary" to "ثقافة ووثائقي",
        "Culture" to "ثقافة ووثائقي",
        "Education" to "ثقافة ووثائقي",
        "Science" to "ثقافة ووثائقي",
        "Classic" to "ثقافة ووثائقي",
        "Religious" to "ديني",
        "General" to "عام",
        "Legislative" to "عام",
        "Auto" to "ترفيه عام",
        "XXX" to ADULT,
    )

    /** Display order; groups not listed here come after, alphabetically. */
    val ORDER = listOf(
        "رياضة عربية", "كرة القدم", "قتال وملاكمة", "سباقات سيارات وخيول", "غولف وتنس",
        "رياضات أمريكية", "بوكر وألعاب", "رياضات متنوعة", SPORTS,
        "ترفيه عام", "أفلام", "مسلسلات", "كوميديا", "موسيقى", "أطفال", "أخبار",
        "ثقافة ووثائقي", "ديني", "عام", OTHER, ADULT,
    )

    private val arabic = Regex("[\\u0600-\\u06FF]")

    fun map(groupTitle: String?, source: Source): String {
        if (source.adult) return ADULT
        val parts = groupTitle.orEmpty().split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return source.defaultGroup ?: OTHER
        // Our own playlist already uses Arabic group names.
        parts.firstOrNull { arabic.containsMatchIn(it) }?.let { return it }
        for (p in parts) CATEGORIES[p]?.let { return it }
        return source.defaultGroup ?: OTHER
    }

    private fun rank(group: String): Int = ORDER.indexOf(group).let { if (it >= 0) it else ORDER.size }

    val comparator: Comparator<String> = compareBy<String>({ rank(it) }, { it })
}
