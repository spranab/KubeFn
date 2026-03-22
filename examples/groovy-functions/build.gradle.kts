plugins {
    groovy
}

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    compileOnly(project(":kubefn-api"))
    implementation("org.apache.groovy:groovy:4.0.27")
}

tasks.jar {
    archiveBaseName.set("groovy-functions")
}
