package at.asitplus.wallet.lib.oidc

import at.asitplus.wallet.lib.LibraryInitializer
import at.asitplus.wallet.lib.agent.CryptoService
import at.asitplus.wallet.lib.agent.DefaultCryptoService
import at.asitplus.wallet.lib.agent.Holder
import at.asitplus.wallet.lib.agent.HolderAgent
import at.asitplus.wallet.lib.agent.IssuerAgent
import at.asitplus.wallet.lib.agent.Verifier
import at.asitplus.wallet.lib.agent.VerifierAgent
import at.asitplus.wallet.lib.data.ConstantIndex
import at.asitplus.wallet.lib.data.CredentialSubject
import at.asitplus.wallet.lib.oidvci.decodeFromPostBody
import at.asitplus.wallet.lib.oidvci.decodeFromUrlQuery
import at.asitplus.wallet.lib.oidvci.encodeToParameters
import at.asitplus.wallet.lib.oidvci.formUrlEncode
import com.benasher44.uuid.uuid4
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

class OidcSiopProtocolTest : FreeSpec({

    lateinit var relyingPartyUrl: String
    lateinit var walletUrl: String

    lateinit var holderCryptoService: CryptoService
    lateinit var verifierCryptoService: CryptoService

    lateinit var holderAgent: Holder
    lateinit var verifierAgent: Verifier

    lateinit var holderSiop: OidcSiopWallet
    lateinit var verifierSiop: OidcSiopVerifier

    beforeSpec {
        LibraryInitializer.registerExtensionLibrary(LibraryInitializer.ExtensionLibraryInfo(
            credentialScheme = TestCredentialScheme,
            serializersModule = kotlinx.serialization.modules.SerializersModule {
                polymorphic(CredentialSubject::class) {
                    subclass(TestCredential::class)
                }
            }
        ))
    }

    beforeEach {
        holderCryptoService = DefaultCryptoService()
        verifierCryptoService = DefaultCryptoService()
        holderAgent = HolderAgent.newDefaultInstance(holderCryptoService)
        verifierAgent = VerifierAgent.newDefaultInstance(verifierCryptoService.identifier)
        runBlocking {
            holderAgent.storeCredentials(
                IssuerAgent.newDefaultInstance(
                    DefaultCryptoService(),
                    dataProvider = DummyCredentialDataProvider(),
                ).issueCredentialWithTypes(
                    holderAgent.identifier,
                    listOf(ConstantIndex.AtomicAttribute2023.vcType)
                ).toStoreCredentialInput()
            )
        }

        holderSiop = OidcSiopWallet.newInstance(
            holder = holderAgent,
            cryptoService = holderCryptoService
        )
        verifierSiop = OidcSiopVerifier.newInstance(
            verifier = verifierAgent,
            cryptoService = verifierCryptoService
        )

        relyingPartyUrl = "https://example.com/${uuid4()}"
        walletUrl = "https://example.com/${uuid4()}"
    }

    "test with Fragment" {
        val authnRequest = verifierSiop.createAuthnRequestUrl(walletUrl, relyingPartyUrl)
        println(authnRequest)
        
        val authnResponse = holderSiop.createAuthnResponse(authnRequest).getOrThrow()
        authnResponse.shouldBeInstanceOf<OidcSiopWallet.AuthenticationResponseResult.Redirect>()
        println(authnResponse)
        authnResponse.url.shouldNotContain("?")
        authnResponse.url.shouldContain("#")
        authnResponse.url.shouldStartWith(relyingPartyUrl)

        val result = verifierSiop.validateAuthnResponse(authnResponse.url, relyingPartyUrl)
        result.shouldBeInstanceOf<OidcSiopVerifier.AuthnResponseResult.Success>()
        result.vp.verifiableCredentials.shouldNotBeEmpty()

        verifierSiop.validateAuthnResponse(
            (holderSiop.createAuthnResponse(verifierSiop.createAuthnRequestUrl(walletUrl, relyingPartyUrl))
                .getOrThrow() as OidcSiopWallet.AuthenticationResponseResult.Redirect).url,
            relyingPartyUrl
        ).shouldBeInstanceOf<OidcSiopVerifier.AuthnResponseResult.Success>()
    }

    "test with POST" {
        val authnRequest = verifierSiop.createAuthnRequestUrl(
            walletUrl,
            relyingPartyUrl,
            responseMode = OpenIdConstants.ResponseModes.POST
        )
        println(authnRequest)

        val authnResponse = holderSiop.createAuthnResponse(authnRequest).getOrThrow()
        authnResponse.shouldBeInstanceOf<OidcSiopWallet.AuthenticationResponseResult.Post>()
        println(authnResponse)
        authnResponse.url.shouldBe(relyingPartyUrl)

        val result = verifierSiop.validateAuthnResponseFromPost(authnResponse.content, relyingPartyUrl)
        result.shouldBeInstanceOf<OidcSiopVerifier.AuthnResponseResult.Success>()
        result.vp.verifiableCredentials.shouldNotBeEmpty()
    }

    "test with Query" {
        val authnRequest = verifierSiop.createAuthnRequestUrl(
            walletUrl,
            relyingPartyUrl,
            responseMode = OpenIdConstants.ResponseModes.QUERY
        )
        println(authnRequest)

        val authnResponse = holderSiop.createAuthnResponse(authnRequest).getOrThrow()
        authnResponse.shouldBeInstanceOf<OidcSiopWallet.AuthenticationResponseResult.Redirect>()
        println(authnResponse)
        authnResponse.url.shouldContain("?")
        authnResponse.url.shouldNotContain("#")
        authnResponse.url.shouldStartWith(relyingPartyUrl)

        val result = verifierSiop.validateAuthnResponse(authnResponse.url, relyingPartyUrl)
        result.shouldBeInstanceOf<OidcSiopVerifier.AuthnResponseResult.Success>()
        result.vp.verifiableCredentials.shouldNotBeEmpty()
    }

    "test with JAR" {
        val authnRequest = verifierSiop.createAuthnRequestUrlWithRequestObject(walletUrl, relyingPartyUrl)
        println(authnRequest)

        val authnResponse = holderSiop.createAuthnResponse(authnRequest).getOrThrow()
        authnResponse.shouldBeInstanceOf<OidcSiopWallet.AuthenticationResponseResult.Redirect>()
        val result = verifierSiop.validateAuthnResponse(authnResponse.url, relyingPartyUrl)
        result.shouldBeInstanceOf<OidcSiopVerifier.AuthnResponseResult.Success>()
        result.vp.verifiableCredentials.shouldNotBeEmpty()
    }

    "test with deserializing" {
        val authnRequest = verifierSiop.createAuthnRequest(relyingPartyUrl)
        val authnRequestUrlParams = authnRequest.encodeToParameters().formUrlEncode()
        println(authnRequestUrlParams)

        val parsedAuthnRequest: AuthenticationRequestParameters = authnRequestUrlParams.decodeFromUrlQuery()
        val authnResponse = holderSiop.createAuthnResponseParams(parsedAuthnRequest).getOrThrow()
        val authnResponseParams = authnResponse.encodeToParameters().formUrlEncode()
        println(authnResponseParams)

        val parsedAuthnResponse: AuthenticationResponseParameters = authnResponseParams.decodeFromPostBody()
        val result = verifierSiop.validateAuthnResponse(parsedAuthnResponse, relyingPartyUrl)
        result.shouldBeInstanceOf<OidcSiopVerifier.AuthnResponseResult.Success>()
        result.vp.verifiableCredentials.shouldNotBeEmpty()
    }

    "test specific credential" {
        holderAgent.storeCredentials(
            IssuerAgent.newDefaultInstance(
                DefaultCryptoService(),
                dataProvider = TestCredentialDataProvider(),
            ).issueCredentialWithTypes(
                holderAgent.identifier,
                listOf(TestCredentialScheme.vcType)
            ).toStoreCredentialInput()
        )

        val authnRequest = verifierSiop.createAuthnRequestUrl(
            walletUrl,
            relyingPartyUrl,
            credentialScheme = TestCredentialScheme
        )
        println(authnRequest)

        val authnResponse = holderSiop.createAuthnResponse(authnRequest).getOrThrow()
        authnResponse.shouldBeInstanceOf<OidcSiopWallet.AuthenticationResponseResult.Redirect>()
        println(authnResponse)

        val result = verifierSiop.validateAuthnResponse(authnResponse.url, relyingPartyUrl)
        result.shouldBeInstanceOf<OidcSiopVerifier.AuthnResponseResult.Success>()
        result.vp.verifiableCredentials.shouldNotBeEmpty()
        result.vp.verifiableCredentials.forEach {
            it.vc.credentialSubject.shouldBeInstanceOf<TestCredential>()
        }
    }
})