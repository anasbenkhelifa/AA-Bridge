package dev.caraalink.settings

import android.content.Context
import androidx.preference.PreferenceManager

/** What a button on the car does. [aaKeycode] is the Android key code Android Auto gets; null for our own actions. */
enum class CarKeyAction(val id: String, val aaKeycode: Int?) {
    NONE("none", null),
    CLOSE_AA("close", null),
    BACK("back", 4),
    HOME("home", 3),
    VOICE("voice", 84),
    PLAY_PAUSE("play_pause", 85),
    NEXT("next", 87),
    PREVIOUS("previous", 88),
    ANSWER_CALL("call", 5),
    END_CALL("end_call", 6),
}

/**
 * Which car button does what. The car reports a button as a bare number
 * (CarbitLink's SCREEN_EVENT type 4); only Home and Back are known ahead of
 * time, so every other button is learned by pressing it with the Car buttons
 * screen open.
 */
object CarKeys {
    /** Stored as `1042=close,1044=back`. One of the per-car settings (see CarProfiles). */
    const val PREF_KEY = "car_key_map"

    const val KEY_HOME = 1042
    const val KEY_BACK = 1044

    /** Home leaves Android Auto, as it always has. Back goes back inside it. */
    val DEFAULTS: Map<Int, CarKeyAction> = mapOf(KEY_HOME to CarKeyAction.CLOSE_AA, KEY_BACK to CarKeyAction.BACK)

    /** Every key code a button can send, offered to Android Auto at service discovery. */
    val AA_KEYCODES: List<Int> = CarKeyAction.values().mapNotNull { it.aaKeycode }

    /** The defaults with the stored choices on top. Entries that don't parse are skipped. */
    fun decode(stored: String?): Map<Int, CarKeyAction> {
        val map = LinkedHashMap(DEFAULTS)
        stored?.split(',')?.forEach { entry ->
            val key = entry.substringBefore('=').trim().toIntOrNull() ?: return@forEach
            val action = CarKeyAction.values().firstOrNull { it.id == entry.substringAfter('=', "").trim() } ?: return@forEach
            map[key] = action
        }
        return map
    }

    fun encode(map: Map<Int, CarKeyAction>): String =
        map.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.id}" }

    fun mapping(context: Context): Map<Int, CarKeyAction> =
        decode(PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_KEY, null))

    fun actionFor(context: Context, key: Int): CarKeyAction = mapping(context)[key] ?: CarKeyAction.NONE

    fun setAction(context: Context, key: Int, action: CarKeyAction) {
        val map = mapping(context) + (key to action)
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PREF_KEY, encode(map)).apply()
    }

    // --- buttons pressed since the app started, for the Car buttons screen ---

    /** [last] is the most recent press; [seen] every button pressed so far; [count] goes up on each press. */
    data class Pressed(val last: Int? = null, val seen: Set<Int> = emptySet(), val count: Int = 0)

    @Volatile
    var pressed = Pressed()
        private set

    private val listeners = ArrayList<(Pressed) -> Unit>()

    /** Called on the car link's thread for every button event. */
    @Synchronized
    fun report(key: Int) {
        pressed = Pressed(key, pressed.seen + key, pressed.count + 1)
        listeners.toList().forEach { it(pressed) }
    }

    @Synchronized
    fun addListener(listener: (Pressed) -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: (Pressed) -> Unit) {
        listeners.remove(listener)
    }
}
