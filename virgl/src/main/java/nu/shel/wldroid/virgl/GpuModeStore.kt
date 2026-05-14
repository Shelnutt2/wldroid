package nu.shel.wldroid.virgl

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.gpuModeDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "gpu_mode_prefs")

/**
 * Persists the user's GPU mode override using AndroidX DataStore.
 *
 * A `null` override means "auto-detect" — the [GpuCapabilityDetector]
 * will choose the best mode at runtime.
 */
class GpuModeStore(
    private val context: Context,
) {
    /** Returns the stored GPU mode override, or `null` for auto-detect. */
    fun getGpuModeOverride(): Flow<GpuMode?> =
        context.gpuModeDataStore.data.map { prefs ->
            val value = prefs[GPU_MODE_OVERRIDE_KEY]
            if (value == null || value == "auto") {
                null
            } else {
                try {
                    GpuMode.valueOf(value)
                } catch (_: Exception) {
                    null
                }
            }
        }

    /**
     * Set the GPU mode override.
     *
     * @param mode The mode to persist, or `null` to revert to auto-detect.
     */
    suspend fun setGpuModeOverride(mode: GpuMode?) {
        context.gpuModeDataStore.edit { prefs ->
            prefs[GPU_MODE_OVERRIDE_KEY] = mode?.name ?: "auto"
        }
    }

    companion object {
        private val GPU_MODE_OVERRIDE_KEY = stringPreferencesKey("gpu_mode_override")
    }
}
