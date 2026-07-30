import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

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

val generatedCoverTextureResDir = layout.buildDirectory.dir("generated/coverTextureRes")
val prepareCoverTexture by tasks.registering {
    val source = layout.projectDirectory.file("src/main/coverTexture/paper_texture_v577.png.b64")
    val target = generatedCoverTextureResDir.map {
        it.file("drawable-nodpi/paper_texture_v577.png")
    }
    inputs.file(source)
    outputs.file(target)

    doLast {
        val output = target.get().asFile
        output.parentFile.mkdirs()
        output.writeBytes(Base64.getMimeDecoder().decode(source.asFile.readText()))
    }
}

val generatedEpubAssetsDir = layout.buildDirectory.dir("generated/epubjsAssets")
val prepareEpubJsAssets by tasks.registering {
    val epubTarget = generatedEpubAssetsDir.map { it.file("epubjs/epub.min.js") }
    val zipTarget = generatedEpubAssetsDir.map { it.file("epubjs/jszip.min.js") }
    outputs.files(epubTarget, zipTarget)

    doLast {
        fun downloadPinned(url: String, destination: File, minimumBytes: Long) {
            if (destination.isFile && destination.length() >= minimumBytes) return
            destination.parentFile.mkdirs()
            val temporary = File(destination.parentFile, destination.name + ".part")
            temporary.delete()
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "SimpleReader-Android-Build")
            try {
                connection.inputStream.use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                require(temporary.length() >= minimumBytes) {
                    "Downloaded asset is incomplete: ${destination.name} (${temporary.length()} bytes)"
                }
                temporary.copyTo(destination, overwrite = true)
            } finally {
                connection.disconnect()
                temporary.delete()
            }
        }

        downloadPinned(
            "https://cdn.jsdelivr.net/npm/epubjs@0.3.93/dist/epub.min.js",
            epubTarget.get().asFile,
            200_000
        )
        downloadPinned(
            "https://cdn.jsdelivr.net/npm/jszip@3.10.1/dist/jszip.min.js",
            zipTarget.get().asFile,
            50_000
        )
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareEpubJsAssets, prepareCoverTexture)
}

android {
    namespace = "com.simplereader.app"
    compileSdk = 35

    val generatedVersionCode = (System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2026202801")
        .toIntOrNull()
        ?: 2026202801
    val generatedVersionName = System.getenv("SIMPLE_READER_VERSION_NAME") ?: "2026.07.28.1"

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
        isCoreLibraryDesugaringEnabled = true
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
        getByName("main").assets.srcDir(generatedEpubAssetsDir)
        getByName("main").res.srcDir(generatedCoverTextureResDir)
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
    exclude(group = "xmlpull", module = "xmlpull")
    exclude(group = "net.sf.kxml", module = "kxml2")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.2")

    implementation("com.google.code.gson:gson:2.10.1")

    implementation("org.readium.kotlin-toolkit:readium-shared:3.0.0")
    implementation("org.readium.kotlin-toolkit:readium-streamer:3.0.0")
    implementation("org.readium.kotlin-toolkit:readium-navigator:3.0.0")

    implementation("io.documentnode:epub4j-core:4.2.3")
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
