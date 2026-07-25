package dev.lokalgrid.app

import android.content.Context
import android.content.SharedPreferences

/**
 * The little that must survive a restart: whether onboarding has been walked, and
 * which node to talk to. Room holds the actual data later (§6); this is settings.
 *
 * The node URL lives here because the default (`10.0.2.2`) is emulator-only — on
 * a real phone it goes nowhere, which reads exactly like a broken app. Making it
 * editable in onboarding turns a mystery into a field.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("lokalgrid", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = sp.getBoolean(KEY_ONBOARDED, false)
        set(v) = sp.edit().putBoolean(KEY_ONBOARDED, v).apply()

    var nodeUrl: String
        get() = sp.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(v) = sp.edit().putString(KEY_URL, v.trim()).apply()

    /**
     * Last position seq this client has received, per node. Survives a restart so
     * reopening the app resumes a delta instead of re-streaming an hour — and it
     * is keyed by URL because a cursor means nothing on a different node.
     */
    fun posCursor(url: String): Long = sp.getLong(cursorKey(url), 0)

    fun setPosCursor(url: String, seq: Long) {
        sp.edit().putLong(cursorKey(url), seq).apply()
    }

    private fun cursorKey(url: String) = "pos_cursor::${url.trim()}"

    companion object {
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_URL = "node_url"

        /** 10.0.2.2 is the emulator's alias for the dev machine running the mock. */
        const val DEFAULT_URL = "ws://10.0.2.2:8787"
    }
}
