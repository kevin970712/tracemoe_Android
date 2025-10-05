// 【關鍵修復】
// 這個區塊告訴 Gradle 去哪裡尋找構建專案所需的「插件」
pluginManagement {
    repositories {
        google() // 去 Google 的 Maven 倉庫尋找
        mavenCentral() // 去 Maven 中央倉庫尋找
        gradlePluginPortal() // 去 Gradle 官方插件入口尋找
    }
}

// 這個區塊告訴專案去哪裡尋找「函式庫」(dependencies)
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tracemoe"
include(":app")