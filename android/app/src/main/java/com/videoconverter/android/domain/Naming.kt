package com.videoconverter.android.domain

fun sourceStem(displayName: String): String {
    val name = displayName.substringAfterLast('/', displayName)
    val stem = name.substringBeforeLast('.', name)
    return stem.ifEmpty { "output" }
}

fun partialOutputPath(output: String): String {
    val lastSlash = output.lastIndexOf('/')
    val parent = if (lastSlash >= 0) output.substring(0, lastSlash) else ""
    val fileName = if (lastSlash >= 0) output.substring(lastSlash + 1) else output

    val dotIndex = fileName.lastIndexOf('.')
    val ext = if (dotIndex >= 0) fileName.substring(dotIndex + 1) else "bin"
    val stem = if (dotIndex >= 0) fileName.substring(0, dotIndex) else fileName
    val actualStem = stem.ifEmpty { "output" }

    val partialName = "$actualStem.partial.$ext"
    return if (parent.isNotEmpty()) "$parent/$partialName" else partialName
}

fun ffmpegFileArg(path: String): String {
    if (path.startsWith("file:") || path.startsWith("pipe:") || path.startsWith("/proc/self/fd/")) {
        return path
    }
    return "file:$path"
}

fun allocateOutputPath(
    outputDir: String,
    stem: String,
    ext: String,
    exists: (String) -> Boolean,
): String {
    val dir = outputDir.trimEnd('/')
    val candidate = "$dir/$stem.$ext"
    if (!exists(candidate)) return candidate
    var index = 1
    while (true) {
        val numbered = "$dir/$stem-$index.$ext"
        if (!exists(numbered)) return numbered
        index++
    }
}
