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

package io.papermc.paperweight.core.extension

import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.domainObjectContainer
import org.gradle.kotlin.dsl.newInstance

abstract class AdditionalUpstreamConfig @Inject constructor(
    private val configName: String,
    objects: ObjectFactory,
) : Named {
    override fun getName(): String {
        return configName
    }

    abstract val repo: Property<String>
    abstract val ref: Property<String>
    val sourceGenerationConfig: SourceGenerationConfig = objects.newInstance<SourceGenerationConfig>()
    val patchGenerationConfig: PatchGenerationConfig = objects.newInstance<PatchGenerationConfig>()

    fun sourceGeneration(op: Action<SourceGenerationConfig>) {
        op.execute(sourceGenerationConfig)
    }

    fun patchGeneration(op: Action<PatchGenerationConfig>) {
        op.execute(patchGenerationConfig)
    }

    @Suppress("UNUSED_PARAMETER")
    abstract class SourceGenerationConfig @Inject constructor(
        objects: ObjectFactory,
    ) {
        val generationConfig: NamedDomainObjectContainer<GenerationConfig> = objects.domainObjectContainer(
            GenerationConfig::class
        ) { name -> objects.newInstance(name, objects) }

        @Suppress("UNUSED_PARAMETER")
        abstract class GenerationConfig @Inject constructor(
            private val setName: String,
            objects: ObjectFactory,
        ) : Named {
            override fun getName(): String {
                return setName
            }

            abstract val generateSources: Property<Boolean>
            abstract val generateResources: Property<Boolean>
            abstract val generateTestSources: Property<Boolean>
            abstract val generateTestResources: Property<Boolean>

            abstract val sourcesOutputDir: DirectoryProperty
            abstract val resourcesOutputDir: DirectoryProperty
            abstract val testSourcesOutputDir: DirectoryProperty
            abstract val testResourcesOutputDir: DirectoryProperty

            abstract val additionalAts: RegularFileProperty

            abstract val additionalPatch: RegularFileProperty
        }
    }

    abstract class PatchGenerationConfig @Inject constructor(
        objects: ObjectFactory
    ) {
        abstract val outputDir: DirectoryProperty

        abstract val patchesDirOutput: Property<Boolean>

        val inputConfig: NamedDomainObjectContainer<InputConfig> = objects.domainObjectContainer(
            InputConfig::class
        ) { name -> objects.newInstance(name, objects) }

        @Suppress("UNUSED_PARAMETER")
        abstract class InputConfig @Inject constructor(
            private val setName: String,
            objects: ObjectFactory,
        ) : Named {
            override fun getName(): String {
                return setName
            }

            abstract val additionalAts: RegularFileProperty

            abstract val additionalPatch: RegularFileProperty
        }
    }

    fun github(owner: String, repo: String): String = "https://github.com/$owner/$repo.git"
}
