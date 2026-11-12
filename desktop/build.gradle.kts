import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

group = "com.camera2pc"
version = "1.0.0"

dependencies {
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.mp.material3)
    implementation(libs.kotlinx.coroutines.swing)
    // Своя строка заголовка окна на JetBrains Runtime, как в Pier.doon.
    implementation("org.jetbrains.runtime:jbr-api:1.9.0")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

val jbrDir: String? = rootProject.file("local.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) }.getProperty("jbr.dir") }
    ?.takeIf { file(it).exists() }

compose.desktop {
    application {
        mainClass = "com.camera2pc.MainKt"
        jbrDir?.let { javaHome = it }
        jvmArgs += listOf(
            "-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED",
            "-XX:+UseSerialGC", "-Xms32m", "-Xmx512m", "-XX:+UseCompactObjectHeaders",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Oko"
            packageVersion = project.version.toString()
            description = "Phone camera as a webcam"
            vendor = "Oko"
            modules("java.instrument", "java.management", "jdk.unsupported", "java.logging", "jdk.accessibility")
            windows {
                iconFile.set(project.file("icons/oko.ico"))
            }
        }
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}
