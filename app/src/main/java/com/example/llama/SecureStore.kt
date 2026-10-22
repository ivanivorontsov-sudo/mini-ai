package com.example.llama

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** GitHub token typed by the user once. Stored only on the phone, encrypted with an Android Keystore key. */
object SecureStore {
    private fun prefs(c: Context) = EncryptedSharedPreferences.create(
        c, "secure",
        MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    fun githubToken(c: Context): String? = runCatching { prefs(c).getString("gh", null) }.getOrNull()?.takeIf { it.isNotBlank() }
    fun setGithubToken(c: Context, t: String?) {
        prefs(c).edit().apply { if (t.isNullOrBlank()) remove("gh") else putString("gh", t.trim()) }.apply()
    }
}
