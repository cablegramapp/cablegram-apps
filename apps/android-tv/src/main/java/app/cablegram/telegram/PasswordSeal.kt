package app.cablegram.telegram

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals the Telegram two-step password on the phone so only the TV that asked can read it (contracts/telegram-link.md,
 * "The Telegram two-step password, typed on the phone"). The control plane relays the TV's public key and the sealed
 * bytes and cannot open them.
 *
 * ECDH on P-256; the AES-256-GCM key is SHA-256(shared secret || "cablegram-tg-password-v1:" || request id), and the
 * request id is also the associated data, so a sealed password only opens for the request it was made for.
 * `sealed = base64(phone SPKI || IV || ciphertext || tag)`. The same file is in both apps.
 */
object PasswordSeal {
    private const val SPKI_LENGTH = 91 // DER SubjectPublicKeyInfo of a P-256 key
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH = 16

    /** The TV's one-time key pair for one request; [publicKey] is what the control plane gets. */
    class TvKeys internal constructor(private val pair: KeyPair) {
        val publicKey: String get() = Base64.getEncoder().encodeToString(pair.public.encoded)
        internal val privateKey: PrivateKey get() = pair.private
    }

    fun newTvKeys(): TvKeys = TvKeys(newPair())

    /** Phone: the sealed password for the TV whose [tvPublicKey] (base64 SPKI) was posted with [requestId]. */
    fun seal(tvPublicKey: String, requestId: String, password: String): String {
        val tv = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(tvPublicKey)))
        val ephemeral = newPair()
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(agree(ephemeral.private, tv), requestId), GCMParameterSpec(TAG_LENGTH * 8, iv))
        cipher.updateAAD(requestId.toByteArray())
        val body = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(ephemeral.public.encoded + iv + body)
    }

    /** TV: the password, or null when [sealed] is not for [requestId] and these keys (or was altered). */
    fun open(keys: TvKeys, requestId: String, sealed: String): String? = open(keys.privateKey, requestId, sealed)

    internal fun open(privateKey: PrivateKey, requestId: String, sealed: String): String? = runCatching {
        val raw = Base64.getDecoder().decode(sealed)
        require(raw.size > SPKI_LENGTH + IV_LENGTH + TAG_LENGTH)
        val phone = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(raw.copyOfRange(0, SPKI_LENGTH)))
        val iv = raw.copyOfRange(SPKI_LENGTH, SPKI_LENGTH + IV_LENGTH)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(agree(privateKey, phone), requestId), GCMParameterSpec(TAG_LENGTH * 8, iv))
        cipher.updateAAD(requestId.toByteArray())
        String(cipher.doFinal(raw, SPKI_LENGTH + IV_LENGTH, raw.size - SPKI_LENGTH - IV_LENGTH), Charsets.UTF_8)
    }.getOrNull()

    private fun newPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun agree(privateKey: PrivateKey, publicKey: java.security.PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").apply { init(privateKey); doPhase(publicKey, true) }.generateSecret()

    private fun key(secret: ByteArray, requestId: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(secret)
        digest.update("cablegram-tg-password-v1:$requestId".toByteArray())
        return SecretKeySpec(digest.digest(), "AES")
    }
}
