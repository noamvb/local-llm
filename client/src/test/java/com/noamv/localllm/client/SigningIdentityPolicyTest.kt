package com.noamv.localllm.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SigningIdentityPolicyTest {
    private val pinned = setOf("old")

    @Test
    fun acceptsPinnedCurrentSigner() {
        assertTrue(
            SigningIdentityPolicy.isTrusted(
                SigningIdentity("com.noamv.localllm", setOf("old"), setOf("old")),
                "com.noamv.localllm",
                pinned,
            ),
        )
    }

    @Test
    fun acceptsAndroidAuthenticatedRotationFromPinnedSigner() {
        assertTrue(
            SigningIdentityPolicy.isTrusted(
                SigningIdentity(
                    "com.noamv.localllm",
                    currentSignerDigests = setOf("new"),
                    signingLineageDigests = setOf("old", "new"),
                ),
                "com.noamv.localllm",
                pinned,
            ),
        )
    }

    @Test
    fun rejectsSamePackageWithUnrelatedSigner() {
        assertFalse(
            SigningIdentityPolicy.isTrusted(
                SigningIdentity("com.noamv.localllm", setOf("fake"), setOf("fake")),
                "com.noamv.localllm",
                pinned,
            ),
        )
    }

    @Test
    fun rejectsApprovedSignerOnDifferentPackage() {
        assertFalse(
            SigningIdentityPolicy.isTrusted(
                SigningIdentity("example.fake", setOf("old"), setOf("old")),
                "com.noamv.localllm",
                pinned,
            ),
        )
    }

    @Test
    fun rejectsMultipleCurrentSigners() {
        assertFalse(
            SigningIdentityPolicy.isTrusted(
                SigningIdentity(
                    "com.noamv.localllm",
                    currentSignerDigests = setOf("old", "other"),
                    signingLineageDigests = setOf("old", "other"),
                ),
                "com.noamv.localllm",
                pinned,
            ),
        )
    }

    @Test
    fun rejectsLineageThatDoesNotContainCurrentSigner() {
        assertFalse(
            SigningIdentityPolicy.isTrusted(
                SigningIdentity("com.noamv.localllm", setOf("new"), setOf("old")),
                "com.noamv.localllm",
                pinned,
            ),
        )
    }

    @Test
    fun acceptsOnlyThePinnedInferenceServiceComponent() {
        assertTrue(ServiceIdentityPolicy.isTrusted(trustedServiceIdentity()))
    }

    @Test
    fun rejectsWrongServiceClassEvenInTrustedPackage() {
        assertFalse(
            ServiceIdentityPolicy.isTrusted(
                trustedServiceIdentity().copy(className = "com.noamv.localllm.service.DecoyService"),
            ),
        )
    }

    @Test
    fun rejectsPinnedComponentWithoutRequiredPermission() {
        assertFalse(ServiceIdentityPolicy.isTrusted(trustedServiceIdentity().copy(permission = null)))
    }

    private fun trustedServiceIdentity() = ServiceIdentity(
        packageName = LocalLlmClient.LOCALLLM_PACKAGE,
        className = LocalLlmClient.INFERENCE_SERVICE_CLASS,
        exported = true,
        serviceEnabled = true,
        applicationEnabled = true,
        permission = LocalLlmClient.INFERENCE_PERMISSION,
    )
}
