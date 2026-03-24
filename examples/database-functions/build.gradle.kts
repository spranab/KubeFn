plugins { java }

dependencies {
    compileOnly(project(":kubefn-api"))
    implementation("com.h2database:h2:2.2.224")
    implementation("com.zaxxer:HikariCP:5.1.0")
}

tasks.jar {
    archiveBaseName.set("database-functions")
}
