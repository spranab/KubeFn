plugins { java }
dependencies {
    compileOnly(project(":kubefn-api"))
}
tasks.jar { archiveBaseName.set("usecase-sidecar-replacements") }
