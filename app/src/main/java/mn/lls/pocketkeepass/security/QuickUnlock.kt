package mn.lls.pocketkeepass.security

import android.content.Context
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.core.content.edit
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What the settings dialog shows for quick unlock. */
enum class QuickUnlockSetting {
    /** The device has no strong biometrics set up (or no hardware for them). */
    Unavailable,
    Off,
    On,
}

/**
 * Biometric quick unlock: the database's composite key hash, encrypted with an Android Keystore
 * key that only works right after a strong biometric check (fingerprint, or face on phones where
 * Android rates it as strong).
 *
 * The hash is what the password and key file turn into before key derivation. Unlike the
 * derived key, it keeps working after the database is saved, by us or any other app, because
 * saving changes the key derivation salt but not the master key. It stops working only when the
 * master key changes, and then the engine just says "wrong key".
 *
 * The Keystore key never leaves secure hardware, and Android deletes it when biometrics are
 * added or removed, or the screen lock is turned off. So someone who adds their own fingerprint
 * to the phone can't use it to open the database. After that, the master password is needed
 * again. Only the encrypted hash and its IV are stored on disk, along with the database's
 * location; app data is excluded from backups.
 *
 * There's one slot: the app remembers one database at a time (`RecentDatabase`), and so does
 * this.
 */
class QuickUnlock(context: Context) {
    private val prefs = context.getSharedPreferences("quick_unlock", Context.MODE_PRIVATE)

    // Kept apart from [prefs], which [disable] clears: turning quick unlock off isn't a reason to ask again.
    private val offers = context.getSharedPreferences("quick_unlock_offer", Context.MODE_PRIVATE)
    private val biometrics = BiometricManager.from(context)

    /**
     * Whether to ask, right after the user unlocked [database] with the master password, if
     * they'd like to use biometrics next time: they can, it's off, and they haven't been asked
     * about this database yet.
     */
    fun shouldOffer(database: Uri): Boolean =
        setting(database) == QuickUnlockSetting.Off && offers.getString(OFFERED, null) != database.toString()

    /** Remembers that the user answered [shouldOffer]'s question for [database], whatever they chose. */
    fun offered(database: Uri) = offers.edit { putString(OFFERED, database.toString()) }

    /** Whether strong biometrics are set up on this device right now. */
    val isAvailable: Boolean
        get() = biometrics.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** Whether quick unlock is turned on for [database]. It may still turn out to be invalidated. */
    fun isEnabledFor(database: Uri): Boolean = prefs.getString(DATABASE, null) == database.toString()

    fun setting(database: Uri): QuickUnlockSetting = when {
        !isAvailable -> QuickUnlockSetting.Unavailable
        isEnabledFor(database) -> QuickUnlockSetting.On
        else -> QuickUnlockSetting.Off
    }

    /**
     * The first step of turning quick unlock on: makes a fresh Keystore key (replacing any old
     * one) and a cipher that can encrypt with it once the user passes the biometric prompt.
     * Then pass the cipher from the prompt's result to [store].
     */
    @Throws(GeneralSecurityException::class)
    fun encryptingCipher(): Cipher {
        disable()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, createKey()) }
    }

    /** Encrypts [keyHash] with an authenticated cipher from [encryptingCipher] and remembers it for [database]. */
    @Throws(GeneralSecurityException::class)
    fun store(database: Uri, cipher: Cipher, keyHash: ByteArray) {
        val sealed = cipher.doFinal(keyHash)
        prefs.edit {
            putString(DATABASE, database.toString())
            putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putString(SEALED, Base64.encodeToString(sealed, Base64.NO_WRAP))
        }
    }

    /**
     * A cipher that can decrypt [database]'s stored hash once the user passes the biometric
     * prompt, or null if quick unlock no longer works: Android deleted the key because
     * biometrics or the screen lock changed. In that case quick unlock is turned off.
     */
    fun decryptingCipher(database: Uri): Cipher? {
        if (!isEnabledFor(database)) return null
        return try {
            val iv = Base64.decode(prefs.getString(IV, null) ?: throw GeneralSecurityException("no IV"), Base64.NO_WRAP)
            val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: throw GeneralSecurityException("no key")
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv)) }
        } catch (_: GeneralSecurityException) {
            // Usually KeyPermanentlyInvalidatedException.
            disable()
            null
        } catch (_: IllegalArgumentException) {
            disable() // the stored IV isn't valid base64
            null
        }
    }

    /**
     * Decrypts the stored hash with an authenticated cipher from [decryptingCipher]. The caller
     * wipes the result. Throws if the stored data was damaged.
     */
    @Throws(GeneralSecurityException::class)
    fun keyHash(cipher: Cipher): ByteArray {
        val sealed = prefs.getString(SEALED, null) ?: throw GeneralSecurityException("quick unlock was turned off")
        return try {
            cipher.doFinal(Base64.decode(sealed, Base64.NO_WRAP))
        } catch (e: IllegalArgumentException) {
            throw GeneralSecurityException(e)
        }
    }

    /** Turns quick unlock off: forgets the encrypted hash and deletes the Keystore key. */
    fun disable() {
        prefs.edit { clear() }
        try {
            keyStore().deleteEntry(KEY_ALIAS)
        } catch (_: GeneralSecurityException) {
            // Nothing to delete, or the Keystore is unavailable. The hash is gone either way.
        }
    }

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun createKey(strongBox: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P): SecretKey {
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            // Every use needs its own biometric check, made with this cipher (no time window).
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Strong biometrics only: not the device PIN, which isn't what the user turned on.
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true)
            }
            .build()
        return try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
        } catch (e: ProviderException) {
            // StrongBoxUnavailableException (API 28) is a ProviderException. Most phones have no separate security chip. The regular Keystore is still hardware-backed on nearly all of them.
            if (strongBox) createKey(strongBox = false) else throw e
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "quick_unlock"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128

        const val DATABASE = "database"
        const val IV = "iv"
        const val SEALED = "sealed_key_hash"

        const val OFFERED = "database"
    }
}
