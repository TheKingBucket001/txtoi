import java.util.Properties
import java.util.zip.ZipFile
import java.security.MessageDigest

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
// Production releases use R8. Pass -Pr8Candidate=false only for an explicit local
// fallback build; the release identity records the effective value.
val r8Candidate = providers.gradleProperty("r8Candidate").map { value ->
    require(value == "true" || value == "false") { "r8Candidate must be true or false" }
    value.toBoolean()
}.getOrElse(true)

// Content identity includes dirty production inputs, never signing files, QA or outputs.
// Normalize text line endings so identical Windows and Linux checkouts agree.
val probeIdentityRequired = listOf(
    "app/build.gradle.kts", "app/proguard-rules.pro", "build.gradle.kts", "settings.gradle.kts",
    "gradle.properties", "gradle/verification-metadata.xml", "gradle/wrapper/gradle-wrapper.properties",
    "gradle/wrapper/gradle-wrapper.jar", ".github/scripts/source_identity.py",
).map(rootProject::file)
probeIdentityRequired.forEach { check(it.isFile) { "Production identity input is missing: ${it.name}" } }
val probeIdentityOptional = rootProject.file("gradle/libs.versions.toml").let { if (it.isFile) listOf(it) else emptyList() }
val probeIdentityInputs = (rootProject.fileTree("app/src/main").files + probeIdentityRequired + probeIdentityOptional)
    .sortedBy { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath }
fun probeBuildIdentity(variant: String): String {
    val actualR8 = variant == "release" && r8Candidate
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("txtoi-probe-build-v1\n$variant\nr8=$actualR8\n".toByteArray(Charsets.UTF_8))
    for (input in probeIdentityInputs) {
        val relative = input.relativeTo(rootProject.projectDir).invariantSeparatorsPath
        val text = input.extension in setOf("java", "kt", "kts", "xml", "pro", "properties", "prop", "list", "txt", "json", "toml", "py")
        val bytes = if (text) input.readText(Charsets.UTF_8).replace("\r\n", "\n").replace("\r", "\n").toByteArray(Charsets.UTF_8)
            else input.readBytes()
        digest.update("$relative\n${bytes.size}\n".toByteArray(Charsets.UTF_8))
        digest.update(bytes)
        digest.update(0.toByte())
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

abstract class WriteProbeIdentity : DefaultTask() {
    @get:org.gradle.api.tasks.Input
    abstract val buildId: org.gradle.api.provider.Property<String>
    @get:org.gradle.api.tasks.Input
    abstract val variantName: org.gradle.api.provider.Property<String>
    @get:org.gradle.api.tasks.Input
    abstract val r8Enabled: org.gradle.api.provider.Property<Boolean>
    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDirectory: org.gradle.api.file.DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun writeIdentity() {
        val destination = outputDirectory.get().asFile.resolve("probe-build.json")
        destination.parentFile.mkdirs()
        destination.writeText("{\"schema\":1,\"variant\":\"${variantName.get()}\",\"r8Candidate\":${r8Enabled.get()},\"buildId\":\"${buildId.get()}\"}\n", Charsets.UTF_8)
    }
}

android {
    namespace = "io.github.selectionmenucontrol"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.selectionmenucontrol"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "0.8.1"
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
        debug {
            buildConfigField("String", "PROBE_BUILD_ID", "\"${probeBuildIdentity("debug")}\"")
        }
        release {
            buildConfigField("String", "PROBE_BUILD_ID", "\"${probeBuildIdentity("release")}\"")
            isMinifyEnabled = r8Candidate
            isShrinkResources = r8Candidate
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

androidComponents.onVariants { variant ->
    val identity = tasks.register<WriteProbeIdentity>("write${variant.name.replaceFirstChar { it.uppercaseChar() }}ProbeIdentity") {
        buildId.set(probeBuildIdentity(variant.name))
        variantName.set(variant.name)
        r8Enabled.set(variant.buildType == "release" && r8Candidate)
        outputDirectory.set(layout.buildDirectory.dir("generated/probeIdentity/${variant.name}"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(identity, WriteProbeIdentity::outputDirectory)
}

val rootProtocolTestShell = providers.gradleProperty("rootProtocolTestShell").orNull?.let { path ->
    rootProject.file(path).also { shell ->
        check(shell.isFile) { "rootProtocolTestShell must name an existing shell executable: $path" }
    }.absolutePath
}
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    // The selected shell is an explicit input so changing it reruns cached host tests.
    inputs.property("rootProtocolTestShell", rootProtocolTestShell.orEmpty())
    rootProtocolTestShell?.let { shell -> systemProperty("selectionmenu.rootProtocolTestShell", shell) }
}

tasks.matching { it.name == "packageRelease" }.configureEach {
    doFirst {
        check(releaseSigningReady) {
            "Release signing is not configured. Set signing.properties or ANDROID_KEYSTORE_* environment variables."
        }
    }
}

for (variant in listOf("debug", "release")) {
    val verificationTask = if (variant == "debug") "verifyModernXposedMetadata" else "verifyReleaseModernXposedMetadata"
    tasks.register(verificationTask) {
        dependsOn(if (variant == "debug") "assembleDebug" else "assembleRelease")
        doLast {
            val apk = layout.buildDirectory.file("outputs/apk/$variant/app-$variant.apk").get().asFile
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
}

dependencies {
    compileOnly("io.github.libxposed:api:101.0.1")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.3")
    testImplementation("junit:junit:4.13.2")
}
