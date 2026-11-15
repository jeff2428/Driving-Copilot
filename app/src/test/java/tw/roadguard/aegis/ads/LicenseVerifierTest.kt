package tw.roadguard.aegis.ads

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.LocalDate
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class LicenseVerifierTest {
    private val device = "RG-0102-0304-0506-0708"
    private val today = LocalDate.of(2026, 11, 15)
    private val keys = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)

    private fun issue(expiry: String): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keys.private)
        signer.update("$device|$expiry".toByteArray())
        return "PRO2.$device.$expiry." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())
    }

    @Test fun expiryIncludesTheLastDayAndLifetime() {
        for (expiry in listOf("20261115", "99991231")) {
            assertEquals(expiry, LicenseVerifier.verifiedExpiry(issue(expiry), device, publicKey, today))
        }
        assertNull(LicenseVerifier.verifiedExpiry(issue("20261114"), device, publicKey, today))
    }

    @Test fun invalidTokensNeverUnlockPro() {
        val valid = issue("99991231")
        for (token in listOf("", "垃圾", "PRO-99991231-00000000", valid.dropLast(10),
            valid.replace("99991231", "99981231"), valid.replace("0102", "0103"))) {
            assertNull(LicenseVerifier.verifiedExpiry(token, device, publicKey, today))
        }
    }
}
