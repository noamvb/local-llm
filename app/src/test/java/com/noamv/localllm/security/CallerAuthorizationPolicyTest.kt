package com.noamv.localllm.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerAuthorizationPolicyTest {
    private val approved = setOf(
        ApprovedCaller("com.noamv.poopschedule", "poop-old"),
        ApprovedCaller("com.noamv.cannsheet.mobile", "cannsheet"),
    )

    @Test
    fun acceptsExactPackageAndCurrentSigner() {
        assertTrue(
            CallerAuthorizationPolicy.isAuthorized(
                listOf(identity("com.noamv.poopschedule", "poop-old")),
                approved,
            ),
        )
    }

    @Test
    fun acceptsAndroidAuthenticatedSigningRotation() {
        assertTrue(
            CallerAuthorizationPolicy.isAuthorized(
                listOf(
                    CallerSigningIdentity(
                        "com.noamv.poopschedule",
                        currentSignerDigests = setOf("poop-new"),
                        signingLineageDigests = setOf("poop-old", "poop-new"),
                    ),
                ),
                approved,
            ),
        )
    }

    @Test
    fun rejectsApprovedCertificateOnUnapprovedPackage() {
        assertFalse(
            CallerAuthorizationPolicy.isAuthorized(
                listOf(identity("example.copy", "poop-old")),
                approved,
            ),
        )
    }

    @Test
    fun rejectsApprovedPackageWithUnrelatedCertificate() {
        assertFalse(
            CallerAuthorizationPolicy.isAuthorized(
                listOf(identity("com.noamv.poopschedule", "fake")),
                approved,
            ),
        )
    }

    @Test
    fun rejectsSharedUidBecauseBinderCannotIdentifyOriginatingPackage() {
        assertFalse(
            CallerAuthorizationPolicy.isAuthorized(
                listOf(
                    identity("com.noamv.poopschedule", "poop-old"),
                    identity("example.shared", "poop-old"),
                ),
                approved,
            ),
        )
    }

    @Test
    fun rejectsMultipleCurrentSigners() {
        assertFalse(
            CallerAuthorizationPolicy.isAuthorized(
                listOf(
                    CallerSigningIdentity(
                        "com.noamv.poopschedule",
                        currentSignerDigests = setOf("poop-old", "other"),
                        signingLineageDigests = setOf("poop-old", "other"),
                    ),
                ),
                approved,
            ),
        )
    }

    @Test
    fun rejectsMissingOrAmbiguousUidPackages() {
        assertFalse(CallerAuthorizationPolicy.isAuthorized(emptyList(), approved))
    }

    private fun identity(packageName: String, signer: String) = CallerSigningIdentity(
        packageName,
        currentSignerDigests = setOf(signer),
        signingLineageDigests = setOf(signer),
    )
}
