package at.asitplus.wallet.lib.agent

import at.asitplus.wallet.lib.data.AtomicAttributeCredential
import at.asitplus.wallet.lib.data.VerifiablePresentationParsed


/**
 * An agent that only implements [Verifier], i.e. it can only verify credentials of other agents.
 */
class VerifierAgent private constructor(
    private val validator: Validator,
    private val localId: String
) : Verifier {

    companion object {
        fun newDefaultInstance(
            keyId: String,
            cryptoService: VerifierCryptoService = DefaultVerifierCryptoService(),
            validator: Validator = Validator.newDefaultInstance(cryptoService),
        ): VerifierAgent = VerifierAgent(
            validator = validator,
            localId = keyId
        )

        /**
         * Explicitly short argument list to use it from Swift
         */
        fun newDefaultInstance(localId: String): VerifierAgent = VerifierAgent(
            validator = Validator.newDefaultInstance(),
            localId = localId
        )
    }

    override fun setRevocationList(it: String): Boolean {
        return validator.setRevocationList(it)
    }

    /**
     * Verifies a presentation of some credentials that a holder issued with that [challenge] we sent before.
     */
    override fun verifyPresentation(it: String, challenge: String): Verifier.VerifyPresentationResult {
        return validator.verifyVpJws(it, challenge, localId)
    }

    /**
     * Verifies if a presentation contains all required [attributeNames].
     */
    override fun verifyPresentationContainsAttributes(
        it: VerifiablePresentationParsed,
        attributeNames: List<String>
    ): Boolean {
        val existingAttributeNames = it.verifiableCredentials
            .map { it.vc.credentialSubject }
            .filterIsInstance<AtomicAttributeCredential>()
            .map { it.name }
        return attributeNames == existingAttributeNames
    }

    override fun verifyVcJws(it: String): Verifier.VerifyCredentialResult {
        return validator.verifyVcJws(it, localId)
    }

}
