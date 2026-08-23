package com.noamv.localllm.security

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import com.noamv.localllm.R
import java.security.MessageDigest

internal data class ApprovedCaller(
    val packageName: String,
    val approvedLineageDigest: String,
)

internal data class CallerSigningIdentity(
    val packageName: String,
    val currentSignerDigests: Set<String>,
    val signingLineageDigests: Set<String>,
)

/** Pure policy: a Binder UID must resolve only to one approved package and lineage. */
internal object CallerAuthorizationPolicy {
    fun isAuthorized(
        identities: List<CallerSigningIdentity>,
        approvedCallers: Set<ApprovedCaller>,
    ): Boolean {
        // Binder exposes a UID, not the originating package. Reject shared UIDs because an
        // unapproved package sharing an approved app's UID would otherwise inherit access.
        if (identities.size != 1) return false
        val identity = identities.single()
        if (identity.currentSignerDigests.size != 1) return false
        if (identity.signingLineageDigests.isEmpty()) return false
        if (!identity.signingLineageDigests.containsAll(identity.currentSignerDigests)) return false
        return approvedCallers.any { approved ->
            approved.packageName == identity.packageName &&
                approved.approvedLineageDigest in identity.signingLineageDigests
        }
    }
}

/** Re-reads UID/package/signing state for every inbound Binder transaction. */
internal class CallerAuthorizer(context: Context) {
    private val packageManager = context.packageManager
    private val approvedCallers = context.resources
        .getStringArray(R.array.localllm_approved_callers)
        .map(::parseApprovedCaller)
        .toSet()

    fun enforceAuthorized(uid: Int) {
        val packages = packageManager.getPackagesForUid(uid).orEmpty().distinct()
        val identities = packages.mapNotNull { packageName ->
            packageManager.signingIdentityOrNull(packageName)
        }
        if (packages.size != identities.size ||
            !CallerAuthorizationPolicy.isAuthorized(identities, approvedCallers)
        ) {
            throw SecurityException("UID $uid is not an approved LocalLLM client")
        }
    }
}

private fun parseApprovedCaller(encoded: String): ApprovedCaller {
    val pieces = encoded.split('|', limit = 2)
    require(
        pieces.size == 2 &&
            pieces[0].isNotBlank() &&
            pieces[1].length == 64 &&
            pieces[1].all { it.isDigit() || it.lowercaseChar() in 'a'..'f' },
    ) {
        "Malformed LocalLLM approved-caller entry"
    }
    return ApprovedCaller(pieces[0], pieces[1].lowercase())
}

@Suppress("DEPRECATION")
private fun PackageManager.signingIdentityOrNull(packageName: String): CallerSigningIdentity? {
    val packageInfo = try {
        getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    } catch (_: PackageManager.NameNotFoundException) {
        return null
    }
    val signingInfo = packageInfo.signingInfo ?: return null
    val current = signingInfo.apkContentsSigners.orEmpty().mapTo(mutableSetOf(), Signature::sha256)
    val lineage = if (signingInfo.hasMultipleSigners()) {
        current
    } else {
        signingInfo.signingCertificateHistory.orEmpty().mapTo(mutableSetOf(), Signature::sha256)
    }
    return CallerSigningIdentity(packageName, current, lineage)
}

private fun Signature.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
