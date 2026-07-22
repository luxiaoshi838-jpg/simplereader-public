extensions.configure<com.android.build.api.dsl.ApplicationExtension>("android") {
    buildTypes.getByName("release").apply {
        signingConfig = signingConfigs.getByName("debug")
        isMinifyEnabled = true
        isShrinkResources = true
    }
}
