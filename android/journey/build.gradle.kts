plugins {
    kotlin("jvm") version "2.0.21"
}

dependencies {
    // Android supplies org.json at runtime. compileOnly keeps that jar out of the app.
    compileOnly("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}
