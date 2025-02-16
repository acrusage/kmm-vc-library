package at.asitplus.wallet.lib.agent

import at.asitplus.wallet.lib.data.ConstantIndex
import com.benasher44.uuid.uuid4
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class AgentTest : FreeSpec({

    lateinit var issuer: Issuer
    lateinit var holder: Holder
    lateinit var verifier: Verifier
    lateinit var issuerCredentialStore: IssuerCredentialStore
    lateinit var holderCredentialStore: SubjectCredentialStore
    lateinit var challenge: String

    beforeEach {
        issuerCredentialStore = InMemoryIssuerCredentialStore()
        holderCredentialStore = InMemorySubjectCredentialStore()
        issuer = IssuerAgent.newDefaultInstance(
            issuerCredentialStore = issuerCredentialStore,
            dataProvider = DummyCredentialDataProvider(),
        )
        holder = HolderAgent.newDefaultInstance(
            subjectCredentialStore = holderCredentialStore
        )
        verifier = VerifierAgent.newRandomInstance()
        challenge = uuid4().toString()
    }

    "simple walk-through success" {
        val vcList =
            issuer.issueCredentialWithTypes(holder.identifier, listOf(ConstantIndex.AtomicAttribute2023.vcType))
        if (vcList.failed.isNotEmpty()) fail("no issued credentials")
        holder.storeCredentials(vcList.toStoreCredentialInput())

        val vp = holder.createPresentation(challenge, verifier.identifier)
        vp.shouldNotBeNull()
        vp.shouldBeInstanceOf<Holder.CreatePresentationResult.Signed>()
        val verified = verifier.verifyPresentation(vp.jws, challenge)
        verified.shouldBeInstanceOf<Verifier.VerifyPresentationResult.Success>()
    }

    "simple walk-through success with attachments" {
        // DummyCredentialProvider issues an attachment for "picture"
        val vcList =
            issuer.issueCredentialWithTypes(holder.identifier, listOf(ConstantIndex.AtomicAttribute2023.vcType))
        vcList.successful.shouldNotBeEmpty()
        holder.storeCredentials(vcList.toStoreCredentialInput())
        holderCredentialStore.getAttachment("picture").getOrThrow().shouldNotBeNull()

        val vp = holder.createPresentation(challenge, verifier.identifier)
        vp.shouldNotBeNull()
        vp.shouldBeInstanceOf<Holder.CreatePresentationResult.Signed>()
        val verified = verifier.verifyPresentation(vp.jws, challenge)
        verified.shouldBeInstanceOf<Verifier.VerifyPresentationResult.Success>()
    }

    "wrong keyId in presentation leads to InvalidStructure" {
        val credentials =
            issuer.issueCredentialWithTypes(holder.identifier, listOf(ConstantIndex.AtomicAttribute2023.vcType))
        if (credentials.failed.isNotEmpty()) fail("no issued credentials")
        holder.storeCredentials(credentials.toStoreCredentialInput())

        val vp = holder.createPresentation(challenge, issuer.identifier)
        vp.shouldNotBeNull()
        vp.shouldBeInstanceOf<Holder.CreatePresentationResult.Signed>()
        val result = verifier.verifyPresentation(vp.jws, challenge)
        result.shouldBeInstanceOf<Verifier.VerifyPresentationResult.InvalidStructure>()
    }

    "revoked credentials must not be validated" {
        val credentials =
            issuer.issueCredentialWithTypes(verifier.identifier, listOf(ConstantIndex.AtomicAttribute2023.vcType))
        if (credentials.failed.isNotEmpty()) fail("no issued credentials")
        issuer.revokeCredentials(credentials.successful.map { it.vcJws }) shouldBe true

        val revocationListCredential = issuer.issueRevocationListCredential(FixedTimePeriodProvider.timePeriod)
        revocationListCredential.shouldNotBeNull()
        verifier.setRevocationList(revocationListCredential) shouldBe true

        credentials.successful.map { it.vcJws }.forEach {
            verifier.verifyVcJws(it).shouldBeInstanceOf<Verifier.VerifyCredentialResult.Revoked>()
        }
    }

    "building presentation with revoked credentials should not work" - {

        "when setting a revocation list before storing credentials" {
            val credentials =
                issuer.issueCredentialWithTypes(holder.identifier, listOf(ConstantIndex.AtomicAttribute2023.vcType))
            if (credentials.failed.isNotEmpty()) fail("no issued credentials")
            issuer.revokeCredentials(credentials.successful.map { it.vcJws }) shouldBe true
            val revocationListCredential = issuer.issueRevocationListCredential(FixedTimePeriodProvider.timePeriod)
            revocationListCredential.shouldNotBeNull()
            holder.setRevocationList(revocationListCredential) shouldBe true

            val storedCredentials = holder.storeCredentials(credentials.toStoreCredentialInput())
            storedCredentials.accepted.shouldBeEmpty()
            storedCredentials.rejected shouldHaveSize credentials.successful.size
            storedCredentials.notVerified.shouldBeEmpty()

            holder.createPresentation(challenge, verifier.identifier) shouldBe null
        }

        "and when setting a revocation list after storing credentials" {
            val credentials =
                issuer.issueCredentialWithTypes(holder.identifier, listOf(ConstantIndex.AtomicAttribute2023.vcType))
            if (credentials.failed.isNotEmpty()) fail("no issued credentials")
            val storedCredentials = holder.storeCredentials(credentials.toStoreCredentialInput())
            storedCredentials.accepted shouldHaveSize credentials.successful.size
            storedCredentials.rejected.shouldBeEmpty()
            storedCredentials.notVerified.shouldBeEmpty()

            issuer.revokeCredentials(credentials.successful.map { it.vcJws }) shouldBe true
            val revocationListCredential = issuer.issueRevocationListCredential(FixedTimePeriodProvider.timePeriod)
            revocationListCredential.shouldNotBeNull()
            holder.setRevocationList(revocationListCredential) shouldBe true

            holder.createPresentation(challenge, verifier.identifier) shouldBe null
        }
    }

    "getting credentials that have been stored by the holder" - {

        "when there are no credentials stored" {
            val holderCredentials = holder.getCredentials()
            holderCredentials.shouldNotBeNull()
            holderCredentials.shouldBeEmpty()
        }

        "when they are valid" - {
            val credentials =
                issuer.issueCredentialWithTypes(holder.identifier, listOf(ConstantIndex.AtomicAttribute2023.vcType))
            if (credentials.failed.isNotEmpty()) fail("no issued credentials")
            val storedCredentials = holder.storeCredentials(credentials.toStoreCredentialInput())
            storedCredentials.accepted shouldHaveSize credentials.successful.size
            storedCredentials.rejected.shouldBeEmpty()
            storedCredentials.notVerified.shouldBeEmpty()

            "without a revocation list set" {
                val holderCredentials = holder.getCredentials()
                holderCredentials.shouldNotBeNull()
                holderCredentials.forEach {
                    it.status.shouldBe(Validator.RevocationStatus.UNKNOWN)
                }
            }

            "with a revocation list set" {
                holder.setRevocationList(issuer.issueRevocationListCredential(FixedTimePeriodProvider.timePeriod)!!) shouldBe true
                val holderCredentials = holder.getCredentials()
                holderCredentials.shouldNotBeNull()
                holderCredentials.forEach {
                    it.status.shouldBe(Validator.RevocationStatus.VALID)
                }
            }
        }

        "when the issuer has revoked them" {
            val credentials =
                issuer.issueCredentialWithTypes(holder.identifier, listOf(ConstantIndex.AtomicAttribute2023.vcType))
            if (credentials.failed.isNotEmpty()) fail("no issued credentials")
            val storedCredentials = holder.storeCredentials(credentials.toStoreCredentialInput())
            storedCredentials.accepted shouldHaveSize credentials.successful.size
            storedCredentials.rejected.shouldBeEmpty()
            storedCredentials.notVerified.shouldBeEmpty()

            issuer.revokeCredentials(credentials.successful.map { it.vcJws }) shouldBe true
            val revocationListCredential = issuer.issueRevocationListCredential(FixedTimePeriodProvider.timePeriod)
            revocationListCredential.shouldNotBeNull()
            holder.setRevocationList(revocationListCredential) shouldBe true

            val holderCredentials = holder.getCredentials()
            holderCredentials.shouldNotBeNull()
            holderCredentials.forEach {
                it.status.shouldBe(Validator.RevocationStatus.REVOKED)
            }
        }
    }

    "building presentation without necessary credentials" {
        holder.createPresentation(challenge, verifier.identifier) shouldBe null
    }

    "valid presentation is valid" {
        val credentials =
            issuer.issueCredentialWithTypes(holder.identifier, listOf(ConstantIndex.AtomicAttribute2023.vcType))
        if (credentials.failed.isNotEmpty()) fail("no issued credentials")
        holder.storeCredentials(credentials.toStoreCredentialInput())
        val vp = holder.createPresentation(challenge, verifier.identifier)
        vp.shouldNotBeNull()
        vp.shouldBeInstanceOf<Holder.CreatePresentationResult.Signed>()

        val result = verifier.verifyPresentation(vp.jws, challenge)
        result.shouldBeInstanceOf<Verifier.VerifyPresentationResult.Success>()
        result.vp.revokedVerifiableCredentials.shouldBeEmpty()
        credentials.successful shouldHaveSize result.vp.verifiableCredentials.size
    }

    "valid presentation is valid -- some other attributes revoked" {
        val credentials =
            issuer.issueCredentialWithTypes(holder.identifier, listOf(ConstantIndex.AtomicAttribute2023.vcType))
        if (credentials.failed.isNotEmpty()) fail("no issued credentials")
        holder.storeCredentials(credentials.toStoreCredentialInput())
        val vp = holder.createPresentation(challenge, verifier.identifier)
        vp.shouldNotBeNull()
        vp.shouldBeInstanceOf<Holder.CreatePresentationResult.Signed>()

        val credentialsToRevoke =
            issuer.issueCredentialWithTypes(holder.identifier, listOf(ConstantIndex.AtomicAttribute2023.vcType))
        if (credentialsToRevoke.failed.isNotEmpty()) fail("no issued credentials")
        issuer.revokeCredentials(credentialsToRevoke.successful.map { it.vcJws }) shouldBe true
        val revocationList = issuer.issueRevocationListCredential(FixedTimePeriodProvider.timePeriod)
        revocationList.shouldNotBeNull()
        verifier.setRevocationList(revocationList) shouldBe true

        val result = verifier.verifyPresentation(vp.jws, challenge)
        result.shouldBeInstanceOf<Verifier.VerifyPresentationResult.Success>()
    }

})
