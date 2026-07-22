pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

gradle.beforeProject {
    if (path == ":app") {
        apply(from = file("scripts/reader_bookmark_continuous_scroll.gradle.kts"))
        apply(from = file("scripts/bookshelf_format_preview.gradle.kts"))
        apply(from = file("scripts/backup_relink_migration.gradle.kts"))
        apply(from = file("scripts/reader_scroll_resource_sanitize.gradle.kts"))
        apply(from = file("scripts/reader_search_paging.gradle.kts"))
        apply(from = file("scripts/release_stability_tests.gradle.kts"))
    }
}

rootProject.name = "SimpleReader"
include(":app")
