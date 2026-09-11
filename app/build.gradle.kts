import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { load(it) }
    }
}
val llmApiKey: String = localProperties.getProperty("LLM_API_KEY", "")
val llmBaseUrl: String = localProperties.getProperty("LLM_BASE_URL", "")
val llmModel: String = localProperties.getProperty("LLM_MODEL", "")
val llmReasoningEffort: String = localProperties.getProperty("LLM_REASONING_EFFORT", "")
val langsmithApiKey: String = localProperties.getProperty("LANGSMITH_API_KEY", "")
val langsmithProject: String = localProperties.getProperty("LANGSMITH_PROJECT", "")
val langsmithEndpoint: String = localProperties.getProperty(
    "LANGSMITH_ENDPOINT",
    "https://api.smith.langchain.com",
)
val webSearchApiKey: String = localProperties.getProperty("WEB_SEARCH_API_KEY", "")

fun escapeBuildConfig(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.mystar.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mystar.agent"
        minSdk = 24
        targetSdk = 35
        versionCode = 31
        versionName = "0.40"

        buildConfigField("String", "LLM_API_KEY", "\"${escapeBuildConfig(llmApiKey)}\"")
        buildConfigField("String", "LLM_BASE_URL", "\"${escapeBuildConfig(llmBaseUrl)}\"")
        buildConfigField("String", "LLM_MODEL", "\"${escapeBuildConfig(llmModel)}\"")
        buildConfigField(
            "String",
            "LLM_REASONING_EFFORT",
            "\"${escapeBuildConfig(llmReasoningEffort)}\"",
        )
        buildConfigField("String", "LANGSMITH_API_KEY", "\"${escapeBuildConfig(langsmithApiKey)}\"")
        buildConfigField("String", "LANGSMITH_PROJECT", "\"${escapeBuildConfig(langsmithProject)}\"")
        buildConfigField(
            "String",
            "LANGSMITH_ENDPOINT",
            "\"${escapeBuildConfig(langsmithEndpoint)}\"",
        )
        buildConfigField("String", "WEB_SEARCH_API_KEY", "\"${escapeBuildConfig(webSearchApiKey)}\"")
    }

    buildTypes {
        release {
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 골든셋 원본은 docs/evaluation/set_D0.md 하나. copyEvalSet이 여기로 복사한다.
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/evalAssets"))
}

/** 골든셋 원본 문서를 assets/eval/로 복사한다. 셋은 문서에서만 고친다. */
val copyEvalSet by tasks.registering(Copy::class) {
    from(rootProject.file("docs/evaluation/set_D0.md"))
    into(layout.buildDirectory.dir("generated/evalAssets/eval"))
}

tasks.named("preBuild") {
    dependsOn(copyEvalSet)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
