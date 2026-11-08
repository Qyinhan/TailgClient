plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }

// Runs the complete pure-domain smoke harness on the Gradle CI machine as well.
tasks.register<JavaExec>("smokeTest") {
    group = "verification"
    description = "Run transport-independent domain contract checks"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.tailg.core.domain.DomainSmokeTestKt")
}
