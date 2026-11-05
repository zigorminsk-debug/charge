// Откуда брать плагины Gradle. Фильтр для google() ограничивает пакеты Android, Google и AndroidX.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Библиотеки. FAIL_ON_PROJECT_REPOS запрещает объявлять репозитории в модулях: все они здесь.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Имя проекта ChargeHUD. Единственный модуль — :app.
rootProject.name = "ChargeHUD"
include(":app")
