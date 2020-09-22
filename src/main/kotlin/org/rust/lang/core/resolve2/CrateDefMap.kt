/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiFile
import org.rust.lang.core.crate.CratePersistentId
import org.rust.lang.core.psi.RsEnumVariant
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.ext.RsMod
import org.rust.lang.core.resolve.Namespace
import org.rust.openapiext.fileId
import org.rust.openapiext.testAssert
import org.rust.stdext.HashCode
import java.nio.file.Path

class CrateDefMap(
    val crate: CratePersistentId,
    val root: ModData,

    // TODO: Not used after [CrateDefMap] was built, probably it is worth to move it to [CollectorContext]
    /** Used only by `extern crate crate_name;` declarations */
    val directDependenciesDefMaps: Map<String, CrateDefMap>,
    private val allDependenciesDefMaps: Map<CratePersistentId, CrateDefMap>,
    var prelude: ModData?,
    val metaData: CrateMetaData,
    /** Only for debug */
    val crateDescription: String,
) {
    val externPrelude: MutableMap<String, ModData> = directDependenciesDefMaps
        .mapValuesTo(hashMapOf()) { it.value.root }

    /**
     * File included via `include!` macro has same [FileInfo.modData] as main file,
     * but different [FileInfo.hash] and [FileInfo.modificationStamp]
     */
    val fileInfos: MutableMap<FileId, FileInfo> = hashMapOf()

    /**
     * Files which currently do not exist, but could affect resolve if created:
     * - for unresolved mod declarations - `.../name.rs` and `.../name/mod.rs`
     * - for unresolved `include!` macro - corresponding file
     *
     * Note: [missedFiles] should be empty if compilation is successful.
     */
    val missedFiles: MutableList<Path> = mutableListOf()

    /** For tests */
    val timestamp: Long = System.nanoTime()

    fun getDefMap(crate: CratePersistentId): CrateDefMap? =
        if (crate == this.crate) this else allDependenciesDefMaps[crate]

    fun getModData(path: ModPath): ModData? {
        val defMap = getDefMap(path.crate) ?: error("Can't find ModData for path $path")
        return defMap.doGetModData(path)
    }

    private fun doGetModData(path: ModPath): ModData? {
        check(crate == path.crate)
        return path.segments
            .fold(root as ModData?) { modData, segment -> modData?.childModules?.get(segment) }
            .also { testAssert({ it != null }, { "Can't find ModData for $path in crate $crateDescription" }) }
    }

    fun tryCastToModData(perNs: PerNs): ModData? {
        val types = perNs.types ?: return null
        return tryCastToModData(types)
    }

    // TODO: Possible optimization - store in [CrateDefMap] map from [String] (mod path) to [ModData]
    fun tryCastToModData(types: VisItem): ModData? {
        if (!types.isModOrEnum) return null
        return getModData(types.path)
    }

    fun getModData(mod: RsMod): ModData? {
        if (mod is RsFile) {
            val virtualFile = mod.originalFile.virtualFile ?: return null
            val fileInfo = fileInfos[virtualFile.fileId]
            // TODO: Exception here does not fail [RsBuildDefMapTest]
            // Note: we don't expand cfg-disabled macros (it can contain mod declaration)
            testAssert { fileInfo !== null || !mod.isDeeplyEnabledByCfg }
            return fileInfo?.modData
        }
        val parentMod = mod.`super` ?: return null
        val parentModData = getModData(parentMod) ?: return null
        return parentModData.childModules[mod.modName]
    }

    fun getMacroInfo(macroDef: VisItem): MacroDefInfo {
        val defMap = getDefMap(macroDef.crate) ?: error("Can't find DefMap for macro $macroDef")
        return defMap.doGetMacroInfo(macroDef)
    }

    // TODO: [RsMacro2]
    private fun doGetMacroInfo(macroDef: VisItem): MacroDefInfo {
        val containingMod = getModData(macroDef.containingMod) ?: error("Can't find ModData for macro $macroDef")
        return containingMod.legacyMacros[macroDef.name] ?: error("Can't find definition for macro $macroDef")
    }

    /**
     * Import all exported macros from another crate.
     *
     * Exported macros are just all macros in the root module scope.
     * Note that it contains not only all ```#[macro_export]``` macros, but also all aliases
     * created by `use` in the root module, ignoring the visibility of `use`.
     */
    fun importAllMacrosExported(from: CrateDefMap) {
        for ((name, def) in from.root.visibleItems) {
            val macroDef = def.macros ?: continue
            // `macro_use` only bring things into legacy scope.
            root.legacyMacros[name] = from.getMacroInfo(macroDef)
        }
    }

    fun addVisitedFile(file: RsFile, modData: ModData, fileHash: HashCode) {
        val fileId = file.virtualFile.fileId
        // TODO: File included in module tree multiple times ?
        // testAssert { fileId !in fileInfos }
        fileInfos[fileId] = FileInfo(file.modificationStampForResolve, modData, fileHash)
    }

    override fun toString(): String = crateDescription
}

typealias FileId = Int

class FileInfo(
    /**
     * Result of [FileViewProvider.getModificationStamp].
     *
     * Here are possible (other) methods to use:
     * - [PsiFile.getModificationStamp]
     * - [FileViewProvider.getModificationStamp]
     * - [VirtualFile.getModificationStamp]
     * - [VirtualFile.getModificationCount]
     * - [Document.getModificationStamp]
     *
     * Notes:
     * - [VirtualFile] methods value is updated only after file is saved to disk
     * - Only [VirtualFile.getModificationCount] survives IDE restart
     */
    val modificationStamp: Long,
    /** Optimization for [CrateDefMap.getModData] */
    val modData: ModData,
    val hash: HashCode,
)

class ModData(
    val parent: ModData?,
    val crate: CratePersistentId,
    val path: ModPath,
    val isDeeplyEnabledByCfg: Boolean,
    /** id of containing file */
    val fileId: FileId,
    // TODO: Possible optimization - store as Array<String>
    /** Starts with :: */
    val fileRelativePath: String,
    /** `fileId` of owning directory */
    val ownedDirectoryId: FileId?,
    val isEnum: Boolean = false,
) {
    val name: String get() = path.name
    val isCrateRoot: Boolean get() = parent === null
    val isRsFile: Boolean get() = fileRelativePath.isEmpty()
    val parents: Sequence<ModData> get() = generateSequence(this) { it.parent }

    // TODO: Compare with storing three maps
    val visibleItems: MutableMap<String, PerNs> = hashMapOf()
    val childModules: MutableMap<String, ModData> = hashMapOf()

    /**
     * Macros visible in current module in legacy textual scope.
     * Module scoped macros will be inserted into [visibleItems] instead of here.
     * Currently stores only cfg-enabled macros.
     */
    val legacyMacros: MutableMap<String, MacroDefInfo> = hashMapOf()

    /** Traits imported via `use Trait as _;` */
    val unnamedTraitImports: MutableMap<ModPath, Visibility> = hashMapOf()

    /**
     * Make sense only for files ([isRsFile] == true).
     * Value `false` means that `this` is not accessible from [CrateDefMap.root] through [ModData.childModules],
     * but can be accessible using [CrateDefMap.fileInfos].
     * It could happen when two mod declarations with same path has different cfg-attributes.
     */
    var isShadowedByOtherFile: Boolean = true

    operator fun get(name: String): PerNs = visibleItems.getOrDefault(name, PerNs.Empty)

    fun getVisibleItems(filterVisibility: (Visibility) -> Boolean): List<Pair<String, PerNs>> {
        val usualItems = visibleItems.entries
            .map { (name, visItem) -> name to visItem.filterVisibility(filterVisibility) }
            .filterNot { (_, visItem) -> visItem.isEmpty }
        if (unnamedTraitImports.isEmpty()) return usualItems

        val traitItems = unnamedTraitImports
            .mapNotNull { (path, visibility) ->
                if (!filterVisibility(visibility)) return@mapNotNull null
                val trait = VisItem(path, visibility, isModOrEnum = false)
                "_" to PerNs(types = trait)
            }
        return usualItems + traitItems
    }

    fun addVisibleItem(name: String, def: PerNs) {
        val defExisting = visibleItems.putIfAbsent(name, def)
        if (defExisting === null) return
        defExisting.update(def)
    }

    fun asVisItem(visibility: Visibility = Visibility.Public): VisItem {
        val parent = parent
        return if (parent === null) {
            // crate root
            VisItem(path, visibility, true)
        } else {
            parent.visibleItems[name]?.types?.takeIf { it.isModOrEnum }
                ?: error("Inconsistent `visibleItems` and `childModules` in parent of $this")
        }
    }

    fun asPerNs(visibility: Visibility = Visibility.Public): PerNs = PerNs(types = asVisItem(visibility))

    fun getNthParent(n: Int): ModData? {
        check(n >= 0)
        var current = this
        repeat(n) {
            current = current.parent ?: return null
        }
        return current
    }

    override fun toString(): String = "ModData($path, crate=$crate)"
}

data class PerNs(
    var types: VisItem? = null,
    var values: VisItem? = null,
    var macros: VisItem? = null,
) {
    val isEmpty: Boolean get() = types === null && values === null && macros === null

    constructor(visItem: VisItem, ns: Set<Namespace>) :
        this(
            visItem.takeIf { Namespace.Types in ns },
            visItem.takeIf { Namespace.Values in ns },
            visItem.takeIf { Namespace.Macros in ns }
        )

    fun withVisibility(visibility: Visibility): PerNs =
        PerNs(
            types?.withVisibility(visibility),
            values?.withVisibility(visibility),
            macros?.withVisibility(visibility)
        )

    fun filterVisibility(filter: (Visibility) -> Boolean): PerNs =
        PerNs(
            types?.takeIf { filter(it.visibility) },
            values?.takeIf { filter(it.visibility) },
            macros?.takeIf { filter(it.visibility) }
        )

    // TODO: Consider unite with [DefCollector#pushResolutionFromImport]
    fun update(other: PerNs) {
        fun merge(existing: VisItem?, new: VisItem?): VisItem? {
            if (existing === null) return new
            if (new === null) return existing
            return if (new.visibility.isStrictlyMorePermissive(existing.visibility)) new else existing
        }
        types = merge(types, other.types)
        values = merge(values, other.values)
        macros = merge(macros, other.macros)
    }

    fun or(other: PerNs): PerNs {
        if (other.isEmpty) return this
        return PerNs(
            types ?: other.types,
            values ?: other.values,
            macros ?: other.macros
        )
    }

    fun mapItems(f: (VisItem) -> VisItem): PerNs =
        PerNs(
            types?.let { f(it) },
            values?.let { f(it) },
            macros?.let { f(it) }
        )

    companion object {
        val Empty: PerNs = PerNs()
    }
}

/**
 * The item which can be visible in the module (either directly declared or imported)
 * Could be [RsEnumVariant] (because it can be imported)
 */
data class VisItem(
    // TODO:
    //  Measure memory usage caused by [path]
    //  Probably it is better to store [containingMod] and [name] separately
    /**
     * Full path to item, including its name.
     * Note: Can't store [containingMod] and [name] separately, because [VisItem] could be used for crate root
     */
    val path: ModPath,
    val visibility: Visibility,
    val isModOrEnum: Boolean = false,
) {
    init {
        check(isModOrEnum || path.segments.isNotEmpty())
    }

    val containingMod: ModPath get() = path.parent  // mod where item is explicitly declared
    val name: String get() = path.name
    val crate: CratePersistentId get() = path.crate

    fun withVisibility(visibilityNew: Visibility): VisItem =
        if (visibility === visibilityNew || visibility.isInvisible) this else copy(visibility = visibilityNew)

    override fun toString(): String = "$visibility $path"
}

sealed class Visibility {
    object Public : Visibility()

    /** includes private */
    data class Restricted(val inMod: ModData) : Visibility()

    /**
     * Means that we have import to private item
     * So normally we should ignore such [VisItem] (it is not accessible)
     * But we record it for completion, etc
     */
    object Invisible : Visibility()

    object CfgDisabled : Visibility()

    fun isVisibleFromOtherCrate(): Boolean = this === Public

    fun isVisibleFromMod(mod: ModData): Boolean {
        return when (this) {
            Public -> true
            // Alternative realization: `mod.parents.contains(inMod)`
            is Restricted -> inMod.path.isSubPathOf(mod.path)
            Invisible, CfgDisabled -> false
        }
    }

    fun isStrictlyMorePermissive(other: Visibility): Boolean {
        return if (this is Restricted && other is Restricted) {
            inMod.crate == other.inMod.crate
                && inMod !== other.inMod
                && other.inMod.parents.contains(inMod)
        } else {
            when (this) {
                Public -> other !is Public
                is Restricted -> other === Invisible || other === CfgDisabled
                Invisible -> other === CfgDisabled
                CfgDisabled -> false
            }
        }
    }

    val isInvisible: Boolean get() = this === Invisible || this === CfgDisabled

    override fun toString(): String =
        when (this) {
            Public -> "Public"
            is Restricted -> "Restricted(in ${inMod.path})"
            Invisible -> "Invisible"
            CfgDisabled -> "CfgDisabled"
        }
}

/** Path to a module or an item in module */
class ModPath(
    val crate: CratePersistentId,
    val segments: Array<String>,
    // val fileId: FileId,  // id of containing file
    // val fileRelativePath: String  // empty for pathRsFile
) {
    val name: String get() = segments.last()
    val parent: ModPath get() = ModPath(crate, segments.copyOfRange(0, segments.size - 1))

    fun append(segment: String): ModPath = ModPath(crate, segments + segment)

    override fun toString(): String = segments.joinToString("::").ifEmpty { "crate" }

    /** `mod1::mod2` isSubPathOf `mod1::mod2::mod3` */
    fun isSubPathOf(other: ModPath): Boolean {
        if (crate != other.crate) return false

        if (segments.size > other.segments.size) return false
        for (index in segments.indices) {
            if (segments[index] != other.segments[index]) return false
        }
        return true
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ModPath
        return crate == other.crate && segments.contentEquals(other.segments)
    }

    override fun hashCode(): Int = 31 * crate + segments.contentHashCode()
}

val RESOLVE_LOG = Logger.getInstance("org.rust.resolve2")
