plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
rootProject.name = "lnis-afs-validator"
include("lnis-protocol", "lnis-agent", "lnis-server")
