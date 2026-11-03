import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// --- Firma automatica della release (facoltativa, solo in locale) ---
// In Android/local.properties (escluso da git):
//   macsync.keystore=../PixelMacSync.jks           percorso del keystore, relativo alla cartella Android
//   macsync.keyAlias=key0
//   macsync.keychainService=PixelMacSync keystore  voce del Portachiavi di macOS con la password
//   macsync.keychainKeyService=...                 facoltativo, se la password della chiave è diversa
// Le password restano nel Portachiavi, mai in un file. Senza queste righe la release resta non firmata,
// come per chiunque cloni il repository.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun keychainPassword(service: String): String? = try {
    val lookup = providers.exec {
        commandLine("security", "find-generic-password", "-s", service, "-w")
        isIgnoreExitValue = true
    }
    if (lookup.result.get().exitValue == 0) lookup.standardOutput.asText.get().trimEnd('\n').ifEmpty { null } else null
} catch (e: Exception) {
    null
}

val releaseKeystore = localProperties.getProperty("macsync.keystore")?.let { rootProject.file(it) }
val releaseKeyAlias: String? = localProperties.getProperty("macsync.keyAlias")
val releaseStorePassword = localProperties.getProperty("macsync.keychainService")?.let(::keychainPassword)
val releaseKeyPassword = localProperties.getProperty("macsync.keychainKeyService")?.let(::keychainPassword) ?: releaseStorePassword
val canSignRelease = releaseKeystore?.exists() == true && releaseKeyAlias != null && releaseStorePassword != null

android {
    namespace = "it.luigi.macsync"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "it.luigi.macsync"
        minSdk = 36
        targetSdk = 37
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true // Attiva R8 per offuscare e rimuovere il codice morto
            isShrinkResources = true // Rimuove file grafici e XML inutilizzati
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        aidl = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("androidx.compose.material:material-icons-extended")
    // Libreria ufficiale Google per le forme avanzate MD3
    implementation("androidx.graphics:graphics-shapes:1.0.1")
}