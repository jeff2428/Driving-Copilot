package tw.roadguard.aegis.ads

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

internal object LicenseVerifier {
    private val devicePattern = Regex("RG(?:-[0-9A-F]{4}){4}")

    fun verifiedExpiry(
        key: String, device: String, publicKey: String,
        today: LocalDate = LocalDate.now(ZoneId.of("Asia/Taipei"))
    ): String? {
        val parts = key.trim().split('.')
        if (parts.size != 4 || !parts[0].equals("PRO2", ignoreCase = true)) return null
        val target = parts[1].uppercase(Locale.US)
        val expiry = parts[2]
        if (!devicePattern.matches(target) || !target.equals(device, ignoreCase = true)) return null
        if (expiry.length != 8 || expiry.any { it !in '0'..'9' }) return null
        return try {
            if (LocalDate.parse(expiry, DateTimeFormatter.BASIC_ISO_DATE).isBefore(today)) return null
            val spec = X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(spec))
            verifier.update("$target|$expiry".toByteArray(Charsets.UTF_8))
            expiry.takeIf { verifier.verify(Base64.getUrlDecoder().decode(parts[3])) }
        } catch (_: Exception) {
            null
        }
    }
}
