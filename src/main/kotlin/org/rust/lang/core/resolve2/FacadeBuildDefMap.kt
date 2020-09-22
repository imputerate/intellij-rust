/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.util.AutoInjectedCrates
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.crate.CratePersistentId
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.ext.edition
import org.rust.lang.core.psi.shouldIndexFile
import org.rust.openapiext.checkReadAccessAllowed
import org.rust.openapiext.fileId
import org.rust.openapiext.testAssert

/**
 * Returns `null` if [crate] has null `id` or `rootMod`,
 * or if crate should not be indexed (e.g. test/bench non-workspace crate)
 */
fun buildDefMap(
    crate: Crate,
    allDependenciesDefMaps: Map<Crate, CrateDefMap>,
    indicator: ProgressIndicator
): CrateDefMap? {
    // todo если dumbMod, то добавить crate в defMapService.changedCrates?
    //  и запускать updateAllDefMaps когда dumb mode завершится
    checkReadAccessAllowed()
    val project = crate.project
    check(project.isNewResolveEnabled)
    val context = CollectorContext(crate, project, indicator)
    val defMap = buildDefMapContainingExplicitItems(context, allDependenciesDefMaps) ?: return null
    DefCollector(project, defMap, context).collect()
    defMap.afterBuilt()
    afterDefMapBuiltDebug(defMap, context)
    project.defMapService.afterDefMapBuilt(defMap)
    testAssert({ !isCrateChanged(crate, defMap, indicator) }, { "DefMap $defMap should be up-to-date just after built" })
    return defMap
}

/** Context for [ModCollector] and [DefCollector] */
class CollectorContext(
    val crate: Crate,
    val project: Project,
    val indicator: ProgressIndicator,
) {
    /** All imports (including expanded from macros - filled in [DefCollector]) */
    val imports: MutableList<Import> = mutableListOf()

    /** All macro calls */
    val macroCalls: MutableList<MacroCallInfo> = mutableListOf()
}

private fun buildDefMapContainingExplicitItems(
    context: CollectorContext,
    allDependenciesDefMaps: Map<Crate, CrateDefMap>
): CrateDefMap? {
    val crate = context.crate
    val crateId = crate.id ?: return null
    val crateRoot = crate.rootMod ?: return null

    val crateRootFile = crate.rootModFile ?: return null
    if (!shouldIndexFile(context.project, crateRootFile)) return null

    val (directDependenciesDefMaps, allDependenciesDefMapsById) =
        getDirectAndAllDependencies(crate, crateRoot, allDependenciesDefMaps)

    val crateRootOwnedDirectory = crateRoot.parent
        ?: error("Can't find parent directory for crate root of $crate crate")
    val crateRootData = ModData(
        parent = null,
        crate = crateId,
        path = ModPath(crateId, emptyArray()),
        isDeeplyEnabledByCfg = true,
        fileId = crateRoot.virtualFile.fileId,
        fileRelativePath = "",
        ownedDirectoryId = crateRootOwnedDirectory.virtualFile.fileId
    )
    val defMap = CrateDefMap(
        crate = crateId,
        root = crateRootData,
        directDependenciesDefMaps = directDependenciesDefMaps,
        allDependenciesDefMaps = allDependenciesDefMapsById,
        prelude = findPrelude(crate, allDependenciesDefMaps),
        metaData = CrateMetaData(crate),
        crateDescription = crate.toString()
    )

    val collector = ModCollector(crateRootData, defMap, crateRootData, context)
    createExternCrateStdImport(crateRoot, crateRootData)?.let {
        context.imports += it
        collector.importExternCrateMacros(it.usePath.single())
    }
    collector.collectFileAndCalculateHash(crateRoot)

    removeInvalidImportsAndMacroCalls(defMap, context)
    sortImports(context.imports)
    return defMap
}

private fun getDirectAndAllDependencies(
    crate: Crate,
    crateRoot: RsFile,
    allDependenciesDefMaps: Map<Crate, CrateDefMap>
): Pair<Map<String, CrateDefMap>, Map<CratePersistentId, CrateDefMap>> {
    val attributes = crateRoot.attributes
    val shouldRemoveCore = attributes === RsFile.Attributes.NO_CORE
    val shouldRemoveStd = attributes === RsFile.Attributes.NO_STD || shouldRemoveCore
    val dependenciesDefMapsById = allDependenciesDefMaps
        .filterKeys {
            if (shouldRemoveStd && it.normName === AutoInjectedCrates.STD) return@filterKeys false
            if (shouldRemoveCore && it.normName === AutoInjectedCrates.CORE) return@filterKeys false
            it.id != null
        }
        .mapKeysTo(hashMapOf()) {
            it.key.id!!
        }
    val directDependenciesDefMaps = crate.dependencies
        .mapNotNull {
            val id = it.crate.id ?: return@mapNotNull null
            val defMap = dependenciesDefMapsById[id] ?: return@mapNotNull null
            it.normName to defMap
        }
        .toMap(hashMapOf())
    return Pair(directDependenciesDefMaps, dependenciesDefMapsById)
}

/**
 * Look for the prelude.
 * If the dependency defines a prelude, we overwrite an already defined prelude.
 * This is necessary to import the "std" prelude if a crate depends on both "core" and "std".
 */
private fun findPrelude(crate: Crate, allDependenciesDefMaps: Map<Crate, CrateDefMap>): ModData? {
    // TODO: check that correct prelude is always selected (core vs std)
    return crate.dependencies
        .mapNotNull { allDependenciesDefMaps[it.crate]?.prelude }
        .firstOrNull()
}

private fun createExternCrateStdImport(crateRoot: RsFile, crateRootData: ModData): Import? {
    // Rust injects implicit `extern crate std` in every crate root module unless it is
    // a `#![no_std]` crate, in which case `extern crate core` is injected. However, if
    // there is a (unstable?) `#![no_core]` attribute, nothing is injected.
    //
    // https://doc.rust-lang.org/book/using-rust-without-the-standard-library.html
    // The stdlib lib itself is `#![no_std]`, and the core is `#![no_core]`
    val name = when (crateRoot.attributes) {
        RsFile.Attributes.NONE -> AutoInjectedCrates.STD
        RsFile.Attributes.NO_STD -> AutoInjectedCrates.CORE
        RsFile.Attributes.NO_CORE -> return null
    }
    return Import(
        crateRootData,
        arrayOf(name),
        nameInScope = if (crateRoot.edition === CargoWorkspace.Edition.EDITION_2015) name else "_",
        visibility = Visibility.Restricted(crateRootData),
        isExternCrate = true
    )
}

/**
 * "Invalid" means it belongs to [ModData] which is no longer accessible from `defMap.root` using [ModData.childModules]
 * It could happen if there is cfg-disabled module, which we collect first (with its imports)
 * And then cfg-enabled module overrides previously created [ModData]
 */
private fun removeInvalidImportsAndMacroCalls(defMap: CrateDefMap, context: CollectorContext) {
    fun ModData.descendantsMods(): Sequence<ModData> =
        sequenceOf(this) + childModules.values.asSequence().flatMap { it.descendantsMods() }

    val allMods = defMap.root.descendantsMods().toSet()
    context.imports.removeIf { it.containingMod !in allMods }
    context.macroCalls.removeIf { it.containingMod !in allMods }
}

/**
 * This is a workaround for some real-project cases. See:
 * - [RsUseResolveTest.`test import adds same name as existing`]
 * - https://github.com/rust-lang/cargo/blob/875e0123259b0b6299903fe4aea0a12ecde9324f/src/cargo/util/mod.rs#L23
 */
private fun sortImports(imports: MutableList<Import>) {
    imports.sortWith(
        // TODO: Profile & optimize
        compareByDescending<Import> { it.nameInScope in it.containingMod.visibleItems }
            .thenBy { it.isGlob }
            .thenByDescending { it.containingMod.path.segments.size }  // imports from nested modules first
    )
}

private fun CrateDefMap.afterBuilt() {
    root.visitDescendants {
        it.isShadowedByOtherFile = false
    }

    // TODO: uncomment when #[cfg_attr] will be supported
    // testAssert { missedFiles.isEmpty() }
}

private fun ModData.visitDescendants(visitor: (ModData) -> Unit) {
    visitor(this)
    for (childMod in childModules.values) {
        childMod.visitDescendants(visitor)
    }
}
