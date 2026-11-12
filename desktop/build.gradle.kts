import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

group = "com.camera2pc"
version = "1.1.0"

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

// adb едет вместе с Oko: на обычном ПК Android SDK нет. Берём из platform-tools Google только
// adb.exe с двумя его DLL; Compose кладёт их в app/resources (compose.application.resources.dir).
// Версия зафиксирована, архив сверяется по SHA-256 (SHA-1 совпадает с repository2-3.xml Google).
val adbVersion = "37.0.1"
val adbSha256 = "45f4d63113e895ebde0c90f194099a4676b6ac653bd28d54314a9e022bbc1a99"
val adbResources = layout.buildDirectory.dir("adb")
val adbZip = layout.buildDirectory.file("downloads/platform-tools_r$adbVersion-win.zip")
val downloadAdb = tasks.register("downloadAdb") {
    val zip = adbZip
    val url = "https://dl.google.com/android/repository/platform-tools_r$adbVersion-win.zip"
    val sha = adbSha256
    outputs.file(zip)
    onlyIf { !zip.get().asFile.exists() }
    doLast {
        val f = zip.get().asFile
        f.parentFile.mkdirs()
        val bytes = URI(url).toURL().openStream().use { it.readBytes() }
        val got = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(got == sha) { "platform-tools: SHA-256 $got, ожидался $sha" }
        f.writeBytes(bytes)
    }
}
val unpackAdb = tasks.register<Sync>("unpackAdb") {
    dependsOn(downloadAdb)
    from(zipTree(adbZip)) {
        include("platform-tools/adb.exe", "platform-tools/AdbWinApi.dll", "platform-tools/AdbWinUsbApi.dll", "platform-tools/NOTICE.txt")
    }
    into(adbResources.map { it.dir("windows") })
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(unpackAdb) }

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
            "-Doko.version=${project.version}",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Oko"
            packageVersion = project.version.toString()
            appResourcesRootDir.set(adbResources)
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
