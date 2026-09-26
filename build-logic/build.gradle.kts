plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.gradle.ktlint)
    implementation(libs.gradle.level.headered)
    implementation(libs.gradle.shadow)
    implementation(libs.gradle.kotlin.dsl)
    implementation(kotlin("gradle-plugin", embeddedKotlinVersion))
    implementation(libs.gradle.plugin.publish)

    /*
    constraints {
        // spotless carries 1.9.10 and kotlin-dsl plugin has a strictly 2.4.0 constraint
        implementation("org.jetbrains.kotlin:kotlin-stdlib") {
            version {
                strictly(embeddedKotlinVersion)
            }
        }
    }
     */
}
