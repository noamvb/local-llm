package com.noamv.localllm.client

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/** A package identity as authenticated by Android's signing-certificate APIs. */
internal data class SigningIdentity(
    val packageName: String,
    val currentSignerDigests: Set<String>,
    val signingLineageDigests: Set<String>,
)

/**
 * Strict trust policy shared by resolution-time and connection-time checks.
 *
 * LocalLLM currently has one signer. A future key is accepted only when Android reports
 * it in the cryptographically authenticated signing lineage that contains a pinned
 * certificate. Multi-signer packages are rejected because Android cannot rotate keys for
 * them and accepting one approved signer alongside an unapproved signer widens trust.
 */
internal object SigningIdentityPolicy {
    fun isTrusted(
        identity: SigningIdentity,
        expectedPackage: String,
        approvedLineageDigests: Set<String>,
    ): Boolean {
        if (identity.packageName != expectedPackage) return false
        if (identity.currentSignerDigests.size != 1) return false
        if (identity.signingLineageDigests.isEmpty()) return false
        if (!identity.signingLineageDigests.containsAll(identity.currentSignerDigests)) return false
        return identity.signingLineageDigests.any(approvedLineageDigests::contains)
    }
}

/** Service metadata checked without relying on intent-filter resolution order. */
internal data class ServiceIdentity(
    val packageName: String,
    val className: String,
    val exported: Boolean,
    val serviceEnabled: Boolean,
    val applicationEnabled: Boolean,
    val permission: String?,
)

internal object ServiceIdentityPolicy {
    fun isTrusted(identity: ServiceIdentity): Boolean =
        identity.packageName == LocalLlmClient.LOCALLLM_PACKAGE &&
            identity.className == LocalLlmClient.INFERENCE_SERVICE_CLASS &&
            identity.exported &&
            identity.serviceEnabled &&
            identity.applicationEnabled &&
            identity.permission == LocalLlmClient.INFERENCE_PERMISSION
}

/** Resolves and authenticates the one concrete LocalLLM service component. */
internal class TrustedServiceResolver(
    context: Context,
    private val packageManager: PackageManager = context.packageManager,
) {
    private val expectedComponent = ComponentName(
        LocalLlmClient.LOCALLLM_PACKAGE,
        LocalLlmClient.INFERENCE_SERVICE_CLASS,
    )

    @Suppress("DEPRECATION")
    fun resolve(): ComponentName {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            throw LocalLlmClient.Unavailable("LocalLLM requires Android 12 or newer")
        }
        val serviceInfo = try {
            packageManager.getServiceInfo(expectedComponent, 0)
        } catch (error: PackageManager.NameNotFoundException) {
            throw LocalLlmClient.Unavailable("The LocalLLM inference service is not installed", error)
        }

        val serviceIdentity = ServiceIdentity(
            packageName = serviceInfo.packageName,
            className = serviceInfo.name.orEmpty(),
            exported = serviceInfo.exported,
            serviceEnabled = serviceInfo.enabled,
            applicationEnabled = serviceInfo.applicationInfo?.enabled == true,
            permission = serviceInfo.permission,
        )
        if (!ServiceIdentityPolicy.isTrusted(serviceIdentity)) {
            throw LocalLlmClient.Unavailable("The resolved LocalLLM service is not trusted")
        }

        val identity = packageManager.signingIdentity(serviceInfo.packageName)
        if (!SigningIdentityPolicy.isTrusted(
                identity = identity,
                expectedPackage = LocalLlmClient.LOCALLLM_PACKAGE,
                approvedLineageDigests = LocalLlmClient.APPROVED_LOCALLLM_SIGNING_LINEAGE,
            )
        ) {
            throw LocalLlmClient.Unavailable("The installed LocalLLM signature is not trusted")
        }
        return expectedComponent
    }

    /** Re-resolve after connection to close the package-replacement race. */
    fun verifyConnected(component: ComponentName?): ComponentName {
        val expected = resolve()
        if (component == null || component != expected) {
            throw LocalLlmClient.Unavailable("The connected LocalLLM component changed")
        }
        return expected
    }
}

// Every call is dominated by TrustedServiceResolver's API-31 fail-closed guard. Keeping
// the guard at the public resolution boundary avoids advertising a callable lower-API
// path while still compiling this vendored source at the oldest consumer minSdk.
@SuppressLint("NewApi")
@Suppress("DEPRECATION")
private fun PackageManager.signingIdentity(packageName: String): SigningIdentity {
    val packageInfo = try {
        getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    } catch (error: PackageManager.NameNotFoundException) {
        throw LocalLlmClient.Unavailable("The LocalLLM package disappeared", error)
    }
    val signingInfo = packageInfo.signingInfo
        ?: throw LocalLlmClient.Unavailable("LocalLLM has no signing information")
    val current = signingInfo.apkContentsSigners.orEmpty().mapTo(mutableSetOf(), Signature::sha256)
    val lineage = if (signingInfo.hasMultipleSigners()) {
        current
    } else {
        signingInfo.signingCertificateHistory.orEmpty().mapTo(mutableSetOf(), Signature::sha256)
    }
    return SigningIdentity(packageName, current, lineage)
}

private fun Signature.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
