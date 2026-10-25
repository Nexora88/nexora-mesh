package org.nexora.mesh

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small local-only trusted-contact store. It stores public identity material, never session secrets. */
class TrustedContactStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("trusted_mesh_contacts_v1", Context.MODE_PRIVATE)

    data class Entry(val label: String, val publicKeyBase64: String, val fingerprint: String, val addedAt: Long)

    @Synchronized
    fun all(): List<Entry> {
        val raw = prefs.getString(KEY_CONTACTS, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val label = item.optString("label").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val key = item.optString("key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val fingerprint = item.optString("fingerprint").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Entry(label, key, fingerprint, item.optLong("addedAt"))
            }
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized
    fun save(contact: ContactQr.Contact): Entry {
        val current = all().filterNot { it.fingerprint == contact.fingerprint }.toMutableList()
        val entry = Entry(contact.label, contact.publicKeyBase64, contact.fingerprint, System.currentTimeMillis())
        current.add(entry)
        val array = JSONArray()
        current.forEach { item ->
            array.put(JSONObject().put("label", item.label).put("key", item.publicKeyBase64)
                .put("fingerprint", item.fingerprint).put("addedAt", item.addedAt))
        }
        check(prefs.edit().putString(KEY_CONTACTS, array.toString()).commit()) { "Could not persist trusted contact" }
        return entry
    }

    @Synchronized
    fun remove(fingerprint: String) {
        val array = JSONArray()
        all().filterNot { it.fingerprint == fingerprint }.forEach { item ->
            array.put(JSONObject().put("label", item.label).put("key", item.publicKeyBase64)
                .put("fingerprint", item.fingerprint).put("addedAt", item.addedAt))
        }
        prefs.edit().putString(KEY_CONTACTS, array.toString()).apply()
    }

    companion object { private const val KEY_CONTACTS = "contacts" }
}
