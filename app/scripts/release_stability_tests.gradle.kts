afterEvaluate {
    tasks.matching { it.name == "assembleRelease" }.configureEach {
        dependsOn("testReleaseUnitTest")
    }
}
