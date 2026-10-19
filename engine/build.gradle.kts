// Pure-JVM library: APK/ZIP/binary-XML rewriting with no Android dependencies,
// so it can be unit-tested on any machine.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.junit)
}
