package nu.shel.wldroid.proot

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.rootfsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "wldroid_rootfs_prefs",
)

/**
 * Persists rootfs environment metadata using Jetpack DataStore Preferences.
 *
 * Environments are serialized as JSON using Android's built-in [org.json] classes
 * to avoid pulling in an external serialization library.
 */
class RootfsStore(private val context: Context) {

    private val environmentsKey = stringPreferencesKey("environments_json")
    private val autoDeleteKey = booleanPreferencesKey("auto_delete_on_remove")

    fun getEnvironments(): Flow<List<RootfsEnvironment>> =
        context.rootfsDataStore.data.map { prefs ->
            val json = prefs[environmentsKey]
            if (json.isNullOrEmpty()) {
                emptyList()
            } else {
                try {
                    parseEnvironmentList(json)
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

    suspend fun addEnvironment(env: RootfsEnvironment) {
        context.rootfsDataStore.edit { prefs ->
            val current = parseEnvironmentsFromPrefs(prefs)
            prefs[environmentsKey] = serializeEnvironmentList(current + env)
        }
    }

    suspend fun removeEnvironment(id: String) {
        context.rootfsDataStore.edit { prefs ->
            val current = parseEnvironmentsFromPrefs(prefs)
            prefs[environmentsKey] = serializeEnvironmentList(current.filter { it.id != id })
        }
    }

    suspend fun updateEnvironment(id: String, transform: (RootfsEnvironment) -> RootfsEnvironment) {
        context.rootfsDataStore.edit { prefs ->
            val current = parseEnvironmentsFromPrefs(prefs)
            prefs[environmentsKey] = serializeEnvironmentList(
                current.map { if (it.id == id) transform(it) else it },
            )
        }
    }

    fun getAutoDeleteEnabled(): Flow<Boolean> =
        context.rootfsDataStore.data.map { prefs ->
            prefs[autoDeleteKey] ?: true
        }

    suspend fun setAutoDeleteEnabled(enabled: Boolean) {
        context.rootfsDataStore.edit { prefs ->
            prefs[autoDeleteKey] = enabled
        }
    }

    // ── JSON serialization using org.json ──

    private fun parseEnvironmentsFromPrefs(prefs: Preferences): List<RootfsEnvironment> {
        val json = prefs[environmentsKey]
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            parseEnvironmentList(json)
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        internal fun parseEnvironmentList(json: String): List<RootfsEnvironment> {
            val array = JSONArray(json)
            return (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                RootfsEnvironment(
                    id = obj.getString("id"),
                    name = obj.optString("name", obj.getString("id")),
                    rootfsPath = obj.getString("rootfsPath"),
                    distro = obj.optString("distro", ""),
                    createdAt = obj.getLong("createdAt"),
                    sizeBytes = obj.optLong("sizeBytes", 0),
                    lastUsedAt = if (obj.has("lastUsedAt") && !obj.isNull("lastUsedAt")) {
                        obj.getLong("lastUsedAt")
                    } else {
                        null
                    },
                    status = try {
                        RootfsStatus.valueOf(obj.optString("status", "READY"))
                    } catch (_: Exception) {
                        RootfsStatus.READY
                    },
                )
            }
        }

        internal fun serializeEnvironmentList(envs: List<RootfsEnvironment>): String {
            val array = JSONArray()
            for (env in envs) {
                val obj = JSONObject().apply {
                    put("id", env.id)
                    put("name", env.name)
                    put("rootfsPath", env.rootfsPath)
                    put("distro", env.distro)
                    put("createdAt", env.createdAt)
                    put("sizeBytes", env.sizeBytes)
                    put("lastUsedAt", env.lastUsedAt ?: JSONObject.NULL)
                    put("status", env.status.name)
                }
                array.put(obj)
            }
            return array.toString()
        }
    }
}
