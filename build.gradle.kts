// Compose and Spring Boot share the root plugin classloader. Use Boot's ZIP API
// version there so packaging cannot load Compose's older commons-compress first.
buildscript {
    configurations.classpath {
        resolutionStrategy.force("org.apache.commons:commons-compress:1.27.1")
    }
}
plugins {
    kotlin("jvm") version "2.2.20" apply false
    kotlin("plugin.serialization") version "2.2.20" apply false
    id("com.android.application") version "8.11.0" apply false
    id("com.android.library") version "8.11.0" apply false
    kotlin("android") version "2.2.20" apply false
    kotlin("multiplatform") version "2.2.20" apply false
    id("org.jetbrains.compose") version "1.9.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}
