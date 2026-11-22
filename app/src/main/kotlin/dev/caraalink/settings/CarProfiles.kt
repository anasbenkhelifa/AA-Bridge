package dev.caraalink.settings

import android.content.Context
import org.json.JSONObject

/**
 * The video/AA settings that vary per car (screen shape, resolution, DPI,
 * fill mode, bitrate, audio routing, what the car's buttons do), kept two
 * ways:
 *
 * - Named profiles, saved and loaded by hand from Advanced > Car profiles.
 * - A memory per car, with nothing to manage: the head unit names itself when
 *   it connects, and the settings it was last used with come back.
 */
object CarProfiles {
    private const val PREFS_FILE = "car_profiles"
    private const val MEMORY_FILE = "car_memory"
    private const val LAST_CAR = "last_car"
    private const val SNAPSHOT_PREFIX = "car:"
    private const val SAVED_AT = "saved_at"
    /** A head unit that reported a new id on every connect would otherwise grow this without end. */
    internal const val MAX_REMEMBERED_CARS = 8

    /** Keys worth remembering per car; everything else in Settings is phone-wide, not car-specific. */
    private val PROFILE_KEYS = listOf(
        "car_orientation",
        "video_resolution_mode",
        "video_resolution_custom_width",
        "video_resolution_custom_height",
        "video_frame_rate",
        "video_bitrate_kbps",
        "video_dpi_auto",
        "video_dpi_manual",
        "video_downscale_720p",
        "aa_fill_mode",
        "aa_auto_crop",
        "aa_audio_route",
        CarKeys.PREF_KEY,
    )

    private fun store(context: Context) = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private fun memory(context: Context) = context.getSharedPreferences(MEMORY_FILE, Context.MODE_PRIVATE)
    private fun logger(context: Context) = dev.caraalink.diagnostics.DiagnosticLogger(context, "car-profiles")

    private fun snapshot(context: Context): JSONObject {
        val defaults = PreferenceManagerCompat.default(context)
        val snapshot = JSONObject()
        for (key in PROFILE_KEYS) {
            when (val value = defaults.all[key]) {
                is Boolean -> snapshot.put(key, value)
                is String -> snapshot.put(key, value)
                null -> {}
                else -> snapshot.put(key, value.toString())
            }
        }
        return snapshot
    }

    /** [removeMissing] puts a setting the snapshot doesn't have back to its default, instead of leaving the current value. */
    private fun restore(context: Context, snapshot: JSONObject, removeMissing: Boolean) {
        val editor = PreferenceManagerCompat.default(context).edit()
        for (key in PROFILE_KEYS) {
            if (!snapshot.has(key)) {
                if (removeMissing) editor.remove(key)
                continue
            }
            when (val value = snapshot.get(key)) {
                is Boolean -> editor.putBoolean(key, value)
                else -> editor.putString(key, value.toString())
            }
        }
        editor.apply()
    }

    fun listProfiles(context: Context): List<String> =
        store(context).all.keys.sorted()

    fun saveProfile(context: Context, name: String) {
        if (name.isBlank()) return
        val snapshot = snapshot(context)
        store(context).edit().putString(name.trim(), snapshot.toString()).apply()
        logger(context).log("saved profile '${name.trim()}': $snapshot")
    }

    /** Returns false if [name] isn't a saved profile. */
    fun loadProfile(context: Context, name: String): Boolean {
        val json = store(context).getString(name, null) ?: return false
        val snapshot = JSONObject(json)
        restore(context, snapshot, removeMissing = false)
        logger(context).log("loaded profile '$name': $snapshot")
        return true
    }

    fun deleteProfile(context: Context, name: String) {
        store(context).edit().remove(name).apply()
        logger(context).log("deleted profile '$name'")
    }

    // --- memory per car ---

    enum class CarSwitch { SAME_CAR, RESTORE, NEW_CAR }

    /**
     * The settings on screen always belong to the car that connected last, so
     * changes made away from the car are kept for it. Only a *different* car
     * swaps them: a known one gets its own back, a new one starts from what
     * is there now.
     */
    internal fun carSwitch(lastCarId: String?, carId: String, remembered: Boolean): CarSwitch = when {
        carId == lastCarId -> CarSwitch.SAME_CAR
        remembered -> CarSwitch.RESTORE
        else -> CarSwitch.NEW_CAR
    }

    /**
     * A stable, short name for a head unit from what it reports about itself
     * (CLIENT_INFO's HUID, else HUName), or null when it reports neither.
     * Hashed, because logs get shared and a head unit id identifies a car.
     */
    fun carId(huid: String?, huName: String?): String? {
        val raw = huid?.takeIf { it.isNotBlank() } ?: huName?.takeIf { it.isNotBlank() } ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    /** The oldest snapshots beyond [keep], by when they were saved. */
    internal fun snapshotsToDrop(savedAt: Map<String, Long>, keep: Int): List<String> =
        savedAt.entries.sortedByDescending { it.value }.drop(keep).map { it.key }

    /**
     * Called when a head unit identifies itself. Returns true when another
     * car's settings were just put back, i.e. the settings changed.
     */
    fun onCarConnected(context: Context, carId: String): Boolean {
        if (!AppSettings.rememberPerCar(context)) return false
        val memory = memory(context)
        val last = memory.getString(LAST_CAR, null)
        val change = carSwitch(last, carId, memory.contains(SNAPSHOT_PREFIX + carId))
        if (change == CarSwitch.SAME_CAR) return false
        val editor = memory.edit().putString(LAST_CAR, carId)
        if (last != null) {
            editor.putString(SNAPSHOT_PREFIX + last, snapshot(context).put(SAVED_AT, System.currentTimeMillis()).toString())
        }
        // This car and the one just saved always stay; the rest are kept newest first.
        val kept = setOfNotNull(SNAPSHOT_PREFIX + carId, last?.let { SNAPSHOT_PREFIX + it })
        val others = memory.all.filterKeys { it.startsWith(SNAPSHOT_PREFIX) && it !in kept }
            .mapValues { runCatching { JSONObject(it.value.toString()).optLong(SAVED_AT) }.getOrDefault(0L) }
        snapshotsToDrop(others, MAX_REMEMBERED_CARS - kept.size).forEach { editor.remove(it) }
        editor.apply()
        logger(context).log("car $carId connected (last ${last ?: "none"}): $change")
        if (change != CarSwitch.RESTORE) return false
        val snapshot = runCatching { JSONObject(memory.getString(SNAPSHOT_PREFIX + carId, null) ?: "{}") }.getOrElse { return false }
        restore(context, snapshot, removeMissing = true)
        logger(context).log("restored settings for car $carId: $snapshot")
        return true
    }
}

/** Thin wrapper so CarProfiles doesn't need an androidx.preference import for one call. */
private object PreferenceManagerCompat {
    fun default(context: Context) = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
}
