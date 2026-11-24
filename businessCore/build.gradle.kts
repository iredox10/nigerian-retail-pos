plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
sourceSets {
    main {
        kotlin.srcDirs("../shared/src/commonMain/kotlin", "../desktopApp/src/jvmMain/kotlin")
        kotlin.include("pos/domain/**", "pos/data/**", "pos/security/**", "pos/cloud/core/**")
        kotlin.exclude("pos/data/DiagnosticRepository.kt")
    }
}
dependencies {
    api(project(":cloudProtocol"))
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("org.slf4j:slf4j-api:2.0.17")
    testImplementation(kotlin("test-junit"))
}

