plugins { id("com.android.library"); kotlin("multiplatform"); id("org.jetbrains.compose"); id("org.jetbrains.kotlin.plugin.compose") }
kotlin {
    jvm()
    androidTarget()
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies { implementation(compose.runtime); implementation(compose.foundation); implementation(compose.material3); implementation(compose.components.resources) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
compose.resources { packageOfResClass = "pos.shared.generated.resources" }

android { namespace = "pos.shared"; compileSdk = 35; defaultConfig { minSdk = 30 }; compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 } }
