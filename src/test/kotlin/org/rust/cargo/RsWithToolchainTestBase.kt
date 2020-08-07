/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo

import com.intellij.openapi.util.RecursionManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.util.ui.UIUtil
import org.rust.*
import org.rust.cargo.project.model.impl.testCargoProjects
import org.rust.ide.sdk.toolchain

/**
 * This class allows executing real Cargo during the tests.
 *
 * Unlike [org.rust.RsTestBase] it does not use in-memory temporary VFS
 * and instead copies real files.
 */
abstract class RsWithToolchainTestBase : CodeInsightFixtureTestCase<ModuleFixtureBuilder<*>>() {

    private lateinit var rustupFixture: RustupTestFixture

    open val dataPath: String = ""

    open val disableMissedCacheAssertions: Boolean get() = true

    protected val cargoProjectDirectory: VirtualFile get() = myFixture.findFileInTempDir(".")

    protected fun FileTree.create(): TestProject =
        create(project, cargoProjectDirectory).apply {
            refreshWorkspace()
        }

    protected fun refreshWorkspace() {
        project.testCargoProjects.discoverAndRefreshSync()
    }

    override fun runTest() {
        val skipReason = rustupFixture.skipTestReason
        if (skipReason != null) {
            System.err.println("SKIP \"$name\": $skipReason")
            return
        }
        val minRustVersion = findAnnotationInstance<MinRustcVersion>()
        if (minRustVersion != null) {
            val requiredVersion = minRustVersion.semver
            val rustcVersion = rustupFixture.sdk!!.toolchain!!.queryVersions().rustc
            if (rustcVersion == null) {
                System.err.println("SKIP \"$name\": failed to query Rust version")
                return
            }

            if (rustcVersion.semver < requiredVersion) {
                println("SKIP \"$name\": $requiredVersion Rust version required, ${rustcVersion.semver} found")
                return
            }
        }
        super.runTest()
    }

    override fun setUp() {
        super.setUp()
        rustupFixture = RustupTestFixture(project)
        rustupFixture.setUp()
        if (disableMissedCacheAssertions) {
            RecursionManager.disableMissedCacheAssertions(testRootDisposable)
        }
    }

    override fun tearDown() {
        rustupFixture.tearDown()
        super.tearDown()
        checkMacroExpansionFileSystemAfterTest()
    }

    protected fun buildProject(builder: FileTreeBuilder.() -> Unit): TestProject =
        fileTree { builder() }.create()

    /** Tries to find the specified annotation on the current test method and then on the current class */
    private inline fun <reified T : Annotation> findAnnotationInstance(): T? =
        javaClass.getMethod(name).getAnnotation(T::class.java) ?: javaClass.getAnnotation(T::class.java)

    /**
     * Tries to launches [action]. If it returns `false`, invokes [UIUtil.dispatchAllInvocationEvents] and tries again
     *
     * Can be used to wait file system refresh, for example
     */
    protected fun runWithInvocationEventsDispatching(
        errorMessage: String = "Failed to invoke `action` successfully",
        retries: Int = 1000,
        action: () -> Boolean
    ) {
        repeat(retries) {
            UIUtil.dispatchAllInvocationEvents()
            if (action()) {
                return
            }
            Thread.sleep(10)
        }
        error(errorMessage)
    }
}
