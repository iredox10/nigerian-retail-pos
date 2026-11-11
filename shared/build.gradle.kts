plugins { kotlin("multiplatform"); id("org.jetbrains.compose"); id("org.jetbrains.kotlin.plugin.compose") }
kotlin {
    jvm()
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies { implementation(compose.runtime); implementation(compose.foundation); implementation(compose.material3); implementation(compose.components.resources) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
compose.resources { packageOfResClass = "pos.shared.generated.resources" }
