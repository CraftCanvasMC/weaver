/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
 *                    Contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 only, no later versions.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight.core.taskcontainers

import io.papermc.paperweight.core.tasks.patching.ApplyBaseFeaturePatches
import io.papermc.paperweight.core.tasks.patching.ApplyFeaturePatches
import io.papermc.paperweight.core.tasks.patching.ApplyFilePatches
import io.papermc.paperweight.core.tasks.patching.ApplyFilePatchesFuzzy
import io.papermc.paperweight.core.tasks.patching.FixupFilePatches
import io.papermc.paperweight.core.tasks.patching.RebuildFilePatches
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.paperTaskOutput
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

class PatchingTasks(
    private val project: Project,
    private val forkName: String,
    private val patchSetName: String,
    private val taskGroup: String,
    private val readOnly: Boolean,
    private val filePatchDir: DirectoryProperty,
    private val rejectsDir: DirectoryProperty,
    private val baseRejectsDir: DirectoryProperty,
    private val featurePatchDir: DirectoryProperty,
    private val baseFeaturePatchDir: DirectoryProperty,
    private val baseDir: Provider<Directory>,
    private val gitFilePatches: Provider<Boolean>,
    private val filterPatches: Provider<Boolean>,
    private val outputDir: Path,
    private val tasks: TaskContainer = project.tasks,
) {
    private val namePart: String = if (readOnly) "${forkName.capitalized()}${patchSetName.capitalized()}" else patchSetName.capitalized()

    private fun ApplyFilePatches.configureApplyFilePatches() {
        group = taskGroup
        description = "Applies $patchSetName file patches"

        if (readOnly) {
            repo.set(layout.cache.resolve(paperTaskOutput()))
        } else {
            repo.set(outputDir)
        }

        patches.set(filePatchDir.fileExists(project))
        rejectsDir.set(this@PatchingTasks.rejectsDir)
        gitFilePatches.set(this@PatchingTasks.gitFilePatches)
        base.set(applyBaseFeaturePatches.flatMap { it.output })
        identifier = "$forkName $patchSetName"
    }

    private fun ApplyBaseFeaturePatches.configureApplyBaseFeaturePatches() {
        group = taskGroup
        description = "Applies $patchSetName base patches"

        input.set(baseDir)
        if (readOnly) {
            output.set(layout.cache.resolve(paperTaskOutput()))
        } else {
            output.set(outputDir)
        }

        base.set(baseDir)

        baseRef.set("base")
        patches.set(baseFeaturePatchDir.fileExists(project))
        baseRejectsDir.set(this@PatchingTasks.baseRejectsDir)
        identifier = "$forkName $patchSetName"
    }

    val applyBaseFeaturePatches = tasks.register<ApplyBaseFeaturePatches>("apply${namePart}BaseFeaturePatches") {
        configureApplyBaseFeaturePatches()
    }

    val applyFilePatches = tasks.register<ApplyFilePatches>("apply${namePart}FilePatches") {
        configureApplyFilePatches()
        dependsOn(applyBaseFeaturePatches)
        base.set(applyBaseFeaturePatches.flatMap { it.output })
    }

    val applyFilePatchesFuzzy = tasks.register<ApplyFilePatchesFuzzy>("apply${namePart}FilePatchesFuzzy") {
        configureApplyFilePatches()
        dependsOn(applyBaseFeaturePatches)
        base.set(applyBaseFeaturePatches.flatMap { it.output })
    }

    val applyFeaturePatches = tasks.register<ApplyFeaturePatches>("apply${namePart}FeaturePatches") {
        group = taskGroup
        description = "Applies $patchSetName feature patches"
        dependsOn(applyFilePatches)

        repo.set(outputDir)
        if (readOnly) {
            base.set(applyFilePatches.flatMap { it.repo })
        }
        patches.set(featurePatchDir.fileExists(project))
    }

    val applyPatches = tasks.register<Task>("apply${namePart}Patches") {
        group = taskGroup
        description = "Applies all $patchSetName patches"
        dependsOn(applyBaseFeaturePatches, applyFilePatches, applyFeaturePatches)
    }

    val rebuildBasePatchesName = "rebuild${namePart}BasePatches"
    val rebuildFilePatchesName = "rebuild${namePart}FilePatches"
    val fixupFilePatchesName = "fixup${namePart}FilePatches"
    val rebuildFeaturePatchesName = "rebuild${namePart}FeaturePatches"
    val rebuildPatchesName = "rebuild${namePart}Patches"

    init {
        if (!readOnly) {
            setupWritable()
        }
    }

    private fun setupWritable() {
        listOf(
            applyBaseFeaturePatches,
            applyFilePatches,
            applyFilePatchesFuzzy,
            applyFeaturePatches,
        ).forEach {
            it.configure {
                doNotTrackState("Always run when requested")
            }
        }

        val rebuildBasePatches = tasks.register<RebuildBaseGitPatches>(rebuildBasePatchesName) {
            group = taskGroup
            description = "Rebuilds $patchSetName base patches"

            base.set(baseDir) // correct task execution order -> unused
            inputDir.set(outputDir)
            patchDir.set(baseFeaturePatchDir)
            filterPatches.set(this@PatchingTasks.filterPatches)
        }
        val rebuildFilePatches = tasks.register<RebuildFilePatches>(rebuildFilePatchesName) {
            group = taskGroup
            description = "Rebuilds $patchSetName file patches"
            dependsOn(rebuildBasePatches)

            input.set(outputDir)
            patches.set(filePatchDir)
            gitFilePatches.set(this@PatchingTasks.gitFilePatches)
        }

        val fixupFilePatches = tasks.register<FixupFilePatches>(fixupFilePatchesName) {
            group = taskGroup
            description = "Puts the currently tracked source changes into the $patchSetName file patches commit"

            repo.set(outputDir)
            upstream.set("patchedBase")
        }

        val rebuildFeaturePatches = tasks.register<RebuildGitPatches>(rebuildFeaturePatchesName) {
            group = taskGroup
            description = "Rebuilds $patchSetName feature patches"
            dependsOn(rebuildFilePatches)

            inputDir.set(outputDir)
            patchDir.set(featurePatchDir)
            filterPatches.set(this@PatchingTasks.filterPatches)
        }

        val rebuildPatches = tasks.register<Task>(rebuildPatchesName) {
            group = taskGroup
            description = "Rebuilds all $patchSetName patches"
            dependsOn(rebuildBasePatches, rebuildFilePatches, rebuildFeaturePatches)
        }

        val applyOrMoveFilePatches = tasks.register<ApplyFilePatches>("applyOrMove${namePart}FilePatches") {
            configureApplyFilePatches()
            description = "Applies $patchSetName file patches as Git patches, moving any failed patches to the rejects dir. " +
                "Useful when updating to a new Minecraft version."
            gitFilePatches = true
            moveFailedGitPatchesToRejects = true
        }
    }
}
