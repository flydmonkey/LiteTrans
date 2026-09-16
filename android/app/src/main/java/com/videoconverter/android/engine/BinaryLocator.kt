package com.videoconverter.android.engine

import java.io.File

fun nativeBinary(nativeLibraryDir: File, name: String): File {
    val file = File(nativeLibraryDir, "lib$name.so")
    if (!file.isFile) {
        throw IllegalStateException(
            "找不到打包的 $name。请先运行 node android/scripts/fetch-ffmpeg.mjs",
        )
    }
    return file
}
