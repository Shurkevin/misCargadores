plugins {
    id("com.android.application")
}

android {
    namespace = "es.origds.iberdrolaauto"
    compileSdk = 36

    defaultConfig {
        applicationId = "es.origds.cargadoresmercadona"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.3.0"
        manifestPlaceholders["appAuthRedirectScheme"] = "rv"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("net.openid:appauth:0.11.1")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
}
