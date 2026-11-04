package com.pixelagent.app.platform

import android.content.Context
import com.pixelagent.app.core.AgentSettings
import com.pixelagent.app.core.ProviderProfile
import com.pixelagent.app.core.objects
import org.json.JSONArray
import org.json.JSONObject

/** Persists settings and provider profiles in the app's private storage (backups are disabled in the manifest). */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("pixel_agent", Context.MODE_PRIVATE)

    fun loadSettings(): AgentSettings = try {
        AgentSettings.fromJson(JSONObject(prefs.getString("settings", "{}") ?: "{}"))
    } catch (e: Exception) {
        AgentSettings()
    }

    fun saveSettings(s: AgentSettings) {
        prefs.edit().putString("settings", s.toJson().toString()).apply()
    }

    fun loadProfiles(): List<ProviderProfile> = try {
        JSONArray(prefs.getString("profiles", "[]") ?: "[]").objects().map { ProviderProfile.fromJson(it) }
    } catch (e: Exception) {
        emptyList()
    }

    fun saveProfiles(list: List<ProviderProfile>) {
        val arr = JSONArray()
        for (p in list) arr.put(p.toJson())
        prefs.edit().putString("profiles", arr.toString()).apply()
    }

    var activeProfileId: String
        get() = prefs.getString("activeProfile", "") ?: ""
        set(v) {
            prefs.edit().putString("activeProfile", v).apply()
        }
}
