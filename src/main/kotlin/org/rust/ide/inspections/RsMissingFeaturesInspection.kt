/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.inspections

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiFile
import org.rust.cargo.project.workspace.FeatureState
import org.rust.cargo.project.workspace.PackageFeature
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.ide.inspections.fixes.EnableCargoFeaturesFix
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.ext.containingCargoTarget

class RsMissingFeaturesInspection : RsLocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor?>? {
        val rsFile = file as? RsFile ?: return null
        val cargoProject = rsFile.cargoProject ?: return null
        val target = rsFile.containingCargoTarget ?: return null
        if (target.pkg.origin != PackageOrigin.WORKSPACE) return null

        val missingFeatures = mutableSetOf<PackageFeature>()

        for (dep in target.pkg.dependencies) {
            if (dep.pkg.origin == PackageOrigin.WORKSPACE) {
                for (requiredFeature in dep.requiredFeatures) {
                    if (dep.pkg.featureState[requiredFeature] == FeatureState.Disabled) {
                        missingFeatures += dep.pkg.findFeature(requiredFeature)
                    }
                }
            }
        }

        val libTarget = target.pkg.libTarget

        if (libTarget != null && target != libTarget) {
            for (requiredFeature in target.requiredFeatures) {
                if (target.pkg.featureState[requiredFeature] == FeatureState.Disabled) {
                    missingFeatures += target.pkg.findFeature(requiredFeature)
                }
            }
        }

        return if (missingFeatures.isEmpty()) {
            ProblemDescriptor.EMPTY_ARRAY
        } else {
            arrayOf(
                manager.createProblemDescriptor(
                    file,
                    "Missing features: ${missingFeatures.joinToString()}",
                    isOnTheFly,
                    arrayOf(EnableCargoFeaturesFix(cargoProject, missingFeatures)),
                    ProblemHighlightType.WARNING
                )
            )
        }
    }
}
