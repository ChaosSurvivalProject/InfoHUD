plugins {
    java
}

group = "dev.infohud"
version = "1.0.0"

repositories {
    // Paper / Purpur 官方 Maven 仓库（paper-api 快照在这里）
    maven("https://repo.papermc.io/repository/maven-public/")
    // PlaceholderAPI（仅编译期需要，运行时是软依赖）
    maven("https://repo.extendedclip.com/releases/")
    mavenCentral()
}

dependencies {
    // 只用 API 编译，不打进 jar：Purpur 完全兼容 Paper API，同一个 jar 两端通用
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.12.3")

    // 单元测试只覆盖纯逻辑（时间换算、时段判定、文本解析），不需要真实服务器
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    // Paper 1.21.x 要求 Java 21 字节码；不指定 toolchain，直接用当前 JDK 21 编译
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
    }

    processResources {
        val projectVersion = project.version.toString()
        inputs.property("version", projectVersion)
        filesMatching("plugin.yml") {
            expand(mapOf("version" to projectVersion))
        }
    }

    jar {
        archiveBaseName.set("InfoHUD")
        archiveVersion.set(project.version.toString())
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
