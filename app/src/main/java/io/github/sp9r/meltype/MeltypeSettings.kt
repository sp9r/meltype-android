package io.github.sp9r.meltype

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class ThemeMode(val key: String, val labelJa: String) {
    SYSTEM("system", "システム連動"),
    LIGHT("light", "ライト"),
    DARK("dark", "ダーク");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

enum class OneHandedMode(val key: String, val labelJa: String) {
    FULL("full", "全幅"),
    LEFT("left", "左寄せ"),
    RIGHT("right", "右寄せ");

    companion object {
        fun fromKey(key: String?): OneHandedMode = entries.firstOrNull { it.key == key } ?: FULL
    }
}

enum class PunctuationStyle(val key: String, val comma: String, val period: String, val labelJa: String) {
    JAPANESE("ja", "、", "。", "。、"),
    WIDE_LATIN("wide_latin", "，", "．", "．，"),
    HALF("half", ",", ".", ".,");

    companion object {
        fun fromKey(key: String?): PunctuationStyle = entries.firstOrNull { it.key == key } ?: JAPANESE
    }
}

data class UserDictEntry(
    val reading: String,
    val word: String
)

class MeltypeSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var glideEnabled: Boolean
        get() = prefs.getBoolean(KEY_GLIDE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_GLIDE_ENABLED, value).apply()

    var glideSensitivity: Float
        get() = prefs.getFloat(KEY_GLIDE_SENSITIVITY, 1.0f).coerceIn(0.6f, 1.4f)
        set(value) = prefs.edit().putFloat(KEY_GLIDE_SENSITIVITY, value.coerceIn(0.6f, 1.4f)).apply()

    var themeMode: ThemeMode
        get() = ThemeMode.fromKey(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.key))
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value.key).apply()

    var oneHandedMode: OneHandedMode
        get() = OneHandedMode.fromKey(prefs.getString(KEY_ONE_HANDED, OneHandedMode.FULL.key))
        set(value) = prefs.edit().putString(KEY_ONE_HANDED, value.key).apply()

    var keyboardHeightScale: Float
        get() = prefs.getFloat(KEY_HEIGHT_SCALE, 1.0f).coerceIn(0.65f, 1.55f)
        set(value) = prefs.edit().putFloat(KEY_HEIGHT_SCALE, value.coerceIn(0.65f, 1.55f)).apply()

    var showNumberRow: Boolean
        get() = prefs.getBoolean(KEY_SHOW_NUMBER_ROW, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_NUMBER_ROW, value).apply()

    var autoReturnFromSymbols: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RETURN_SYMBOLS, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RETURN_SYMBOLS, value).apply()

    var flickSensitivity: Float
        get() = prefs.getFloat(KEY_FLICK_SENSITIVITY, 1.0f).coerceIn(0.7f, 1.4f)
        set(value) = prefs.edit().putFloat(KEY_FLICK_SENSITIVITY, value.coerceIn(0.7f, 1.4f)).apply()

    var autoCommitOnPunctuation: Boolean
        get() = prefs.getBoolean(KEY_AUTO_COMMIT_PUNCT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_COMMIT_PUNCT, value).apply()

    var punctuationStyle: PunctuationStyle
        get() = PunctuationStyle.fromKey(prefs.getString(KEY_PUNCT_STYLE, PunctuationStyle.JAPANESE.key))
        set(value) = prefs.edit().putString(KEY_PUNCT_STYLE, value.key).apply()

    var hapticFeedback: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC, value).apply()

    var showSubLabels: Boolean
        get() = prefs.getBoolean(KEY_SUB_LABELS, true)
        set(value) = prefs.edit().putBoolean(KEY_SUB_LABELS, value).apply()

    var learningEnabled: Boolean
        get() = prefs.getBoolean(KEY_LEARNING, true)
        set(value) = prefs.edit().putBoolean(KEY_LEARNING, value).apply()

    var typoCorrectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_TYPO_CORRECTION, true)
        set(value) = prefs.edit().putBoolean(KEY_TYPO_CORRECTION, value).apply()

    fun getRecentEmojis(): List<String> = readStringList(KEY_RECENT_EMOJIS)

    fun addRecentEmoji(emoji: String) {
        val clean = emoji.trim()
        if (clean.isEmpty()) return
        val next = (listOf(clean) + getRecentEmojis().filterNot { it == clean }).take(27)
        saveStringList(KEY_RECENT_EMOJIS, next)
    }

    fun getClipboardHistory(): List<String> = readStringList(KEY_CLIPBOARD_HISTORY)

    fun addClipboardItem(text: String) {
        if (text.isBlank() || text.length > 500) return
        val next = (listOf(text) + getClipboardHistory().filterNot { it == text }).take(12)
        saveStringList(KEY_CLIPBOARD_HISTORY, next)
    }

    private fun readStringList(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i)
                    if (v.isNotEmpty()) add(v)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveStringList(key: String, items: List<String>) {
        val arr = JSONArray()
        for (item in items) arr.put(item)
        prefs.edit().putString(key, arr.toString()).apply()
    }

    fun getUserDictionary(): List<UserDictEntry> {
        val raw = prefs.getString(KEY_USER_DICT, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val reading = obj.optString("reading").trim()
                    val word = obj.optString("word").trim()
                    if (reading.isNotEmpty() && word.isNotEmpty()) {
                        add(UserDictEntry(reading, word))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun addUserDictEntry(reading: String, word: String) {
        val r = reading.trim()
        val w = word.trim()
        if (r.isEmpty() || w.isEmpty()) return
        val current = getUserDictionary().filterNot { it.reading == r && it.word == w }.toMutableList()
        current.add(0, UserDictEntry(r, w))
        saveUserDict(current)
    }

    fun removeUserDictEntry(reading: String, word: String) {
        val current = getUserDictionary().filterNot { it.reading == reading && it.word == word }
        saveUserDict(current)
    }

    private fun saveUserDict(entries: List<UserDictEntry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().apply {
                put("reading", e.reading)
                put("word", e.word)
            })
        }
        prefs.edit().putString(KEY_USER_DICT, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "meltype_prefs"
        private const val KEY_GLIDE_ENABLED = "glide_enabled"
        private const val KEY_GLIDE_SENSITIVITY = "glide_sensitivity"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_ONE_HANDED = "one_handed_mode"
        private const val KEY_HEIGHT_SCALE = "keyboard_height_scale"
        private const val KEY_SHOW_NUMBER_ROW = "show_number_row"
        private const val KEY_AUTO_RETURN_SYMBOLS = "auto_return_symbols"
        private const val KEY_FLICK_SENSITIVITY = "flick_sensitivity"
        private const val KEY_AUTO_COMMIT_PUNCT = "auto_commit_punct"
        private const val KEY_PUNCT_STYLE = "punct_style"
        private const val KEY_HAPTIC = "haptic_feedback"
        private const val KEY_SUB_LABELS = "show_sub_labels"
        private const val KEY_LEARNING = "learning_enabled"
        private const val KEY_TYPO_CORRECTION = "typo_correction_enabled"
        private const val KEY_RECENT_EMOJIS = "recent_emojis"
        private const val KEY_CLIPBOARD_HISTORY = "clipboard_history"
        private const val KEY_USER_DICT = "user_dictionary"
    }
}
