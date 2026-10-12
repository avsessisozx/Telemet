import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions her derlemede sürüm numarasını artırır.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "io.github.avsessisozx.telemet"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.avsessisozx.telemet"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    // Her derlemenin aynı anahtarla imzalanması için depodaki sabit anahtar kullanılır.
    // Böylece yeni sürümler eskisini silmeden üzerine kurulabilir.
    // Bu anahtar yalnızca kişisel kullanım içindir; Play Store'a yüklemek için kullanılmamalıdır.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/telemet-debug.jks")
            storePassword = "telemet"
            keyAlias = "telemet"
            keyPassword = "telemet"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
