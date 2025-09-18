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

package io.papermc.paperweight.patcher

import io.papermc.paperweight.core.taskcontainers.UpstreamConfigTasks
import io.papermc.paperweight.core.tasks.CheckoutRepo
import io.papermc.paperweight.core.tasks.GeneratePatches
import io.papermc.paperweight.core.tasks.GenerateSources
import io.papermc.paperweight.core.tasks.PrepareForPatchGeneration
import io.papermc.paperweight.core.tasks.RunNestedBuild
import io.papermc.paperweight.patcher.extension.PaperweightPatcherExtension
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.*
import org.gradle.kotlin.dsl.support.uppercaseFirstChar

abstract class PaperweightPatcher : Plugin<Project> {

    override fun apply(target: Project) {
        Git.checkForGit(target.providers)
        printId<PaperweightPatcher>("paperweight-patcher", target.gradle)

        val patcher = target.extensions.create(PAPERWEIGHT_EXTENSION, PaperweightPatcherExtension::class)

        target.tasks.register<Delete>("cleanCache") {
            group = GENERAL_TASK_GROUP
            description = "Delete the project setup cache and task outputs."
            delete(target.layout.cache)
        }

        target.configurations.register(JST_CONFIG) {
            defaultDependencies {
                // add(project.dependencies.create("net.neoforged.jst:jst-cli-bundle:${LibraryVersions.JST}"))
                add(target.dependencies.create("io.canvasmc.jst:jst-cli-bundle:${LibraryVersions.JST}"))
            }
        }

        target.afterEvaluate {
            repositories {
                maven(patcher.jstRepo) {
                    name = JST_REPO_NAME
                    content { onlyForConfigurations(JST_CONFIG) }
                }
            }
            afterEvaluate(patcher)
        }
    }

    private fun Project.afterEvaluate(patcher: PaperweightPatcherExtension) {
        val workDirFromProp = upstreamsDirectory()

        val applyForDownstream = tasks.register("applyForDownstream") {
            group = INTERNAL_TASK_GROUP
        }

        patcher.upstreams.forEach { upstream ->
            val checkoutTask = tasks.register<CheckoutRepo>("checkout${upstream.name.capitalized()}Repo") {
                repoName.set(upstream.name)
                url.set(upstream.repo)
                ref.set(upstream.ref)
                workDir.set(workDirFromProp)
            }

            val applyUpstream = tasks.register<RunNestedBuild>("applyUpstream") {
                projectDir.set(checkoutTask.flatMap { it.outputDir })
                tasks.add("applyForDownstream")
            }

            val upstreamConfigTasks = UpstreamConfigTasks(
                project,
                project.name,
                upstream,
                checkoutTask.flatMap { it.outputDir },
                !isBaseExecution,
                "patching",
                patcher.gitFilePatches,
                patcher.filterPatches,
                applyUpstream,
                null,
            )

            upstreamConfigTasks.setupAggregateTasks(
                upstream.name.capitalized(),
                upstream.directoryPatchSets.names.joinToString(", "),
                upstream.directoryPatchSets.names.joinToString(", ") + ", ${upstream.name} single file"
            )
            applyForDownstream { dependsOn("apply${upstream.name.capitalized()}Patches") }
            tasks.register<RunNestedBuild>("applyAllPatches") {
                group = "patching"
                val depend = "apply${upstream.name.capitalized()}Patches"
                tasks.addAll("applyAllServerPatches")
                description = "Applies all patches defined in the paperweight-patcher project and the server project. " +
                    "(equivalent to running '$depend' and then '${tasks.get().single()}' in a second Gradle invocation)"
                projectDir.set(layout.projectDirectory)
                dependsOn(depend)
            }
        }
        patcher.additionalUpstreams.forEach { upstream ->
            val checkoutTask = tasks.register<CheckoutRepo>("checkout${upstream.name.capitalized()}RepoForGeneration") {
                repoName.set(upstream.name)
                url.set(upstream.repo)
                ref.set(upstream.ref)
                workDir.set(workDirFromProp)
            }

            val applyAdditionalUpstream = tasks.register<RunNestedBuild>("apply${upstream.name.capitalized()}ForGeneration") {
                projectDir.set(checkoutTask.flatMap { it.outputDir })
                tasks.add("applyAllPatches")
            }

            val depend: List<TaskProvider<PrepareForPatchGeneration>> = upstream.patchGenerationConfig.inputConfig.map { input ->
                val name = if (input.name == "minecraft") {
                    "Minecraft"
                } else {
                    input.name.split("-").joinToString("") {
                        it.uppercaseFirstChar()
                    }
                }
                tasks.register<PrepareForPatchGeneration>("prepare${upstream.name.capitalized()}${name}ForPatchGeneration") {
                    group = INTERNAL_TASK_GROUP
                    dependsOn(applyAdditionalUpstream)
                    forkName.set(upstream.name)
                    repoName.set(input.name)
                    workDir.set(workDirFromProp)
                    atFile.set(input.additionalAts.fileExists(project))
                    ats.jst.from(project.configurations.named(JST_CONFIG))
                    additionalPatch.set(input.additionalPatch.fileExists(project))
                }
            }

            val sourceGenDepend: List<TaskProvider<GenerateSources>> = upstream.sourceGenerationConfig.generationConfig.map { input ->
                val name = input.name.split("-").joinToString("") {
                    it.uppercaseFirstChar()
                }
                val outputType = input.name.substringAfter("-")
                val projectDir: Provider<Directory> = project.provider { project.layout.projectDirectory.dir("${rootProject.name}-$outputType") }

                tasks.register<GenerateSources>("generate${name}Sources") {
                    group = "source generation"
                    description = "Generates sources from ${input.name}"
                    dependsOn(applyAdditionalUpstream)
                    forkName.set(upstream.name)
                    forkUrl.set(upstream.repo)
                    inputFrom.set(input.name)
                    commitHash.set(upstream.ref)
                    workDir.set(workDirFromProp)
                    atFile.set(input.additionalAts.fileExists(project))
                    ats.jst.from(project.configurations.named(JST_CONFIG))
                    additionalPatch.set(input.additionalPatch.fileExists(project))
                    generateSources.set(input.generateSources.orElse(true))
                    generateResources.set(input.generateResources.orElse(true))
                    generateTestSources.set(input.generateTestSources.orElse(true))
                    generateTestResources.set(input.generateTestResources.orElse(true))
                    sourceOutput.set(
                        input.sourcesOutputDir.orElse(projectDir.map { it.dir("src/generated/main/java") })
                    )
                    resourceOutput.set(
                        input.resourcesOutputDir.orElse(projectDir.map { it.dir("src/generated/main/resources") })
                    )
                    testSourceOutput.set(
                        input.testSourcesOutputDir.orElse(projectDir.map { it.dir("src/generated/test/java") })
                    )
                    testResourceOutput.set(
                        input.testResourcesOutputDir.orElse(projectDir.map { it.dir("src/generated/test/resources") })
                    )
                }
            }
            val apiProject: Provider<Directory> = project.provider { project.layout.projectDirectory.dir("${rootProject.name}-api") }
            val serverProject: Provider<Directory> = project.provider { project.layout.projectDirectory.dir("${rootProject.name}-server") }

            val genPatches = tasks.register<GeneratePatches>("generate${upstream.name.capitalized()}Patches") {
                dependsOn(depend.map { it })
                group = "patch generation"
                description = "Condenses ${upstream.name} changes for every inputConfig into a corresponding patch file"
                forkName.set(upstream.name)
                forkUrl.set(upstream.repo)
                commitHash.set(upstream.ref)
                inputFrom.set(upstream.patchGenerationConfig.inputConfig.joinToString(",") { it.name })
                preparedSource.from(depend.map { it.flatMap { t -> t.outputDir } })
                patchesDirOutput.set(upstream.patchGenerationConfig.patchesDirOutput)
                serverProjectDir.set(serverProject)
                apiProjectDir.set(apiProject)
                outputDir.set(upstream.patchGenerationConfig.outputDir)
            }

            val genSources = tasks.register("generate${upstream.name.capitalized()}Sources") {
                group = "source generation"
                description = "Generates sources from ${upstream.name}"
            }

            val generate = tasks.register("generate${upstream.name.capitalized()}") {
                group = "generation"
                description = "Generates patches and sources from ${upstream.name}"
            }
            genSources { dependsOn(sourceGenDepend.map { it }) }
            generate { dependsOn(genPatches, genSources) }
        }
    }
}
