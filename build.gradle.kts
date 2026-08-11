import java.util.zip.ZipFile
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
}

private fun ByteArray.containsByteSequence(needle: ByteArray): Boolean {
    require(needle.isNotEmpty()) { "Needle must not be empty" }
    if (needle.size > size) return false
    for (start in 0..size - needle.size) {
        var matches = true
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) {
                matches = false
                break
            }
        }
        if (matches) return true
    }
    return false
}

android {
    namespace = "com.ai.assistance.operit.terminal"
    compileSdk = 37
    ndkVersion = providers.gradleProperty("kiyori.android.ndkVersion").get()

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories += "src/main/jniLibs"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    buildFeatures {
        aidl = true
        compose = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

val minaSanitizerInput =
    configurations.create("minaSanitizerInput") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }
val minaInputJar = minaSanitizerInput.elements.map { elements -> elements.single().asFile }
val minaUnsafePrefix = "org/apache/mina/filter/ssl/BogusTrustManagerFactory"
val minaUnsafeEntries =
    setOf(
        "$minaUnsafePrefix.class",
        "$minaUnsafePrefix\$1.class",
        "$minaUnsafePrefix\$2.class",
        "$minaUnsafePrefix\$BogusTrustManagerFactorySpi.class",
    )
val sanitizeMinaCore =
    tasks.register<Zip>("sanitizeMinaCore") {
        group = "build setup"
        description = "Removes MINA's closed, unused trust-all TLS helper classes."
        inputs.file(minaInputJar)
        archiveFileName.set("mina-core-safe-${libs.versions.mina.get()}.jar")
        destinationDirectory.set(layout.buildDirectory.dir("generated/sanitized-dependencies"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true

        doFirst {
            val inputJar = minaInputJar.get()
            ZipFile(inputJar).use { archive ->
                val entries = archive.entries().asSequence().filterNot { it.isDirectory }.toList()
                val entryNames = entries.mapTo(mutableSetOf()) { it.name }
                val missingUnsafeEntries = minaUnsafeEntries - entryNames
                check(missingUnsafeEntries.isEmpty()) {
                    "MINA sanitizer input changed; expected insecure entries are missing from " +
                        "${inputJar.name}: ${missingUnsafeEntries.sorted()}"
                }

                val unsafePrefixBytes = minaUnsafePrefix.toByteArray(Charsets.UTF_8)
                val unexpectedReferences =
                    entries.asSequence()
                        .filter { entry ->
                            entry.name.endsWith(".class") &&
                                entry.name != "module-info.class" &&
                                !entry.name.endsWith("/module-info.class") &&
                                !entry.name.startsWith(minaUnsafePrefix)
                        }
                        .filter { entry ->
                            archive.getInputStream(entry).use { bytecode ->
                                bytecode.readBytes().containsByteSequence(unsafePrefixBytes)
                            }
                        }
                        .map { it.name }
                        .toList()
                check(unexpectedReferences.isEmpty()) {
                    "Cannot remove MINA trust-all helpers because retained classes reference them: " +
                        unexpectedReferences.joinToString()
                }
            }
        }

        from(minaInputJar.map { inputJar -> zipTree(inputJar) }) {
            exclude(
                "$minaUnsafePrefix**",
                "module-info.class",
                "META-INF/versions/*/module-info.class",
                "META-INF/*.SF",
                "META-INF/*.RSA",
                "META-INF/*.DSA",
                "META-INF/*.EC",
            )
        }

        doLast {
            ZipFile(archiveFile.get().asFile).use { archive ->
                val residualEntries =
                    archive.entries().asSequence()
                        .map { it.name }
                        .filter { it.startsWith(minaUnsafePrefix) }
                        .toList()
                check(residualEntries.isEmpty()) {
                    "Sanitized MINA JAR still contains trust-all helpers: " +
                        residualEntries.joinToString()
                }
            }
        }
    }

dependencies {
    add(minaSanitizerInput.name, libs.mina.core)

    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    implementation(libs.compose.animation.core)
    implementation(libs.navigation.compose)
    implementation(libs.androidx.ui.graphics.android)
    implementation(libs.androidx.runtime.android)
    implementation(libs.androidx.ui.text.android)
    implementation(libs.androidx.animation.android)
    implementation(libs.androidx.ui.android)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(libs.junit)
    
    // Kotlin Serialization
    implementation(libs.kotlinx.serialization)
    implementation(libs.kotlin.parcelize.runtime)
    
    // SSH 依赖
    implementation(libs.jsch)
    
    // FTP服务器依赖
    implementation(libs.ftpserver.core) {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
        exclude(group = "org.apache.mina", module = "mina-core")
    }
    implementation(libs.ftpserver.ftplet.api)
    implementation(files(sanitizeMinaCore.flatMap { it.archiveFile }).builtBy(sanitizeMinaCore))
    
    // SSHD服务器依赖
    implementation("org.apache.sshd:sshd-core:2.10.0") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    implementation("org.apache.sshd:sshd-sftp:2.10.0") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    // BouncyCastle for SSHD on Android (avoids JMX issues)
    implementation(libs.bouncycastle.bcprov)
}
