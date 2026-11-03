plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.scontrini.trasferta"
    compileSdk = 36

    defaultConfig {
        applicationId = "it.scontrini.trasferta"
        minSdk = 26
        targetSdk = 36
        // VERSION_CODE/VERSION_NAME arrivano dal workflow "Pubblica su Google Play"; altrimenti il numero della build
        versionCode = (System.getenv("VERSION_CODE") ?: System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: ("1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0"))
    }

    // Chiave di firma privata: arriva dai Secrets di GitHub (KEYSTORE_B64, KEYSTORE_PASSWORD), mai nel repository.
    // Stessa chiave a ogni versione = gli aggiornamenti si installano sopra senza perdere i dati.
    val ksFile = rootProject.file("signing/app.keystore")
    val ksPass = System.getenv("KEYSTORE_PASSWORD") ?: ""
    val hasKey = ksFile.exists() && ksPass.isNotEmpty()
    signingConfigs {
        create("fixed") {
            storeFile = ksFile
            storePassword = ksPass
            keyAlias = "notespese"
            keyPassword = ksPass
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasKey) signingConfig = signingConfigs.getByName("fixed")
        }
        getByName("release") {
            isMinifyEnabled = false
            if (hasKey) signingConfig = signingConfigs.getByName("fixed")
        }
    }

    buildFeatures { buildConfig = true }

    // due versioni: "completa" legge da sola gli SMS; "senzaSms" non chiede il permesso SMS
    // (Play Protect blocca le app scaricate da internet che lo chiedono) e riceve gli SMS da MacroDroid o da Condividi
    flavorDimensions += "sms"
    productFlavors {
        create("completa") {
            dimension = "sms"
            buildConfigField("boolean", "SMS", "true")
        }
        create("senzaSms") {
            dimension = "sms"
            buildConfigField("boolean", "SMS", "false")
            versionNameSuffix = "-senzaSMS"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    // menu laterale (☰)
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // blocco dell'app con impronta, volto o PIN del telefono
    implementation("androidx.biometric:biometric:1.1.0")
    // Lettura del testo dello scontrino direttamente sul telefono, senza internet
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // "Sono a tavola": scoperta dei telefoni dei colleghi vicini (Bluetooth / Wi-Fi), senza server
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    // commensali via QR: lettore QR di Google (senza permesso fotocamera) e generazione del proprio QR
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.google.zxing:core:3.5.3")
}
