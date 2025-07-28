tasks.register("printVersion") {
    doFirst {
        println(version)
    }
}

tasks.wrapper {
    distributionType = Wrapper.DistributionType.ALL
}

tasks.register("publishAllPublicationsToCanvasRepository") {
    dependsOn(":paperweight-core:publishAllPublicationsToCentralRepository")
    dependsOn(":paperweight-userdev:publishAllPublicationsToCentralRepository")
}
