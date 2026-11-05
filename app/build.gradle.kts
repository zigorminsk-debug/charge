import java.io.File
import java.io.FileInputStream
import java.util.Properties
import java.util.concurrent.TimeUnit

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Корень репозитория (вычисляем один раз, чтобы использовать в функциях скрипта)
val repoDir: File = rootProject.projectDir

fun gitOutput(vararg args: String): String? = try {
    val process = ProcessBuilder(listOf("git") + args)
        .directory(repoDir)
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0) text else null
} catch (e: Exception) {
    null
}

/*
 * versionCode = число коммитов в истории: оно монотонно растёт, поэтому каждая
 * новая сборка ставится поверх предыдущей (с той же подписью).
 * CI клонирует репозиторий с fetch-depth: 0, иначе счётчик будет неверным.
 */
val versionCodeValue: Int = System.getenv("CI_VERSION_CODE")?.toIntOrNull()
    ?: gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull()
    ?: 1

// Постоянный ключ подписи лежит в репозитории осознанно (см. README, раздел про безопасность)
val signingProps = Properties()
val signingPropsFile: File = rootProject.file("signing/signing.properties")
if (signingPropsFile.isFile) {
    FileInputStream(signingPropsFile).use { signingProps.load(it) }
}
val releaseKeystore: File = rootProject.file("signing/charge-overlay.p12")
val hasReleaseSigning: Boolean =
    releaseKeystore.isFile && !signingProps.getProperty("storePassword").isNullOrEmpty()

android {
    namespace = "by.charge.overlay"
    compileSdk = 34

    defaultConfig {
        applicationId = "by.charge.overlay"
        minSdk = 24
        targetSdk = 34
        versionCode = versionCodeValue
        versionName = "1.$versionCodeValue"
    }

    signingConfigs {
        create("chargeRelease") {
            if (hasReleaseSigning) {
                storeFile = releaseKeystore
                storeType = "PKCS12"
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("chargeRelease")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Карта OpenStreetMap без API-ключа (тайлы openstreetmap.org)
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    testImplementation("junit:junit:4.13.2")
    // Настоящий org.json для JVM-тестов (заглушка из android.jar не работает в unit-тестах)
    testImplementation("org.json:json:20240303")
}
