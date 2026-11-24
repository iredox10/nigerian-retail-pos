pluginManagement { resolutionStrategy { eachPlugin { if (requested.id.id == "org.jetbrains.kotlin.android") useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}"); if (requested.id.id.startsWith("com.android.")) useModule("com.android.tools.build:gradle:${requested.version}") } }; repositories { gradlePluginPortal(); mavenCentral(); google() } }
dependencyResolutionManagement { repositories { mavenCentral(); google() } }
rootProject.name = "NigerianRetailPOS"
if (providers.gradleProperty("cloudOnly").orNull=="true") {
    include(":cloudProtocol", ":cloudClient", ":businessCore", ":server")
} else {
    include(":shared", ":desktopApp", ":androidApp", ":cloudProtocol", ":cloudClient", ":businessCore", ":server")
}
