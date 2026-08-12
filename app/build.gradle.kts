import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingProperties = Properties()
val signingPropertiesFile = rootProject.file("signing.properties")
if (signingPropertiesFile.isFile) {
    signingPropertiesFile.inputStream().use(signingProperties::load)
}
val releaseStoreFile = signingProperties.getProperty("storeFile") ?: System.getenv("ANDROID_KEYSTORE_FILE")
val releaseStorePassword = signingProperties.getProperty("storePassword") ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingProperties.getProperty("keyAlias") ?: System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword = signingProperties.getProperty("keyPassword") ?: System.getenv("ANDROID_KEY_PASSWORD")
val releaseSigningReady = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "io.github.selectionmenucontrol"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.selectionmenucontrol"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.3.7"
    }

    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        disable += "AndroidGradlePluginVersion"
        disable += "GradleDependency"
        disable += "OldTargetApi"
    }
}

tasks.matching { it.name == "packageRelease" }.configureEach {
    doFirst {
        check(releaseSigningReady) {
            "Release signing is not configured. Set signing.properties or ANDROID_KEYSTORE_* environment variables."
        }
    }
}

tasks.register("verifyModernXposedMetadata") {
    dependsOn("assembleDebug")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        ZipFile(apk).use { archive ->
            val moduleProperties = archive.getEntry("META-INF/xposed/module.prop")
                ?: error("Modern module.prop is missing from the APK")
            val scope = archive.getEntry("META-INF/xposed/scope.list")
                ?: error("Modern scope.list is missing from the APK")
            val entryPoint = archive.getEntry("META-INF/xposed/java_init.list")
                ?: error("Modern java_init.list is missing from the APK")
            check(archive.getEntry("assets/xposed_init") == null) { "Legacy Xposed entry must not be packaged" }
            check(archive.getInputStream(moduleProperties).bufferedReader().readText().contains("staticScope=true")) {
                "Module must declare staticScope=true"
            }
            check(archive.getInputStream(scope).bufferedReader().readText().trim() == "system") {
                "Static scope must only contain system"
            }
            check(archive.getInputStream(entryPoint).bufferedReader().readText().trim()
                    == "io.github.selectionmenucontrol.SelectionMenuModule") {
                "Modern module entry point is incorrect"
            }
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:101.0.1")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.3")
}
