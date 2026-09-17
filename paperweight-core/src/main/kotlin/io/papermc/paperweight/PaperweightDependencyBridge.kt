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

package io.papermc.paperweight

import io.papermc.paperweight.util.constants.JST_CLASSPATH_ATTRIBUTE
import io.papermc.paperweight.util.constants.JST_CLASSPATH_CONFIG
import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.plugins.JavaPlugin

abstract class PaperweightDependencyBridge : Plugin<Project> {

    @get:Inject
    abstract val configurations: ConfigurationContainer

    override fun apply(target: Project) {
        configurations.consumable(JST_CLASSPATH_CONFIG) {
            attributes {
                attribute(JST_CLASSPATH_ATTRIBUTE, true)
            }
            extendsFrom(configurations.named(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME))
        }
    }
    // TODO
}
