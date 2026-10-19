package app.littleclone.clone

import app.littleclone.engine.SigningIdentity
import java.io.File

/**
 * Keeps the one signing identity every clone is signed with, in app-private storage that is
 * excluded from backups. It is created on first use (a few seconds on a mid-range phone).
 */
class SigningStore(private val dir: File) {
    private var cached: SigningIdentity? = null

    @Synchronized
    fun identity(): SigningIdentity {
        cached?.let { return it }
        val keyFile = File(dir, "clone-key.pk8")
        val certFile = File(dir, "clone-cert.der")
        if (keyFile.exists() && certFile.exists()) {
            try {
                return SigningIdentity.decode(keyFile.readBytes(), certFile.readBytes()).also { cached = it }
            } catch (e: Exception) {
                // Unreadable key material: fall through and create a new identity.
            }
        }
        val fresh = SigningIdentity.generate("Little Clone", 30)
        keyFile.writeBytes(fresh.encodePrivateKey())
        certFile.writeBytes(fresh.encodeCertificate())
        cached = fresh
        return fresh
    }
}
