import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import org.gradle.plugins.signing.SigningExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
    signing
}

repositories {
    mavenLocal()
    google()
    mavenCentral()
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/revanced/registry")
        credentials {
            username = providers.gradleProperty("gpr.user")
                .getOrElse(System.getenv("GITHUB_ACTOR"))
            password =
                providers.gradleProperty("gpr.key").getOrElse(System.getenv("GITHUB_TOKEN"))
        }
    }
}

android {
    val packageName = "dev.syrupderg.reflect" 
    namespace = packageName
    defaultConfig {
        applicationId = packageName
    }

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        compileSdk = 36
        versionName = version.toString()
        versionCode = versionName!!.filter { it.isDigit() }.toInt()
    }

    buildTypes {
        getByName("release") {
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            val keystoreFile = file("${rootDir}/keystore.jks")
            signingConfig =
                if (keystoreFile.exists()) {
                    signingConfigs.create("release") {
                        storeFile = keystoreFile
                        storePassword = System.getenv("KEYSTORE_PASSWORD")
                        keyAlias = System.getenv("KEYSTORE_ENTRY_ALIAS")
                        keyPassword = System.getenv("KEYSTORE_ENTRY_PASSWORD")
                    }
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    applicationVariants.all {
        outputs.all {
            this as ApkVariantOutputImpl
            outputFileName = "reflect-$version.apk"
        }
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexplicit-backing-fields",
            "-Xcontext-parameters",
        )
    }
}

dependencies {
    "compileOnly"(libs.manager.api)

    implementation(libs.arsclib)

    implementation(libs.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
}

tasks.register("assembleReleaseSignApk") {
    dependsOn("assembleRelease")

    val apk =
        layout.buildDirectory.file("outputs/apk/release/reflect-$version.apk")

    inputs.file(apk).withPropertyName("input")
    outputs.file(apk.map { it.asFile.resolveSibling("${it.asFile.name}.asc") })

    doLast {
        project.configure<SigningExtension> {
            useGpgCmd()
            sign(*inputs.files.files.toTypedArray())
        }
    }
}

tasks.named("publish") {
    dependsOn("assembleReleaseSignApk")
}