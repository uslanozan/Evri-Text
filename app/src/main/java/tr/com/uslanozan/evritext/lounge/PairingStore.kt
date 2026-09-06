package tr.com.uslanozan.evritext.lounge

import android.content.Context
import java.io.File

/** Persists the one-time YouTube TV pairing in the app's private storage. */
class PairingStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    fun load(): AuthState? = runCatching {
        file.takeIf { it.isFile }
            ?.readText()
            ?.let(AuthState::decode)
            ?.takeIf { it.paired }
    }.getOrNull()

    fun save(auth: AuthState): Boolean {
        if (!auth.paired) return false
        return runCatching {
            val temporary = File(file.parentFile, "$FILE_NAME.tmp")
            temporary.writeText(auth.encode())
            if (!temporary.renameTo(file)) {
                file.writeText(auth.encode())
                temporary.delete()
            }
        }.isSuccess
    }

    fun clear(): Boolean = !file.exists() || file.delete()

    companion object {
        const val FILE_NAME = "lounge_auth.json"
    }
}
