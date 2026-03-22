// Checkout pipeline demo: 7-function in-memory composition
// Demonstrates FnGraph and HeapExchange working together

dependencies {
    compileOnly(project(":kubefn-api"))
    testImplementation(libs.bundles.testing)
}

tasks.jar {
    archiveBaseName.set("checkout-pipeline")
}
