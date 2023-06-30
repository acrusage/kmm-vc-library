package at.asitplus.wallet.lib.oidc

import at.asitplus.KmmResult
import at.asitplus.wallet.lib.agent.CryptoService
import at.asitplus.wallet.lib.agent.Holder
import at.asitplus.wallet.lib.data.dif.ClaimFormatEnum
import at.asitplus.wallet.lib.data.dif.PresentationSubmission
import at.asitplus.wallet.lib.data.dif.PresentationSubmissionDescriptor
import at.asitplus.wallet.lib.jws.DefaultJwsService
import at.asitplus.wallet.lib.jws.DefaultVerifierJwsService
import at.asitplus.wallet.lib.jws.JsonWebKey
import at.asitplus.wallet.lib.jws.JwsAlgorithm
import at.asitplus.wallet.lib.jws.JwsHeader
import at.asitplus.wallet.lib.jws.JwsService
import at.asitplus.wallet.lib.jws.JwsSigned
import at.asitplus.wallet.lib.jws.VerifierJwsService
import at.asitplus.wallet.lib.oidc.OpenIdConstants.Errors
import at.asitplus.wallet.lib.oidc.OpenIdConstants.ID_TOKEN
import at.asitplus.wallet.lib.oidc.OpenIdConstants.PREFIX_DID_KEY
import at.asitplus.wallet.lib.oidc.OpenIdConstants.ResponseModes.DIRECT_POST
import at.asitplus.wallet.lib.oidc.OpenIdConstants.ResponseModes.POST
import at.asitplus.wallet.lib.oidc.OpenIdConstants.SCOPE_OPENID
import at.asitplus.wallet.lib.oidc.OpenIdConstants.SCOPE_PROFILE
import at.asitplus.wallet.lib.oidc.OpenIdConstants.URN_TYPE_JWK_THUMBPRINT
import at.asitplus.wallet.lib.oidc.OpenIdConstants.VP_TOKEN
import at.asitplus.wallet.lib.oidvci.IssuerMetadata
import at.asitplus.wallet.lib.oidvci.OAuth2Exception
import at.asitplus.wallet.lib.oidvci.decodeFromUrlQuery
import at.asitplus.wallet.lib.oidvci.encodeToParameters
import at.asitplus.wallet.lib.oidvci.formUrlEncode
import com.benasher44.uuid.uuid4
import io.github.aakira.napier.Napier
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.util.flattenEntries
import kotlinx.datetime.Clock
import kotlinx.serialization.decodeFromString
import kotlin.time.Duration.Companion.seconds


/**
 * Combines Verifiable Presentations with OpenId Connect.
 * Implements [OIDC for VP](https://openid.net/specs/openid-connect-4-verifiable-presentations-1_0.html) (2023-04-21)
 * as well as [SIOP V2](https://openid.net/specs/openid-connect-self-issued-v2-1_0.html) (2023-01-01).
 *
 * The [holder] creates the Authentication Response, see [OidcSiopVerifier] for the verifier.
 */
class OidcSiopWallet(
    private val holder: Holder,
    private val agentPublicKey: JsonWebKey,
    private val jwsService: JwsService,
    private val verifierJwsService: VerifierJwsService = DefaultVerifierJwsService(),
    private val clock: Clock = Clock.System,
    private val clientId: String = "https://wallet.a-sit.at/"
) {

    companion object {
        fun newInstance(
            holder: Holder,
            cryptoService: CryptoService,
            jwsService: JwsService = DefaultJwsService(cryptoService),
            verifierJwsService: VerifierJwsService = DefaultVerifierJwsService(),
            clock: Clock = Clock.System,
            clientId: String = "https://wallet.a-sit.at/"
        ) = OidcSiopWallet(
            holder = holder,
            agentPublicKey = cryptoService.toJsonWebKey(),
            jwsService = jwsService,
            verifierJwsService = verifierJwsService,
            clock = clock,
            clientId = clientId,
        )
    }

    /**
     * Possible outcomes of creating the OIDC Authentication Response
     */
    sealed class AuthenticationResponseResult {
        /**
         * Wallet returns the [AuthenticationResponseParameters] as form encoded parameters, which shall be posted to
         * `redirect_uri` of the Relying Party, i.e. clients should execute that POST to call the RP again.
         */
        data class Post(val url: String, val content: String) : AuthenticationResponseResult()

        /**
         * Wallet returns the [AuthenticationResponseParameters] as fragment parameters appended to the
         * `redirect_uri` of the Relying Party, i.e. clients should simply open the URL to call the RP again.
         */
        data class Redirect(val url: String) : AuthenticationResponseResult()
    }

    val metadata: IssuerMetadata by lazy {
        IssuerMetadata(
            issuer = clientId,
            authorizationEndpointUrl = clientId,
            responseTypesSupported = arrayOf(ID_TOKEN),
            scopesSupported = arrayOf(SCOPE_OPENID),
            subjectTypesSupported = arrayOf("pairwise", "public"),
            idTokenSigningAlgorithmsSupported = arrayOf(JwsAlgorithm.ES256.text),
            requestObjectSigningAlgorithmsSupported = arrayOf(JwsAlgorithm.ES256.text),
            subjectSyntaxTypesSupported = arrayOf(URN_TYPE_JWK_THUMBPRINT, PREFIX_DID_KEY),
            idTokenTypesSupported = arrayOf(IdTokenType.SUBJECT_SIGNED),
            presentationDefinitionUriSupported = false,
        )
    }

    /**
     * Pass in the URL sent by the Verifier (containing the [AuthenticationRequestParameters] as query parameters),
     * to create [AuthenticationResponseParameters] that can be sent back to the Verifier, see
     * [AuthenticationResponseResult].
     */
    suspend fun createAuthnResponse(it: String): KmmResult<AuthenticationResponseResult> {
        val params = kotlin.runCatching {
            Url(it).parameters.flattenEntries().toMap().decodeFromUrlQuery<AuthenticationRequestParameters>()
        }.getOrNull()
            ?: return KmmResult.failure<AuthenticationResponseResult>(OAuth2Exception(Errors.INVALID_REQUEST))
                .also { Napier.w("Could not parse authentication request") }
        return extractRequestObject(params)
            ?.let { createAuthnResponse(it) }
            ?: createAuthnResponse(params)
    }

    private fun extractRequestObject(params: AuthenticationRequestParameters): AuthenticationRequestParameters? {
        params.request?.let { requestObject ->
            JwsSigned.parse(requestObject)?.let { jws ->
                if (verifierJwsService.verifyJwsObject(jws, requestObject)) {
                    return kotlin.runCatching {
                        jsonSerializer.decodeFromString<AuthenticationRequestParameters>(jws.payload.decodeToString())
                    }.getOrNull()
                }
            }
        }
        return null
    }

    /**
     * Pass in the deserialized [AuthenticationRequestParameters], which were either encoded as query params,
     * or JSON serialized as a JWT Request Object.
     */
    suspend fun createAuthnResponse(
        request: AuthenticationRequestParameters
    ): KmmResult<AuthenticationResponseResult> = createAuthnResponseParams(request).fold(
        {
            if (request.responseType == null) {
                return KmmResult.failure(OAuth2Exception(Errors.INVALID_REQUEST))
            }
            if (!request.responseType.contains(ID_TOKEN) && !request.responseType.contains(VP_TOKEN)) {
                return KmmResult.failure(OAuth2Exception(Errors.INVALID_REQUEST))
            }
            if (request.responseMode?.contains(POST) == true) {
                if (request.redirectUrl == null)
                    return KmmResult.failure(OAuth2Exception(Errors.INVALID_REQUEST))
                val body = it.encodeToParameters().formUrlEncode()
                KmmResult.success(AuthenticationResponseResult.Post(request.redirectUrl, body))
            } else if (request.responseMode?.contains(DIRECT_POST) == true) {
                if (request.responseUrl == null || request.redirectUrl != null)
                    return KmmResult.failure(OAuth2Exception(Errors.INVALID_REQUEST))
                val body = it.encodeToParameters().formUrlEncode()
                KmmResult.success(AuthenticationResponseResult.Post(request.responseUrl, body))
            } else {
                // default for vp_token and id_token is fragment
                if (request.redirectUrl == null)
                    return KmmResult.failure(OAuth2Exception(Errors.INVALID_REQUEST))
                val url = URLBuilder(request.redirectUrl)
                    .apply { encodedFragment = it.encodeToParameters().formUrlEncode() }
                    .buildString()
                KmmResult.success(AuthenticationResponseResult.Redirect(url))
            }
        }, {
            return KmmResult.failure(it)
        }
    )

    /**
     * Creates the authentication response from the RP's [params]
     */
    suspend fun createAuthnResponseParams(
        params: AuthenticationRequestParameters
    ): KmmResult<AuthenticationResponseParameters> {
        val relyingPartyState = params.state
            ?: return KmmResult.failure<AuthenticationResponseParameters>(OAuth2Exception(Errors.INVALID_REQUEST))
                .also { Napier.w("state is null") }
        val audience = params.clientMetadata?.jsonWebKeySet?.keys?.get(0)?.identifier
            ?: return KmmResult.failure<AuthenticationResponseParameters>(OAuth2Exception(Errors.INVALID_REQUEST))
                .also { Napier.w("Could not parse audience") }
        if (URN_TYPE_JWK_THUMBPRINT !in params.clientMetadata.subjectSyntaxTypesSupported)
            return KmmResult.failure<AuthenticationResponseParameters>(OAuth2Exception(Errors.SUBJECT_SYNTAX_TYPES_NOT_SUPPORTED))
                .also { Napier.w("Incompatible subject syntax types algorithms") }
        if (params.clientId != params.redirectUrl)
            return KmmResult.failure<AuthenticationResponseParameters>(OAuth2Exception(Errors.INVALID_REQUEST))
                .also { Napier.w("client_id does not match redirect_uri") }
        if (params.responseType?.contains(ID_TOKEN) != true)
            return KmmResult.failure<AuthenticationResponseParameters>(OAuth2Exception(Errors.INVALID_REQUEST))
                .also { Napier.w("response_type is not \"$ID_TOKEN\"") }
        if (!params.responseType.contains(VP_TOKEN) && params.presentationDefinition == null)
            return KmmResult.failure<AuthenticationResponseParameters>(OAuth2Exception(Errors.INVALID_REQUEST))
                .also { Napier.w("vp_token not requested") }
        // TODO Client shall send the client_id_scheme, which needs to be supported by the Wallet
        if (params.clientMetadata.vpFormats == null)
            return KmmResult.failure<AuthenticationResponseParameters>(OAuth2Exception(Errors.REGISTRATION_VALUE_NOT_SUPPORTED))
                .also { Napier.w("Incompatible subject syntax types algorithms") }
        if (params.clientMetadata.vpFormats.jwtVp?.algorithms?.contains(JwsAlgorithm.ES256.text) != true)
            return KmmResult.failure<AuthenticationResponseParameters>(OAuth2Exception(Errors.REGISTRATION_VALUE_NOT_SUPPORTED))
                .also { Napier.w("Incompatible JWT algorithms") }
        if (params.nonce == null)
            return KmmResult.failure<AuthenticationResponseParameters>(OAuth2Exception(Errors.INVALID_REQUEST))
                .also { Napier.w("nonce is null") }

        val attributeTypes = params.scope?.split(" ")
            ?.filterNot { it == SCOPE_OPENID }?.filterNot { it == SCOPE_PROFILE }
            ?.toList()?.ifEmpty { null }
        val vp = holder.createPresentation(params.nonce, audience, attributeTypes)
            ?: return KmmResult.failure<AuthenticationResponseParameters>(OAuth2Exception(Errors.USER_CANCELLED))
                .also { Napier.w("Could not create presentation") }
        if (vp !is Holder.CreatePresentationResult.Signed)
            return KmmResult.failure<AuthenticationResponseParameters>(OAuth2Exception(Errors.USER_CANCELLED))
                .also { Napier.w("Could not create presentation") }
        val now = clock.now()
        // we'll assume jwk-thumbprint
        val idToken = IdToken(
            issuer = agentPublicKey.jwkThumbprint,
            subject = agentPublicKey.jwkThumbprint,
            subjectJwk = agentPublicKey,
            audience = params.redirectUrl,
            issuedAt = now,
            expiration = now + 60.seconds,
            nonce = params.nonce,
        )
        val jwsPayload = idToken.serialize().encodeToByteArray()
        val jwsHeader = JwsHeader(JwsAlgorithm.ES256)
        val signedIdToken = jwsService.createSignedJwsAddingParams(jwsHeader, jwsPayload)
            ?: return KmmResult.failure<AuthenticationResponseParameters>(OAuth2Exception(Errors.USER_CANCELLED))
                .also { Napier.w("Could not sign id_token") }
        val presentationSubmission = PresentationSubmission(
            id = uuid4().toString(),
            definitionId = params.presentationDefinition?.id ?: uuid4().toString(),
            descriptorMap = params.presentationDefinition?.inputDescriptors?.map {
                PresentationSubmissionDescriptor(
                    id = it.id,
                    format = ClaimFormatEnum.JWT_VP,
                    path = "$",
                    nestedPath = PresentationSubmissionDescriptor(
                        id = uuid4().toString(),
                        format = ClaimFormatEnum.JWT_VC,
                        path = "$.verifiableCredential[0]"
                    ),
                )
            }?.toTypedArray()
        )
        return KmmResult.success(
            AuthenticationResponseParameters(
                idToken = signedIdToken,
                state = relyingPartyState,
                vpToken = vp.jws,
                presentationSubmission = presentationSubmission,
            )
        )
    }


}
