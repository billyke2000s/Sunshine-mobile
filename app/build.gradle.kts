plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.sunshinemobile"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.sunshinemobile"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += if(project.hasProperty("emulatorTests")) listOf("x86_64") else listOf("arm64-v8a") }
        externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release","-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    ndkVersion = "27.2.12479018"
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
