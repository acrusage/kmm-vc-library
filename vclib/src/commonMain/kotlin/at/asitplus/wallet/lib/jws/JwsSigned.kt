package at.asitplus.wallet.lib.jws

import io.matthewnelson.component.base64.Base64
import io.matthewnelson.component.base64.decodeBase64ToArray
import io.matthewnelson.component.base64.encodeBase64
import io.github.aakira.napier.Napier

/**
 * Representation of a signed JSON Web Signature object, i.e. consisting of header, payload and signature.
 */
data class JwsSigned(
    val header: JwsHeader,
    val payload: ByteArray,
    val signature: ByteArray,
    val plainSignatureInput: String,
) {
    fun serialize(): String {
        return "${plainSignatureInput}.${signature.encodeBase64(Base64.UrlSafe(pad = false))}"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as JwsSigned

        if (header != other.header) return false
        if (!payload.contentEquals(other.payload)) return false
        if (!signature.contentEquals(other.signature)) return false
        return plainSignatureInput == other.plainSignatureInput
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + plainSignatureInput.hashCode()
        return result
    }

    companion object {
        fun parse(it: String): JwsSigned? {
            val stringList = it.replace("[^A-Za-z0-9-_.]".toRegex(), "").split(".")
            if (stringList.size != 3) return null.also { Napier.w("Could not parse JWS: $it") }
            val headerInput = stringList[0].decodeBase64ToArray()
                ?: return null.also { Napier.w("Could not parse JWS: $it") }
            val header = JwsHeader.deserialize(headerInput.decodeToString())
                ?: return null.also { Napier.w("Could not parse JWS: $it") }
            val payload = stringList[1].decodeBase64ToArray()
                ?: return null.also { Napier.w("Could not parse JWS: $it") }
            val signature = stringList[2].decodeBase64ToArray()
                ?: return null.also { Napier.w("Could not parse JWS: $it") }
            return JwsSigned(header, payload, signature, "${stringList[0]}.${stringList[1]}")
        }
    }
}