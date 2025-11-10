import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "wtf.cluster.wireguardobfuscator"
    compileSdk = 36

    defaultConfig {
        applicationId = "wtf.cluster.wireguardobfuscator"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1"
        // CI-friendly defaults: can be overridden with -PobfuscatorServer / -PobfuscatorKey
        val obfServer: String = (project.findProperty("obfuscatorServer") as String?) ?: "127.0.0.1:65535"
        val obfKey: String = (project.findProperty("obfuscatorKey") as String?) ?: ""
        buildConfigField("String", "OBFUSCATOR_SERVER", "\"$obfServer\"")
        buildConfigField("String", "OBFUSCATOR_KEY", "\"$obfKey\"")
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
    applicationVariants.all {
        outputs.all {
            val formattedDate = SimpleDateFormat("yyMMdd-HHmmss").format(Date())
            val versionCodeVal = defaultConfig.versionCode ?: 1
            if (this is ApkVariantOutputImpl) {
                this.outputFileName = "wg-obfuscator-v${versionCodeVal}-${name}-${formattedDate}.apk"
            }
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
        // Enable generation of BuildConfig fields so CI can inject defaults
        buildConfig = true
    }
}

dependencies {
    implementation(libs.ui)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.material3)
    implementation(libs.androidx.runtime.livedata)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // QR code scanner
    implementation("com.google.zxing:core:3.5.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Activity KTX for enableEdgeToEdge
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Testing dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.7.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("androidx.test:core-ktx:1.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.robolectric:robolectric:4.11.1")
}