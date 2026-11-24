import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins { kotlin("multiplatform"); id("org.jetbrains.compose"); id("org.jetbrains.kotlin.plugin.compose") }
kotlin {
    jvm()
    jvmToolchain(17)
    sourceSets {
        jvmMain.dependencies {
            implementation(project(":shared"))
            implementation(project(":cloudClient"))
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation("org.xerial:sqlite-jdbc:3.49.1.0")
            implementation("org.slf4j:slf4j-nop:2.0.17")
            implementation("com.google.zxing:core:3.5.4")
            implementation("org.apache.pdfbox:pdfbox:3.0.8")
        }
        jvmTest.dependencies { implementation(kotlin("test")) }
    }
}
compose.desktop { application { mainClass = if(project.hasProperty("preview")) "pos.PreviewKt" else "pos.MainKt"; nativeDistributions {
    targetFormats(TargetFormat.Msi,TargetFormat.Exe,TargetFormat.Deb)
    packageName = "RetailPOS"
    packageVersion = "0.1.0"
    description = "Offline-first Nigerian retail point of sale"
    vendor = "Retail POS"
    modules("java.sql", "jdk.unsupported")
    windows {
        perUserInstall = true
        dirChooser = true
        shortcut = true
        menuGroup = "Retail POS"
        upgradeUuid = "e78852ce-2019-46fb-b81b-d7eac1d46aae"
    }
} } }
