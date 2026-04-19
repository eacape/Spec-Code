package com.eacape.speccodingplugin.context

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

class CodeGraphServicePlatformTest : BasePlatformTestCase() {

    fun `test buildFromActiveEditor should cache snapshots per graph options`() {
        createProjectFile(
            "HelperA.java",
            """
            class HelperA {
                static String helperA() {
                    return "A";
                }
            }
            """.trimIndent(),
        )
        createProjectFile(
            "HelperB.java",
            """
            class HelperB {
                static String helperB() {
                    return "B";
                }
            }
            """.trimIndent(),
        )
        val mainFile = createProjectFile(
            "Main.java",
            """
            class Main {
                String runTask() {
                    return HelperA.helperA() + HelperB.helperB();
                }
            }
            """.trimIndent(),
        )
        openInEditor(mainFile)

        val service = CodeGraphService.getInstance(project)
        val narrowOptions = CodeGraphService.GraphBuildOptions(maxDependencies = 1, maxCallEdges = 10)
        val wideOptions = CodeGraphService.GraphBuildOptions(maxDependencies = 4, maxCallEdges = 10)

        val narrowSnapshot = service.buildFromActiveEditor(narrowOptions).getOrThrow()
        val repeatedNarrowSnapshot = service.buildFromActiveEditor(narrowOptions).getOrThrow()
        val wideSnapshot = service.buildFromActiveEditor(wideOptions).getOrThrow()
        val repeatedWideSnapshot = service.buildFromActiveEditor(wideOptions).getOrThrow()

        assertSame(narrowSnapshot, repeatedNarrowSnapshot)
        assertSame(wideSnapshot, repeatedWideSnapshot)
        assertNotSame(narrowSnapshot, wideSnapshot)
        assertEquals(1, narrowSnapshot.dependencyEdges().size)
        assertTrue(wideSnapshot.dependencyEdges().size >= 2)
    }

    fun `test buildFromActiveEditor should invalidate cached snapshot after active file content change`() {
        val mainFile = createProjectFile(
            "Main.java",
            """
            class Main {
                void runTask() {
                    prepare();
                }

                void prepare() {
                }
            }
            """.trimIndent(),
        )
        openInEditor(mainFile)

        val service = CodeGraphService.getInstance(project)
        val initialSnapshot = service.buildFromActiveEditor().getOrThrow()
        val cacheStatsBeforeRootEdit = service.cacheStatsSnapshot()

        overwriteFile(
            mainFile,
            """
            class Main {
                void runTask() {
                    prepare();
                    finish();
                }

                void prepare() {
                }

                void finish() {
                }
            }
            """.trimIndent(),
        )

        val updatedSnapshot = service.buildFromActiveEditor().getOrThrow()
        val cacheStatsAfterRootEdit = service.cacheStatsSnapshot()

        assertNotSame(initialSnapshot, updatedSnapshot)
        assertEquals(
            cacheStatsBeforeRootEdit.missCount + 1,
            cacheStatsAfterRootEdit.missCount,
        )
        assertEquals("root-content-change:Main.java", cacheStatsAfterRootEdit.lastInvalidationReason)
    }

    fun `test buildFromActiveEditor should keep cached snapshot after unrelated file content change`() {
        createProjectFile(
            "Helper.java",
            """
            class Helper {
                static String helper() {
                    return "ok";
                }
            }
            """.trimIndent(),
        )
        val unrelatedFile = createProjectFile(
            "Detached.java",
            """
            class Detached {
                static String detached() {
                    return "before";
                }
            }
            """.trimIndent(),
        )
        val mainFile = createProjectFile(
            "Main.java",
            """
            class Main {
                String runTask() {
                    return Helper.helper();
                }
            }
            """.trimIndent(),
        )
        openInEditor(mainFile)

        val service = CodeGraphService.getInstance(project)
        val initialSnapshot = service.buildFromActiveEditor().getOrThrow()
        val cacheStatsBeforeUnrelatedEdit = service.cacheStatsSnapshot()

        overwriteFile(
            unrelatedFile,
            """
            class Detached {
                static String detached() {
                    return "after";
                }
            }
            """.trimIndent(),
        )

        val repeatedSnapshot = service.buildFromActiveEditor().getOrThrow()
        val cacheStatsAfterUnrelatedEdit = service.cacheStatsSnapshot()

        assertSame(initialSnapshot, repeatedSnapshot)
        assertEquals(
            cacheStatsBeforeUnrelatedEdit.missCount,
            cacheStatsAfterUnrelatedEdit.missCount,
        )
        assertEquals(
            cacheStatsBeforeUnrelatedEdit.hitCount + 1,
            cacheStatsAfterUnrelatedEdit.hitCount,
        )
    }

    fun `test buildFromActiveEditor should invalidate cached dependency paths after VFS rename`() {
        val helperFile = createProjectFile(
            "Helper.java",
            """
            class Helper {
                static String helper() {
                    return "ok";
                }
            }
            """.trimIndent(),
        )
        val mainFile = createProjectFile(
            "Main.java",
            """
            class Main {
                String runTask() {
                    return Helper.helper();
                }
            }
            """.trimIndent(),
        )
        openInEditor(mainFile)

        val service = CodeGraphService.getInstance(project)
        val initialSnapshot = service.buildFromActiveEditor().getOrThrow()
        val cacheStatsBeforeDependencyRename = service.cacheStatsSnapshot()
        assertTrue(initialSnapshot.dependencyEdges().any { edge -> edge.toId.contains("Helper.java") })
        if (helperFile.fileSystem.protocol != "file") {
            return
        }

        renameFile(helperFile, "HelperRenamed.java")

        val renamedSnapshot = service.buildFromActiveEditor().getOrThrow()
        val cacheStatsAfterDependencyRename = service.cacheStatsSnapshot()

        assertNotSame(initialSnapshot, renamedSnapshot)
        assertTrue(renamedSnapshot.dependencyEdges().any { edge -> edge.toId.contains("HelperRenamed.java") })
        assertFalse(renamedSnapshot.dependencyEdges().any { edge -> edge.toId.contains("Helper.java") })
        assertEquals(
            cacheStatsBeforeDependencyRename.missCount + 1,
            cacheStatsAfterDependencyRename.missCount,
        )
        assertTrue(cacheStatsAfterDependencyRename.lastInvalidationReason.startsWith("vfs-rename:"))
    }

    fun `test buildFromActiveEditor should keep cached snapshot after unrelated VFS rename`() {
        createProjectFile(
            "Helper.java",
            """
            class Helper {
                static String helper() {
                    return "ok";
                }
            }
            """.trimIndent(),
        )
        val unrelatedFile = createProjectFile(
            "Detached.java",
            """
            class Detached {
                static String detached() {
                    return "ok";
                }
            }
            """.trimIndent(),
        )
        val mainFile = createProjectFile(
            "Main.java",
            """
            class Main {
                String runTask() {
                    return Helper.helper();
                }
            }
            """.trimIndent(),
        )
        openInEditor(mainFile)

        val service = CodeGraphService.getInstance(project)
        val initialSnapshot = service.buildFromActiveEditor().getOrThrow()
        val cacheStatsBeforeUnrelatedRename = service.cacheStatsSnapshot()

        renameFile(unrelatedFile, "DetachedRenamed.java")

        val repeatedSnapshot = service.buildFromActiveEditor().getOrThrow()
        val cacheStatsAfterUnrelatedRename = service.cacheStatsSnapshot()

        assertSame(initialSnapshot, repeatedSnapshot)
        assertEquals(
            cacheStatsBeforeUnrelatedRename.missCount,
            cacheStatsAfterUnrelatedRename.missCount,
        )
        assertEquals(
            cacheStatsBeforeUnrelatedRename.hitCount + 1,
            cacheStatsAfterUnrelatedRename.hitCount,
        )
    }

    private fun openInEditor(file: VirtualFile) {
        myFixture.openFileInEditor(file)
        UIUtil.dispatchAllInvocationEvents()
    }

    private fun overwriteFile(file: VirtualFile, content: String) {
        WriteAction.runAndWait<Throwable> {
            VfsUtil.saveText(file, content)
        }
        UIUtil.dispatchAllInvocationEvents()
    }

    private fun renameFile(file: VirtualFile, newName: String) {
        WriteAction.runAndWait<Throwable> {
            file.rename(this@CodeGraphServicePlatformTest, newName)
        }
        UIUtil.dispatchAllInvocationEvents()
    }

    private fun createProjectFile(relativePath: String, content: String): VirtualFile {
        return myFixture.addFileToProject(relativePath, content).virtualFile
            ?: error("Failed to create file $relativePath")
    }
}
