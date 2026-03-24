package com.eacape.speccodingplugin.spec

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SpecRelatedFilesServicePlatformTest : BasePlatformTestCase() {

    fun `test project service should use supported constructor signature`() {
        val service = SpecRelatedFilesService.getInstance(project)

        assertNotNull(service)
        assertSame(service, SpecRelatedFilesService.getInstance(project))
    }
}
