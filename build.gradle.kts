// Корневой скрипт сборки. Версии плагинов объявлены здесь и применяются в :app.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

// Задача clean удаляет каталог build корневого проекта.
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
