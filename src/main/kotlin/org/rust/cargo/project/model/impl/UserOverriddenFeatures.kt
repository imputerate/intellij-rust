/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.project.model.impl

import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.project.workspace.FeatureState
import org.rust.cargo.project.workspace.PackageFeature
import org.rust.cargo.project.workspace.PackageRoot
import org.rust.stdext.exhaustive

abstract class UserOverriddenFeatures {
    // Package -> disabled Features
    abstract val userOverriddenFeatures: Map<PackageRoot, Set<String>>

    fun getDisabledFeatures(packages: Iterable<CargoWorkspace.Package>): List<PackageFeature> {
        return packages.flatMap { pkg ->
            userOverriddenFeatures[pkg.rootDirectory]
                ?.mapNotNull { name -> pkg.findFeature(name).takeIf { it in pkg.features } }
                ?: emptyList()
        }
    }

    fun isEmpty(): Boolean {
        return userOverriddenFeatures.isEmpty() || userOverriddenFeatures.values.all { it.isEmpty() }
    }

    fun toMutable(): MutableUserOverriddenFeatures = MutableUserOverriddenFeatures(
        userOverriddenFeatures
            .mapValues { (_, v) -> v.toMutableSet() }
            .toMutableMap()
    )

    fun retain(packages: Iterable<CargoWorkspace.Package>): UserOverriddenFeatures {
        val newMap = EMPTY.toMutable()
        for (disabledFeature in getDisabledFeatures(packages)) {
            newMap.setFeatureState(disabledFeature, FeatureState.Disabled)
        }
        return newMap
    }

    companion object {
        val EMPTY: UserOverriddenFeatures = ImmutableUserOverriddenFeatures(emptyMap())

        fun of(userOverriddenFeatures: Map<PackageRoot, Set<String>>): UserOverriddenFeatures =
            ImmutableUserOverriddenFeatures(userOverriddenFeatures)
    }
}

private class ImmutableUserOverriddenFeatures(
    override val userOverriddenFeatures: Map<PackageRoot, Set<String>>
) : UserOverriddenFeatures()

class MutableUserOverriddenFeatures(
    override val userOverriddenFeatures: MutableMap<PackageRoot, MutableSet<String>>
) : UserOverriddenFeatures() {

    fun setFeatureState(
        feature: PackageFeature,
        state: FeatureState
    ) {
        val packageRoot = feature.pkg.rootDirectory
        when (state) {
            FeatureState.Enabled -> {
                userOverriddenFeatures[packageRoot]?.remove(feature.name)
            }
            FeatureState.Disabled -> {
                userOverriddenFeatures.getOrPut(packageRoot) { hashSetOf() }
                    .add(feature.name)
            }
        }.exhaustive
    }
}
