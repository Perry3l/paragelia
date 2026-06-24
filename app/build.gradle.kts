plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.gms.google.services)

}

android {
    namespace = "com.ads.paragelia"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.ads.paragelia"
        minSdk = 28
        targetSdk = 36
        versionCode = 12
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.firebase.database)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation("androidx.cardview:cardview:1.0.0")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.10.0"))
    implementation("com.google.firebase:firebase-database")

    // ZCS
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    // USB
    implementation("com.github.mik3y:usb-serial-for-android:3.7.0")

    // Retrofit & API Calls
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")

    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime:2.11.2")
}