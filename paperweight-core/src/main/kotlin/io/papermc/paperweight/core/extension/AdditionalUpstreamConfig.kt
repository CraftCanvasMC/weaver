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

import io.papermc.paperweight.util.*
import javax.annotation.Nullable
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.*
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
    val patchGenerationConfig: PatchGenerationConfig = objects.newInstance<PatchGenerationConfig>()

    fun patchGeneration(op: Action<PatchGenerationConfig>) {
        op.execute(patchGenerationConfig)
    }

    abstract class PatchGenerationConfig @Inject constructor(
        objects: ObjectFactory
    ) {
        abstract val patchDirOutput: Property<Boolean>
        abstract val inputFrom: ListProperty<String>
        abstract val outputDir: DirectoryProperty

        val repoConfig: NamedDomainObjectContainer<RepoConfig> = objects.domainObjectContainer(
            RepoConfig::class
        ) { name -> objects.newInstance(name, objects) }

        abstract class RepoConfig @Inject constructor(
            private val setName: String,
            objects: ObjectFactory,
        ) : Named {
            override fun getName(): String {
                return setName
            }

            @get:Nullable
            abstract val additionalAts: RegularFileProperty

            @get:Nullable
            abstract val additionalPatch: RegularFileProperty
        }
    }

    fun github(owner: String, repo: String): String = "https://github.com/$owner/$repo.git"
}
