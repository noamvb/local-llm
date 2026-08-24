package com.noamv.localllm.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelTransferManifestTest {
    @Test
    fun `private data sync service and required permissions are declared`() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE\""))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
        val transferDeclaration = manifest.substringAfter(".service.ModelTransferService")
            .substringBefore("/>")
        assertTrue(transferDeclaration.contains("android:exported=\"false\""))
        assertTrue(transferDeclaration.contains("android:foregroundServiceType=\"dataSync\""))
    }

    @Test
    fun `bound inference and self test sources have no acquisition entry point`() {
        val inference = projectFile(
            "app/src/main/java/com/noamv/localllm/service/InferenceService.kt",
        ).readText()
        val manager = projectFile(
            "app/src/main/java/com/noamv/localllm/ui/ManagerViewModel.kt",
        ).readText()

        assertFalse(inference.contains("ModelAcquirer"))
        assertFalse(inference.contains("performOwnerModelTransfer"))
        assertFalse(inference.contains("ModelTransferService.start"))
        val selfTest = manager.substringAfter("fun runSelfTest()")
            .substringBefore("private fun updateSelfTest")
        assertFalse(selfTest.contains("startOwnerTransfer"))
        assertFalse(selfTest.contains("performOwnerModelTransfer"))

        val application = projectFile(
            "app/src/main/java/com/noamv/localllm/LocalLlmApplication.kt",
        ).readText()
        val bindPrewarm = application.substringAfter("fun prewarmModel()")
            .substringBefore("internal fun prepareInstalledModel()")
        assertFalse(bindPrewarm.contains("modelAcquirer"))
        assertFalse(bindPrewarm.contains("performOwnerModelTransfer"))
        assertFalse(bindPrewarm.contains("ModelTransferService.start"))
    }

    @Test
    fun `foreground promotion precedes policy disk and acquisition work in session source`() {
        val source = projectFile(
            "app/src/main/java/com/noamv/localllm/service/ModelTransferService.kt",
        ).readText()
        val session = source.substringAfter("private fun startSession(")
            .substringBefore("private fun cancelAndStop(")

        val foreground = session.indexOf("ensureForeground(preflightStatus(session))")
        assertTrue(foreground >= 0)
        assertTrue(foreground < session.indexOf("beginOwnerModelTransfer"))
        assertTrue(foreground < session.indexOf("networks.acquire"))
        assertTrue(foreground < session.indexOf("performOwnerModelTransfer"))
        assertTrue(source.contains("return START_NOT_STICKY"))
        assertFalse(source.contains("return START_STICKY"))
        assertFalse(source.contains("checkSelfPermission"))
        assertTrue(session.contains("serviceScope.launch(start = CoroutineStart.LAZY)"))
        assertTrue(session.contains("TransferAcquisitionPath.LOCAL_VERIFY_AND_PROMOTE"))
        assertTrue(session.indexOf("acquisitionPath") < session.indexOf("networks.acquire"))
        assertTrue(source.contains("NO_NETWORK_CALL_FACTORY"))
        assertTrue(source.contains("runPostForegroundTransferSetup"))
        val application = projectFile(
            "app/src/main/java/com/noamv/localllm/LocalLlmApplication.kt",
        ).readText()
        assertFalse(application.contains("OwnerModelAcquisitionCoordinator"))
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("BOOT_COMPLETED"))
        val timeout = source.substringAfter("override fun onTimeout(startId: Int, fgsType: Int)")
            .substringBefore("override fun onDestroy()")
        assertTrue(timeout.contains("cancelAndStop(TransferStopReason.SERVICE_TIMEOUT)"))
        val destruction = source.substringAfter("override fun onDestroy()")
            .substringBefore("companion object")
        assertTrue(destruction.contains("activeJob?.cancel()"))
    }

    private fun projectFile(path: String): File {
        val fromRoot = File(path)
        if (fromRoot.isFile) return fromRoot
        return File("..", path).also { check(it.isFile) { "Missing test source $path" } }
    }
}
