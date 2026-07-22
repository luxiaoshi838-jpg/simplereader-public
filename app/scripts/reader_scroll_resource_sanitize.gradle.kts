val sanitizeReaderScrollResources = tasks.register("sanitizeReaderScrollResources") {
    doLast {
        val layoutFile = file("src/main/res/layout/activity_reader.xml")
        val source = layoutFile.readText()
            .replace("        android:smoothScrollingEnabled=\"true\">", "        >")
        layoutFile.writeText(source)
    }
}

sanitizeReaderScrollResources.configure {
    mustRunAfter("applyReaderBookmarkAndContinuousScrollFixes")
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(sanitizeReaderScrollResources)
}
