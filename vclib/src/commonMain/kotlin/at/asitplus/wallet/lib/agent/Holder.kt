package at.asitplus.wallet.lib.agent

import at.asitplus.wallet.lib.data.VerifiableCredentialJws
import at.asitplus.wallet.lib.data.VerifiablePresentation

/**
 * Summarizes operations for a Holder in the sense of the [W3C VC Data Model](https://w3c.github.io/vc-data-model/).
 *
 * It can store Verifiable Credentials, and create a Verifiable Presentation out of the stored credentials
 */
interface Holder {

    /**
     * The identifier for this agent, typically the `keyId` from the cryptographic key,
     * e.g. `did:key:mAB...` or `urn:ietf:params:oauth:jwk-thumbprint:sha256:...`
     */
    val identifier: String

    /**
     * Sets the revocation list ot use for further processing of Verifiable Credentials
     *
     * @return `true` if the revocation list has been validated and set, `false` otherwise
     */
    fun setRevocationList(it: String): Boolean

    data class StoreCredentialInput(
        val vcJws: String,
        val attachments: List<Issuer.Attachment>? = null,
    )

    /**
     * Stores all verifiable credentials from [credentialList] that parse and validate,
     * and returns them for future reference.
     *
     * Note: Revocation credentials should not be stored, but set with [setRevocationList].
     */
    suspend fun storeCredentials(credentialList: List<StoreCredentialInput>): StoredCredentialsResult

    data class StoredCredentialsResult(
        val accepted: List<VerifiableCredentialJws> = listOf(),
        val rejected: List<String> = listOf(),
        val notVerified: List<String> = listOf(),
        val attachments: List<StoredAttachmentResult> = listOf(),
    )

    data class StoredAttachmentResult(val name: String, val data: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as StoredAttachmentResult

            if (name != other.name) return false
            if (!data.contentEquals(other.data)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    data class ValidatedVerifiableCredentialJws(val serialized: String, val vc: VerifiableCredentialJws)

    /**
     * Stores all verifiable credentials from [credentialList].
     * _Does not validate the credentials!_
     */
    suspend fun storeValidatedCredentials(credentialList: List<ValidatedVerifiableCredentialJws>): Boolean

    /**
     * Gets a list of all stored credentials, with a revocation status.
     *
     * Note that the revocation status may be [Validator.RevocationStatus.UNKNOWN] if no revocation list
     * has been set with [setRevocationList]
     */
    suspend fun getCredentials(
        attributeTypes: Collection<String>? = null,
    ): Collection<StoredCredential>?

    data class StoredCredential(
        val vcSerialized: String,
        val vc: VerifiableCredentialJws,
        val status: Validator.RevocationStatus
    )

    /**
     * Creates a [VerifiablePresentation] serialized as a JWT for all the credentials we have stored,
     * that match the [attributeTypes] (if specified).
     *
     * May return null if no valid credentials (i.e. non-revoked, matching attribute name) are available.
     */
    suspend fun createPresentation(
        challenge: String,
        audienceId: String,
        attributeTypes: Collection<String>? = null,
    ): CreatePresentationResult?

    /**
     * Creates a [VerifiablePresentation] with the given [validCredentials].
     *
     * Note: The caller is responsible that only valid credentials are passed to this function!
     */
    suspend fun createPresentation(
        validCredentials: List<String>,
        challenge: String,
        audienceId: String,
    ): CreatePresentationResult?

    sealed class CreatePresentationResult {
        /**
         * [jws] contains a valid, serialized, Verifiable Presentation that can be parsed by [Verifier.verifyPresentation]
         */
        data class Signed(val jws: String) : CreatePresentationResult()
    }

}
