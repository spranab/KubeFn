plugins {
    scala
}

dependencies {
    compileOnly(project(":kubefn-api"))
    implementation("org.scala-lang:scala-library:2.13.16")
}

tasks.jar {
    archiveBaseName.set("scala-functions")
}
