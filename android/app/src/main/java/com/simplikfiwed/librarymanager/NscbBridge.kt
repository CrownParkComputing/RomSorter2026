package com.simplikfiwed.librarymanager

object NscbBridge {
    init {
        System.loadLibrary("nscb")
    }

    external fun configureTempRoot(tempRoot: String): String

    external fun merge(
        inputsJoined: String,
        outputPath: String,
        keysPath: String,
        outputType: String,
    ): String

    external fun contentList(inputPath: String, keysPath: String): String

    external fun compress(inputPath: String, outputPath: String, keysPath: String, level: Int): String

    external fun decompress(inputPath: String, outputPath: String): String

    external fun renamePath(
        path: String,
        keysPath: String,
        cacheDir: String,
        renmode: String,
        addlangue: String,
        noversion: String,
        dlcrname: String
    ): String

    external fun scanDirectory(path: String, keysPath: String, cacheDir: String): String

    external fun scanFaultyFiles(path: String, keysPath: String): String

    external fun refreshTitleDb(cacheDir: String): String

    external fun libraryStatus(inputPath: String, keysPath: String, cacheDir: String): String

    external fun extractFileTitle(fileName: String): String

    external fun titleDbLookupBatch(idsJoined: String, cacheDir: String): String

    external fun getLogs(): String

    external fun deleteFile(path: String): String

    external fun fileList(inputPath: String, keysPath: String): String

    external fun getSuggestedFileName(inputsJoined: String, keysPath: String, cacheDir: String, outputType: String): String
}
