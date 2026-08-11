package tr.com.uslanozan.evritext.lounge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What has to survive a restart so the 12-digit TV code is typed exactly once.
 *
 * Field names match what Phase 0's `lounge_auth.json` writes, so a pairing made on
 * the laptop can be pushed straight onto the device — which is how the Kotlin port
 * gets tested before the pairing screen exists.
 */
@Serializable
data class AuthState(
    @SerialName("screenId") val screenId: String? = null,
    @SerialName("loungeIdToken") val loungeIdToken: String? = null,
    @SerialName("refreshToken") val refreshToken: String? = null,
) {
    val paired: Boolean get() = screenId != null
    val linked: Boolean get() = paired && loungeIdToken != null

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * Accepts both key styles. pyytlounge 3.3.0's own `store_auth_state()` emits
         * the pre-versioning names (`lounge_id_token`) while its loader reads the
         * versioned ones — Phase 0 tripped over exactly this, so read either.
         */
        fun decode(text: String): AuthState {
            val root = JSON.parseToJsonElement(text)
            val obj = root as? kotlinx.serialization.json.JsonObject ?: return AuthState()
            fun str(vararg keys: String): String? {
                for (key in keys) {
                    val element = obj[key] ?: continue
                    val primitive = element as? kotlinx.serialization.json.JsonPrimitive ?: continue
                    if (primitive.isString) return primitive.content
                }
                return null
            }
            return AuthState(
                screenId = str("screenId", "screen_id"),
                loungeIdToken = str("loungeIdToken", "lounge_id_token"),
                refreshToken = str("refreshToken", "refresh_token"),
            )
        }
    }
}
