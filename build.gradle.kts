import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("com.android.application") version "8.1.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.10" apply false
}

subprojects {
    configurations.configureEach {
        // The old native 7-Zip CHM path is no longer used. CHM is provided by
        // the public pure-Java jchmlib dependency instead.
        exclude(group = "com.sorrowblue.sevenzipjbinding")
    }

    plugins.withId("com.android.application") {
        extensions.configure<ApplicationExtension> {
            testBuildType = "release"
        }
        dependencies.add("androidTestImplementation", "androidx.test:core-ktx:1.5.0")
        dependencies.add("androidTestImplementation", "junit:junit:4.13.2")
    }
}
