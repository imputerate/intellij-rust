/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.project.settings

import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.Transient
import org.rust.cargo.toolchain.ExternalLinter
import org.rust.cargo.toolchain.RustToolchain
import org.rust.ide.experiments.RsExperiments
import org.rust.ide.sdk.RsSdkUtils.findSdkByName
import org.rust.ide.sdk.toolchain
import org.rust.openapiext.isFeatureEnabled
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

interface RustProjectSettingsService {

    data class State(
        var version: Int? = null,
        @AffectsCargoMetadata
        var sdkName: String? = null,
        var autoUpdateEnabled: Boolean = true,
        @AffectsHighlighting
        var externalLinter: ExternalLinter = ExternalLinter.DEFAULT,
        @AffectsHighlighting
        var runExternalLinterOnTheFly: Boolean = false,
        @AffectsHighlighting
        var externalLinterArguments: String = "",
        @AffectsHighlighting
        var compileAllTargets: Boolean = true,
        var useOffline: Boolean = false,
        var macroExpansionEngine: MacroExpansionEngine = defaultMacroExpansionEngine,
        @AffectsHighlighting
        var doctestInjectionEnabled: Boolean = true,
        var runRustfmtOnSave: Boolean = false,
        var useSkipChildren: Boolean = false
    ) {
        @get:Transient
        @set:Transient
        var sdk: Sdk?
            get() = sdkName?.let { findSdkByName(it) }
            set(value) {
                sdkName = value?.name
            }
    }

    enum class MacroExpansionEngine {
        DISABLED, OLD, NEW
    }

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.PROPERTY)
    private annotation class AffectsCargoMetadata

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.PROPERTY)
    private annotation class AffectsHighlighting

    /**
     * Allows to modify settings.
     * After setting change,
     */
    fun modify(action: (State) -> Unit)

    val version: Int?
    val sdk: Sdk?
    val autoUpdateEnabled: Boolean
    val externalLinter: ExternalLinter
    val runExternalLinterOnTheFly: Boolean
    val externalLinterArguments: String
    val compileAllTargets: Boolean
    val useOffline: Boolean
    val macroExpansionEngine: MacroExpansionEngine
    val doctestInjectionEnabled: Boolean
    val runRustfmtOnSave: Boolean
    val useSkipChildren: Boolean

    /*
     * Show a dialog for toolchain configuration
     */
    fun configureToolchain()

    companion object {
        val RUST_SETTINGS_TOPIC: Topic<RustSettingsListener> = Topic(
            "rust settings changes",
            RustSettingsListener::class.java
        )

        private val defaultMacroExpansionEngine: MacroExpansionEngine
            get() = if (isFeatureEnabled(RsExperiments.MACROS_NEW_ENGINE)) {
                MacroExpansionEngine.NEW
            } else {
                MacroExpansionEngine.OLD
            }
    }

    interface RustSettingsListener {
        fun rustSettingsChanged(e: RustSettingsChangedEvent)
    }

    data class RustSettingsChangedEvent(val oldState: State, val newState: State) {

        val affectsCargoMetadata: Boolean
            get() = cargoMetadataAffectingProps.any(::isChanged)

        val affectsHighlighting: Boolean
            get() = highlightingAffectingProps.any(::isChanged)

        /** Use it like `event.isChanged(State::foo)` to check whether `foo` property is changed or not */
        fun isChanged(prop: KProperty1<State, *>): Boolean = prop.get(oldState) != prop.get(newState)

        companion object {
            private val cargoMetadataAffectingProps: List<KProperty1<State, *>> =
                State::class.memberProperties.filter { it.findAnnotation<AffectsCargoMetadata>() != null }
            private val highlightingAffectingProps: List<KProperty1<State, *>> =
                State::class.memberProperties.filter { it.findAnnotation<AffectsHighlighting>() != null }
        }
    }
}

val Project.rustSettings: RustProjectSettingsService
    get() = ServiceManager.getService(this, RustProjectSettingsService::class.java)
        ?: error("Failed to get RustProjectSettingsService for $this")

val Project.toolchain: RustToolchain? get() = rustSettings.sdk?.toolchain
