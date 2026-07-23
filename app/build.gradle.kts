plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

val permanentKeystorePath = System.getenv("SIMPLEREADER_KEYSTORE_PATH")
val permanentKeystorePassword = System.getenv("SIMPLEREADER_KEYSTORE_PASSWORD")
val permanentKeyAlias = System.getenv("SIMPLEREADER_KEY_ALIAS")
val permanentKeyPassword = System.getenv("SIMPLEREADER_KEY_PASSWORD")
val permanentSigningConfigured = listOf(
    permanentKeystorePath,
    permanentKeystorePassword,
    permanentKeyAlias,
    permanentKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.simplereader.app"
    compileSdk = 35

    val generatedVersionCode = (System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2026202001")
        .toIntOrNull()
        ?: 2026202001
    val generatedVersionName = System.getenv("SIMPLE_READER_VERSION_NAME") ?: "2026.07.21.1"

    defaultConfig {
        applicationId = "com.simplereader.app"
        minSdk = 26
        targetSdk = 35
        versionCode = generatedVersionCode
        versionName = generatedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf("room.schemaLocation" to "$projectDir/schemas")
            }
        }
    }

    signingConfigs {
        if (permanentSigningConfigured) {
            create("permanentV2") {
                storeFile = file(requireNotNull(permanentKeystorePath))
                storePassword = requireNotNull(permanentKeystorePassword)
                keyAlias = requireNotNull(permanentKeyAlias)
                keyPassword = requireNotNull(permanentKeyPassword)
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            if (permanentSigningConfigured) {
                signingConfig = signingConfigs.getByName("permanentV2")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST"
            )
        }
    }
}

configurations.configureEach {
    exclude(group = "org.apache.tika")
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.2")

    implementation("com.google.code.gson:gson:2.10.1")

    // Mature public parsers: epub4j follows OPF spine order; jchmlib handles CHM TOC and encoding.
    implementation("io.documentnode:epub4j-core:4.2.3") {
        exclude(group = "xmlpull")
    }
    implementation("com.github.albfernandez:juniversalchardet:2.5.0")
    implementation("com.github.chimenchen:jchmlib:v0.5.4")
    implementation("com.sorrowblue.sevenzipjbinding:7-Zip-JBinding-4Android:16.02-2.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("org.robolectric:robolectric:4.16")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
