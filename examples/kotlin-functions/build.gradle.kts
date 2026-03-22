plugins {
    kotlin("jvm") version "2.1.20"
}

dependencies {
    compileOnly(project(":kubefn-api"))
    implementation(kotlin("stdlib"))
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    archiveBaseName.set("kotlin-functions")
}
