val hideBookshelfFormatLabels = tasks.register("hideBookshelfFormatLabels") {
    doLast {
        val mainFile = file("src/main/java/com/simplereader/app/ui/MainActivity.kt")
        var source = mainFile.readText()

        source = source
            .replace("\\n\\n\\n\${format.uppercase()}", "")
            .replace("\\n\\n\${format.uppercase()}", "")
            .replace("\\n\\n\${book.format}", "")

        mainFile.writeText(source)
    }
}

hideBookshelfFormatLabels.configure {
    dependsOn("applyRuntimeUiPatches")
    mustRunAfter("applyRuntimeUiPatches")
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(hideBookshelfFormatLabels)
}
