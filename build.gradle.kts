plugins { java }

group = "de.unlimited8"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.205-stable")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }

tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
