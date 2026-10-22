package com.example.stockmonitor

import java.io.File

object TestProjectFiles {
    fun source(relativePath: String): File {
        var directory: File? = File("").absoluteFile
        while (directory != null && !File(directory, "settings.gradle").exists()) {
            directory = directory.parentFile
        }
        val root = requireNotNull(directory) { "找不到 Gradle 项目根目录" }
        return File(root, "app/src/main/java/$relativePath")
    }
}
