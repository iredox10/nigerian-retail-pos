plugins {
    kotlin("jvm")
    id("org.springframework.boot") version "3.5.16"
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    implementation(project(":cloudProtocol"))
    implementation(project(":businessCore"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation(kotlin("reflect"))
    testImplementation(project(":cloudClient"))
    testImplementation(kotlin("test"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
tasks.test { useJUnitPlatform() }
tasks.bootJar { archiveFileName.set("retailpos-server.jar") }
