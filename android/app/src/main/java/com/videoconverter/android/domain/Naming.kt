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

fun uniqueFileName(
    stem: String,
    ext: String,
    taken: (String) -> Boolean,
    clock: () -> String = ::outputCollisionStamp,
): String {
    val base = "$stem.$ext"
    if (!taken(base)) return base
    val stamp = clock()
    val stamped = "${stem}_$stamp.$ext"
    if (!taken(stamped)) return stamped
    var index = 1
    while (taken("${stem}_${stamp}_$index.$ext")) {
        index++
    }
    return "${stem}_${stamp}_$index.$ext"
}

fun outputCollisionStamp(): String {
    val now = java.time.LocalDateTime.now()
    return java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(now)
}

fun allocateOutputPath(
    outputDir: String,
    stem: String,
    ext: String,
    exists: (String) -> Boolean,
    clock: () -> String = ::outputCollisionStamp,
): String {
    val dir = outputDir.trimEnd('/')
    val name = uniqueFileName(stem, ext, { exists("$dir/$it") }, clock)
    return "$dir/$name"
}

fun sanitizeRenameStem(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.contains('/') || trimmed.contains('\\')) return null
    val cleaned = trimmed
        .replace(Regex("""[:*?"<>|]"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trim('.')
    val stem = cleaned.substringBeforeLast('.', cleaned).trim()
    if (stem.isEmpty() || stem == "." || stem == "..") return null
    return stem.take(80)
}

fun renamedFileName(displayName: String, outputPath: String?, stem: String): String {
    val current = outputPath
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.substringBefore('?')
        ?.takeIf { it.isNotBlank() }
        ?: displayName
    val ext = current.substringAfterLast('.', "").takeIf { it.isNotBlank() && !it.contains('/') }
        ?: displayName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        ?: ""
    return if (ext.isBlank()) stem else "$stem.$ext"
}

fun canRenameJob(status: JobStatus): Boolean = status == JobStatus.Completed
