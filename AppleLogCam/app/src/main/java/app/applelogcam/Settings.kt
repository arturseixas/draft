package app.applelogcam

import android.content.SharedPreferences

enum class Profile(val label: String) {
    APPLE_LOG("APPLE LOG"),
    HLG("HLG"),
    REC709("REC.709")
}

/** Preferências que sobrevivem entre aberturas do app. */
class CamSettings(private val prefs: SharedPreferences) {
    var front = prefs.getBoolean("front", false)
    var height = prefs.getInt("height", 2160)
    var fps = prefs.getInt("fps", 30)
    var profile = Profile.entries.getOrElse(prefs.getInt("profile", 0)) { Profile.APPLE_LOG }
    var bitrateMbps = prefs.getInt("bitrate", 100)
    var audio = prefs.getBoolean("audio", true)
    var stabilization = prefs.getBoolean("stab", true)
    var sharpening = prefs.getBoolean("sharp", false)
    var noiseReduction = prefs.getInt("nr", 1)
    var antibanding = prefs.getInt("antiband", 2)
    var lut = prefs.getBoolean("lut", true)
    var zebra = prefs.getBoolean("zebra", false)
    var zebraLevel = prefs.getInt("zebraLevel", 95)
    var peaking = prefs.getBoolean("peaking", false)
    var falseColor = prefs.getBoolean("falseColor", false)
    var grid = prefs.getBoolean("grid", false)
    var frameLine = prefs.getInt("frameLine", 0)
    var shutterAngle = prefs.getBoolean("shutterAngle", true)
    var clip = prefs.getInt("clip", 1)
    var lastClip: String? = prefs.getString("lastClip", null)
    var vertical = prefs.getBoolean("vertical", false)
    var lutName: String? = prefs.getString("lutName", null)
    var lutBurn = prefs.getBoolean("lutBurn", false)
    var folders: Set<String> = HashSet(prefs.getStringSet("folders", emptySet()) ?: emptySet())
    var recordFolder: String = prefs.getString("recordFolder", "") ?: ""

    fun save() {
        prefs.edit()
            .putBoolean("front", front)
            .putInt("height", height)
            .putInt("fps", fps)
            .putInt("profile", profile.ordinal)
            .putInt("bitrate", bitrateMbps)
            .putBoolean("audio", audio)
            .putBoolean("stab", stabilization)
            .putBoolean("sharp", sharpening)
            .putInt("nr", noiseReduction)
            .putInt("antiband", antibanding)
            .putBoolean("lut", lut)
            .putBoolean("zebra", zebra)
            .putInt("zebraLevel", zebraLevel)
            .putBoolean("peaking", peaking)
            .putBoolean("falseColor", falseColor)
            .putBoolean("grid", grid)
            .putInt("frameLine", frameLine)
            .putBoolean("shutterAngle", shutterAngle)
            .putInt("clip", clip)
            .putString("lastClip", lastClip)
            .putBoolean("vertical", vertical)
            .putString("lutName", lutName)
            .putBoolean("lutBurn", lutBurn)
            .putStringSet("folders", HashSet(folders))
            .putString("recordFolder", recordFolder)
            .apply()
    }
}
