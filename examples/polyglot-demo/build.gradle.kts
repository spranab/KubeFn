// Polyglot demo: Java function that reads objects published by
// Kotlin, Scala, and Groovy functions — all zero-copy on the same heap.

dependencies {
    compileOnly(project(":kubefn-api"))
    testImplementation(libs.bundles.testing)
}

tasks.jar {
    archiveBaseName.set("polyglot-demo")
}
