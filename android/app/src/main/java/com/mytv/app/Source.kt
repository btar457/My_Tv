package com.mytv.app

import org.json.JSONArray
import org.json.JSONObject

data class Source(
    val name: String,
    val url: String,
    val adult: Boolean = false,
    val defaultGroup: String? = null,
    /** Tried in order when [url] fails (built-in sources only, not saved). */
    val mirrors: List<String> = emptyList(),
    /** Keep only entries whose group-title contains this (built-in sources only). */
    val requireGroup: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("url", url)
        if (adult) put("adult", true)
        defaultGroup?.let { put("group", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = Source(
            name = o.getString("name"),
            url = o.getString("url"),
            adult = o.optBoolean("adult", false),
            defaultGroup = o.optString("group").ifBlank { null },
        )

        fun listToJson(list: List<Source>) = JSONArray().apply { list.forEach { put(it.toJson()) } }

        fun listFromJson(text: String): List<Source> {
            val arr = JSONArray(text)
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }

        val DEFAULTS = listOf(
            Source(
                name = "قائمتي الرياضية",
                url = "https://raw.githubusercontent.com/btar457/My_Tv/main/playlist.m3u",
                defaultGroup = Groups.SPORTS,
            ),
            Source(
                name = "القنوات العربية (iptv-org)",
                url = "https://iptv-org.github.io/iptv/languages/ara.m3u",
            ),
        )

    }
}
