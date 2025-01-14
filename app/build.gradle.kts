import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.android.hilt)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.auto.license)
    kotlin(libs.plugins.kotlin.serialization.get().pluginId).version(libs.versions.kotlin)
}

android {
    namespace = "dev.chungjungsoo.gptmobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.chungjungsoo.gptmobile"
        minSdk = 31
        targetSdk = 35
        versionCode = 15
        versionName = "0.6.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField(
            "String", "HEXAGON_VERSION", "\"8G4\""
        )

        buildConfigField(
            "String", "QNN_SDK_VERSION", "\"29\""
        )

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DPOWERSERVE_WITH_QNN=ON",
                    "-DPOWERSERVE_ENABLE_LTO=ON",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DPOWERSERVE_EXCEPTION_ABORT=ON",
                    "-DPOWERSERVE_ANDROID_LOG=ON",
                    "-DPOWERSERVE_SERVER_MULTIMODEL=OFF"
                )
            }
        }
        ndk {
            // noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a")
        }
    }

    androidResources {
        @file:Suppress("UnstableApiUsage") // Incubating class
        generateLocaleConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            @file:Suppress("UnstableApiUsage")
            vcsInfo.include = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    applicationVariants.all {
        outputs.all {
            if (this is com.android.build.gradle.internal.api.ApkVariantOutputImpl) {
                val config          = project.android.defaultConfig
                val versionName     = config.versionName
                val formatter       = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
                val createTime      = LocalDateTime.now().format(formatter)
                val qnnSDKVersion   = config.buildConfigFields["QNN_SDK_VERSION"]
                val hexagonVersion  = config.buildConfigFields["HEXAGON_VERSION"]
                this.outputFileName = "${rootProject.name}_${this.name}_${versionName}_${createTime}_QNN${qnnSDKVersion}_${hexagonVersion}.apk"
            }
        }
    }
}

dependencies {
    // Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.viewmodel)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // SplashScreen
    implementation(libs.splashscreen)

    // DataStore
    implementation(libs.androidx.datastore)

    // Dependency Injection
    implementation(libs.hilt)
    implementation(libs.androidx.lifecycle.runtime.compose.android)
    ksp(libs.hilt.compiler)

    // Gemini SDK
    implementation(libs.gemini)

    // Ktor
    implementation(libs.ktor.content.negotiation)
    implementation(libs.ktor.core)
    implementation(libs.ktor.engine)
    implementation(libs.ktor.logging)
    implementation(libs.ktor.serialization)

    // License page UI
    implementation(libs.auto.license.core)
    implementation(libs.auto.license.ui)

    // Markdown
    implementation(libs.compose.markdown)

    // Navigation
    implementation(libs.hilt.navigation)
    implementation(libs.androidx.navigation)

    // OpenAI (Ktor required)
    implementation(libs.openai)

    // Room
    implementation(libs.room)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    // Serialization
    implementation(libs.kotlin.serialization)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

aboutLibraries {
    // Remove the "generated" timestamp to allow for reproducible builds
    excludeFields = arrayOf("generated")
}
