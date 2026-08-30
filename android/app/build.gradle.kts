plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phondrive.webdavspike"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phondrive.webdavspike"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-spike"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release-key.jks")
            storePassword = "phondrive123"
            keyAlias = "phondrive"
            keyPassword = "phondrive123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // ponytail: Ktor nao e oficialmente suportado em Android - pinar versao e testar cada upgrade
    implementation("io.ktor:ktor-server-core:3.0.3")
    implementation("io.ktor:ktor-server-cio:3.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core:1.13.1")
    
    // Test
    testImplementation("junit:junit:4.13.2")
}
