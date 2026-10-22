import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Assinatura de release: lida de keystore.properties (fora do Git; ver keystore.properties.example).
val releaseSigning = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

// Schemas exportados são versionados e usados pelo MigrationTestHelper.
room {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "br.com.finbot"
    // Compose 1.12+ (BOM 2026.09.00) exige compileSdk 37.
    compileSdk = 37

    defaultConfig {
        applicationId = "br.com.finbot"
        minSdk = 26
        targetSdk = 36
        versionCode = 102
        versionName = "1.0.2"

        testInstrumentationRunner = "br.com.finbot.FinBotTestRunner"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Sem keystore.properties o release sai sem assinatura (não instalável), nunca com chave de debug.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric (capturas do README) precisa dos recursos do app nos testes JVM.
        unitTests.isIncludeAndroidResources = true
        // As capturas do README (Robolectric) só rodam quando pedidas; fora disso ficam fora da suíte.
        if (System.getenv("FINBOT_SCREENSHOTS") != "1") {
            unitTests.all { it.exclude("**/screenshots/**") }
        }
        // Só nas capturas: o Robolectric se perde com espaços no caminho do usuário (ex.: "Luis Felipe").
        if (System.getenv("FINBOT_SCREENSHOTS") == "1") {
            unitTests.all {
                it.systemProperty("user.home", System.getenv("FINBOT_TEST_HOME") ?: "E:/gtmp/robolectric-home")
                // JDK 25: o Robolectric acessa internos de java.io/java.lang por reflexão.
                it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED", "--add-opens=java.base/java.nio=ALL-UNNAMED")
            }
        }
    }

    // Os extratos sintéticos de teste (OFX/CSV) ficam num só lugar e também servem aos testes instrumentados.
    sourceSets {
        getByName("androidTest") {
            assets.srcDir("src/test/resources")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    ksp(libs.androidx.room.compiler)
    // Alinha kotlinx-serialization entre app e testes instrumentados (ADR-010).
    implementation(platform(libs.kotlinx.serialization.bom))
    // Explícito para alinhar com kotlinx-coroutines-test; as libs AndroidX trazem 1.9.0 transitivamente.
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    testImplementation(libs.mockwebserver)
    // Capturas de tela do README renderizadas na JVM (ScreenshotsTest; só roda com FINBOT_SCREENSHOTS=1).
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
