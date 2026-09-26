package com.mytv.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.security.MessageDigest

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("mytv", Context.MODE_PRIVATE)

    var sources: List<Source>
        get() = sp.getString("sources", null)
            ?.let { runCatching { Source.listFromJson(it) }.getOrNull() }
            ?: Source.DEFAULTS
        set(value) = sp.edit().putString("sources", Source.listToJson(value).toString()).apply()

    var favorites: Set<String>
        get() = sp.getStringSet("favorites", emptySet())!!.toSet()
        set(value) = sp.edit().putStringSet("favorites", value).apply()

    fun toggleFavorite(url: String): Boolean {
        val fav = favorites.toMutableSet()
        val added = fav.add(url) || run { fav.remove(url); false }
        favorites = fav
        return added
    }

    /** Most recent first. */
    var recents: List<String>
        get() = sp.getString("recents", null)?.let { s ->
            val a = JSONArray(s); (0 until a.length()).map { a.getString(it) }
        } ?: emptyList()
        set(value) = sp.edit().putString("recents", JSONArray(value.take(40)).toString()).apply()

    fun addRecent(url: String) {
        recents = listOf(url) + recents.filter { it != url }
    }

    var dead: Set<String>
        get() = sp.getStringSet("dead", emptySet())!!.toSet()
        set(value) = sp.edit().putStringSet("dead", value).apply()

    var hideDead: Boolean
        get() = sp.getBoolean("hide_dead", true)
        set(value) = sp.edit().putBoolean("hide_dead", value).apply()

    var lastRefresh: Long
        get() = sp.getLong("last_refresh", 0)
        set(value) = sp.edit().putLong("last_refresh", value).apply()

    // ---- adult section

    var adultEnabled: Boolean
        get() = sp.getBoolean("adult_enabled", false)
        set(value) = sp.edit().putBoolean("adult_enabled", value).apply()

    val hasPin: Boolean get() = sp.contains("pin_hash")

    fun setPin(pin: String) = sp.edit().putString("pin_hash", hash(pin)).apply()

    fun checkPin(pin: String) = sp.getString("pin_hash", null) == hash(pin)

    fun clearPin() = sp.edit().remove("pin_hash").apply()

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("mytv:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        /** Unlocked only for the life of the process; locks again when the app is closed. */
        @Volatile var adultUnlocked = false
    }
}
