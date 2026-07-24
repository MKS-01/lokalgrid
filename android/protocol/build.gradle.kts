plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    testLogging { events("passed", "failed", "skipped") }
}
