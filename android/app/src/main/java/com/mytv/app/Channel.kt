package com.mytv.app

import org.json.JSONObject

data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    val adult: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("url", url)
        put("group", group)
        logo?.let { put("logo", it) }
        userAgent?.let { put("ua", it) }
        referrer?.let { put("ref", it) }
        if (adult) put("adult", true)
    }

    companion object {
        fun fromJson(o: JSONObject) = Channel(
            name = o.getString("name"),
            url = o.getString("url"),
            group = o.optString("group", Groups.OTHER),
            logo = o.optString("logo").ifBlank { null },
            userAgent = o.optString("ua").ifBlank { null },
            referrer = o.optString("ref").ifBlank { null },
            adult = o.optBoolean("adult", false),
        )
    }
}

/** One entry parsed from an M3U file, before it is mapped to our groups. */
data class M3uEntry(
    val name: String,
    val url: String,
    val attrs: Map<String, String>,
    val userAgent: String?,
    val referrer: String?,
)

object M3uParser {
    private val attrRe = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(text: String): List<M3uEntry> {
        val out = ArrayList<M3uEntry>()
        var name: String? = null
        var attrs: Map<String, String> = emptyMap()
        var ua: String? = null
        var ref: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    // The name follows the first comma that is outside an attribute value.
                    val sep = nameSeparator(line)
                    val info = if (sep >= 0) line.substring(0, sep) else line
                    name = if (sep >= 0) line.substring(sep + 1).trim() else ""
                    attrs = attrRe.findAll(info).associate { it.groupValues[1] to it.groupValues[2] }
                    ua = null
                    ref = null
                }
                line.startsWith("#EXTVLCOPT:") -> {
                    val opt = line.removePrefix("#EXTVLCOPT:")
                    when {
                        opt.startsWith("http-user-agent=") -> ua = opt.substringAfter('=')
                        opt.startsWith("http-referrer=") -> ref = opt.substringAfter('=')
                    }
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                name != null -> {
                    if (name.isNotEmpty() && (line.startsWith("http://") || line.startsWith("https://"))) {
                        out.add(M3uEntry(name, line, attrs, ua, ref))
                    }
                    name = null
                }
            }
        }
        return out
    }

    /** Index of the comma that separates attributes from the channel name, or -1. */
    private fun nameSeparator(line: String): Int {
        var inQuotes = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            if (c == ',' && !inQuotes) return i
        }
        return -1
    }
}
