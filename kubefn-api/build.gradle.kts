// kubefn-api: The function author contract. TINY, stable, zero heavy deps.
//
// Published to Maven Central via Sonatype:
//   implementation("com.kubefn:kubefn-api:0.3.0")

plugins {
    `java-library`
    `maven-publish`
    signing
}

dependencies {
    api(libs.slf4j.api)
    testImplementation(libs.bundles.testing)
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:none", "-quiet")
        addBooleanOption("html5", true)
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = "com.kubefn"
            artifactId = "kubefn-api"
            version = project.version.toString()

            from(components["java"])

            pom {
                name.set("KubeFn API")
                description.set("Function author API for KubeFn — the Live Application Fabric for Memory-Continuous Architecture")
                url.set("https://kubefn.com")
                inceptionYear.set("2026")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }

                developers {
                    developer {
                        id.set("spranab")
                        name.set("Pranab Sarkar")
                        email.set("developer@pranab.co.in")
                        url.set("https://pranab.co.in")
                        organization.set("KubeFn")
                        organizationUrl.set("https://kubefn.com")
                    }
                }

                scm {
                    url.set("https://github.com/kubefn/kubefn")
                    connection.set("scm:git:git://github.com/kubefn/kubefn.git")
                    developerConnection.set("scm:git:ssh://github.com/kubefn/kubefn.git")
                }

                issueManagement {
                    system.set("GitHub Issues")
                    url.set("https://github.com/kubefn/kubefn/issues")
                }
            }
        }
    }

    repositories {
        // Maven Central via Sonatype OSSRH
        maven {
            name = "MavenCentral"
            val releasesUrl = uri("https://central.sonatype.com/api/v1/publisher/upload")
            val snapshotsUrl = uri("https://central.sonatype.com/api/v1/publisher/upload")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl

            credentials {
                username = System.getenv("SONATYPE_USERNAME") ?: ""
                password = System.getenv("SONATYPE_PASSWORD") ?: ""
            }
        }

        // GitHub Packages (backup)
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/kubefn/kubefn")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: "kubefn"
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}

// GPG signing — required for Maven Central
signing {
    // Use GPG key from environment (CI) or local gpg agent
    val signingKey = System.getenv("GPG_SIGNING_KEY")
    val signingPassword = System.getenv("GPG_SIGNING_PASSWORD")
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword ?: "")
    }
    sign(publishing.publications["mavenJava"])
}

// Only sign when publishing (not during local builds)
tasks.withType<Sign> {
    onlyIf { gradle.taskGraph.hasTask("publish") }
}
