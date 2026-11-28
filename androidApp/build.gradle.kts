plugins { id("com.android.application"); kotlin("android"); id("org.jetbrains.compose"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "pos.android"
    compileSdk = 35
    defaultConfig { applicationId = "ng.retailpos.android"; minSdk = 30; targetSdk = 35; versionCode = 1; versionName = "0.1.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    defaultConfig.manifestPlaceholders["appLabel"] = "Retail POS"
    buildTypes {
        create("acceptance") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".acceptance"
            versionNameSuffix = "-acceptance"
            manifestPlaceholders["appLabel"] = "Retail POS QA"
            matchingFallbacks += "debug"
        }
    }
    testBuildType = if(providers.gradleProperty("androidAcceptance").orNull=="true") "acceptance" else "debug"
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets["main"].java.setSrcDirs(listOf("src/main/kotlin", layout.buildDirectory.dir("generated/localCore")))
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
}
dependencies {
    implementation(project(":shared"))
    implementation(project(":cloudClient"))
    implementation("org.bouncycastle:bcprov-jdk18on:1.87")
    implementation(compose.runtime); implementation(compose.foundation); implementation(compose.material3)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
val prepareLocalCore by tasks.registering(Sync::class) {
    from("../desktopApp/src/jvmMain/kotlin") {
        include("pos/data/**", "pos/security/**", "pos/output/BackupService.kt", "pos/output/ProductImportService.kt", "pos/output/CsvUtf8.kt", "pos/output/CsvData.kt", "pos/screens/ReportDateRange.kt", "pos/output/CategoryPerformanceService.kt", "pos/output/ProductPerformanceService.kt", "pos/output/StaffPerformanceService.kt", "pos/output/SupplierInvoiceCsv.kt", "pos/output/SupplierStatementService.kt", "pos/output/SupplierAccountStatementService.kt", "pos/output/CustomerStatementService.kt", "pos/output/DatedCustomerStatementService.kt", "pos/output/BulkCatalogueCsvService.kt", "pos/cloud/app/**", "pos/cloud/ui/**")
        exclude("pos/data/ImageUpload.kt", "pos/data/SupplierInvoiceAttachmentUpload.kt", "pos/data/DiagnosticRepository.kt")
    }
    into(layout.buildDirectory.dir("generated/localCore"))
}
kotlin.sourceSets.getByName("main").kotlin.setSrcDirs(listOf("src/main/kotlin", layout.buildDirectory.dir("generated/localCore")))
tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach { dependsOn(prepareLocalCore) }
