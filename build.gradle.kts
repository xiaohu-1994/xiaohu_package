plugins {
    `java-library`
    // A plain jar is all we need: paper-api is compileOnly and is supplied by
    // the server at runtime, so there is nothing to bundle - no shadow/fat-jar.
}

group = "com.xiaohu.resourcepack"
version = "1.9.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // The Paper server provides this API at runtime, so it must not be bundled.
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks {
    jar {
        archiveBaseName.set("xiaohu_package")
    }
    processResources {
        // Keep plugin.yml in sync with the build version.
        filesMatching("plugin.yml") {
            expand("version" to version)
        }
    }
}
