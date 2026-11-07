plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    application
}

group = "shoplens"
version = "0.1.0"

repositories { mavenCentral() }

val ktorVersion = "3.2.3"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("com.anthropic:anthropic-java:3.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0") // WebP decoding for ImageIO

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
}

kotlin { jvmToolchain(21) }

application { mainClass.set("shoplens.ApplicationKt") }

tasks.test { useJUnitPlatform() }
