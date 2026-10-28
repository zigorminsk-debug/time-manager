plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/*
 * Версия приложения задаётся из CI:
 *   -PappVersionCode=<номер сборки> -PappVersionName=<версия>
 * Ветка/локальная сборка получает значения по умолчанию.
 */
val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
val appVersionName = (project.findProperty("appVersionName") as String?) ?: "1.0.1"

/*
 * Подпись. Ключ лежит в репозитории (keystore/timemanager-release.p12),
 * чтобы обновления гарантированно ставились «поверх» прошлых версий.
 * Пароль/алиас можно переопределить переменными окружения или секретами:
 *   TM_KEYSTORE_FILE, TM_KEYSTORE_PASSWORD, TM_KEY_ALIAS, TM_KEY_PASSWORD, TM_KEYSTORE_TYPE
 */
val keystorePath = System.getenv("TM_KEYSTORE_FILE") ?: "keystore/timemanager-release.p12"
val keystoreFile = rootProject.file(keystorePath)
val hasReleaseKey = keystoreFile.exists()
val storePasswordValue = System.getenv("TM_KEYSTORE_PASSWORD") ?: "timemanager"
val keyAliasValue = System.getenv("TM_KEY_ALIAS") ?: "timemanager"
val keyPasswordValue = System.getenv("TM_KEY_PASSWORD") ?: storePasswordValue
val keystoreTypeValue = System.getenv("TM_KEYSTORE_TYPE") ?: "PKCS12"

android {
    namespace = "com.zigorminsk.timemanager"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zigorminsk.timemanager"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "UPDATE_REPO", "\"zigorminsk-debug/time-manager\"")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                this.storeFile = keystoreFile
                this.storePassword = storePasswordValue
                this.keyAlias = keyAliasValue
                this.keyPassword = keyPasswordValue
                this.storeType = keystoreTypeValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
