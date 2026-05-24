package com.nscb.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.net.URL

private const val NSCB_LOG_TAG = "NSCB"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val viewModel: NscbViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T {
                            @Suppress("UNCHECKED_CAST")
                            return NscbViewModel(applicationContext) as T
                        }
                    }
                )
                AndroidNscbScreen(viewModel)
            }
        }
    }
}

class NscbViewModel(context: Context) : ViewModel() {
    private val prefs = context.getSharedPreferences("nscb_prefs", Context.MODE_PRIVATE)

    var keysPath by mutableStateOf(prefs.getString("keys_path", "") ?: "")
        private set

    var outputDirectory by mutableStateOf(
        prefs.getString("output_directory", "") ?: (context.getExternalFilesDir(null)?.absolutePath
            ?: context.filesDir.absolutePath)
    )
        private set

    var outputFolderUri by mutableStateOf(prefs.getString("output_folder_uri", "") ?: "")
        private set

    var lastScanPath by mutableStateOf(prefs.getString("last_scan_path", "/storage/emulated/0/") ?: "/storage/emulated/0/")
        private set

    var lastScanUri by mutableStateOf(prefs.getString("last_scan_uri", "") ?: "")
        private set

    var deleteSourcesAfterMerge by mutableStateOf(prefs.getBoolean("delete_sources_after_merge", false))
        private set

    var ignoreXciInLibraryMerge by mutableStateOf(prefs.getBoolean("ignore_xci_in_library_merge", false))
        private set

    var analyzePackageBeforeImport by mutableStateOf(prefs.getBoolean("analyze_package_before_import", true))
        private set

    init {
        val savedKeys = keysPath.trim()
        val internalTempRoot = File(context.filesDir, "tmp").absolutePath.trimEnd('/') + "/"
        if (savedKeys.isNotBlank()) {
            val savedKeysFile = File(savedKeys)
            if (!savedKeysFile.isFile || savedKeysFile.absolutePath.startsWith(internalTempRoot)) {
                Log.w(NSCB_LOG_TAG, "Clearing stale prod.keys path: $savedKeys")
                keysPath = ""
                prefs.edit().remove("keys_path").apply()
            }
        }
    }

    fun updateKeysPath(path: String) {
        keysPath = path
        prefs.edit().putString("keys_path", path).apply()
    }

    fun updateOutputDirectory(path: String) {
        outputDirectory = path
        prefs.edit().putString("output_directory", path).apply()
    }

    fun updateOutputFolderUri(uri: String) {
        outputFolderUri = uri
        prefs.edit().putString("output_folder_uri", uri).apply()
    }

    fun updateLastScanPath(path: String) {
        lastScanPath = path
        prefs.edit().putString("last_scan_path", path).apply()
    }

    fun updateLastScanUri(uri: String) {
        lastScanUri = uri
        prefs.edit().putString("last_scan_uri", uri).apply()
    }

    fun clearLastScanUri() {
        lastScanUri = ""
        lastScanPath = ""
        prefs.edit().remove("last_scan_uri").remove("last_scan_path").apply()
    }

    fun clearOutputFolderUri() {
        outputFolderUri = ""
        outputDirectory = ""
        prefs.edit().remove("output_folder_uri").remove("output_directory").apply()
    }

    fun updateDeleteSourcesAfterMerge(value: Boolean) {
        deleteSourcesAfterMerge = value
        prefs.edit().putBoolean("delete_sources_after_merge", value).apply()
    }

    fun updateIgnoreXciInLibraryMerge(value: Boolean) {
        ignoreXciInLibraryMerge = value
        prefs.edit().putBoolean("ignore_xci_in_library_merge", value).apply()
    }

    fun updateAnalyzePackageBeforeImport(value: Boolean) {
        analyzePackageBeforeImport = value
        prefs.edit().putBoolean("analyze_package_before_import", value).apply()
    }
}

data class ScanFile(
    val path: String,
    val filename: String,
    val titleId: String,
    val version: Long,
    val kind: String
)

data class ScanGroup(
    val baseId: String,
    val titleName: String,
    val latestVersionDb: Long,
    val items: List<ScanFile>
)

data class LibraryFile(
    val path: String,
    val filename: String,
    val size: Long,
    val modified: Long,
    val extension: String,
    val titleSummary: String = "Not checked",
    val versionSummary: String = "",
    val versionStatus: String = "unknown",
    val imageUrl: String? = null,
    val details: List<LibraryTitleDetail> = emptyList()
)

data class LibraryTitleDetail(
    val titleId: String,
    val titleName: String,
    val localVersion: Long,
    val latestVersion: Long?,
    val releaseDate: String?,
    val publisher: String?,
    val languages: List<String>,
    val description: String?,
    val imageUrl: String?,
    val screenshotUrls: List<String>,
    val status: String
)

data class FaultyFile(
    val path: String,
    val filename: String,
    val reason: String
)

@Composable
fun AndroidNscbScreen(viewModel: NscbViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var mergeInputs by remember { mutableStateOf("") }
    var mergeOutputName by remember { mutableStateOf("merged_output") }
    var mergeOutputUri by remember { mutableStateOf<Uri?>(null) }
    var mergeType by remember { mutableStateOf("nsp") }
    var singleFileInput by remember { mutableStateOf("") }
    var archiveInput by remember { mutableStateOf("") }
    var archiveOutputName by remember { mutableStateOf("converted") }
    var archiveOutputUri by remember { mutableStateOf<Uri?>(null) }
    var archiveMode by remember { mutableStateOf("compress") }
    var archiveLevel by remember { mutableStateOf("3") }
    var cancelBulkAfterCurrent by remember { mutableStateOf(false) }
    
    val scanResults = remember { mutableStateListOf<ScanGroup>() }
    val libraryFiles = remember { mutableStateListOf<LibraryFile>() }
    val faultyFiles = remember { mutableStateListOf<FaultyFile>() }
    val duplicateLibraryPaths = remember { mutableStateListOf<String>() }
    
    var output by remember { mutableStateOf("Ready") }
    var progressStatus by remember { mutableStateOf("Idle") }
    var mergeRunning by remember { mutableStateOf(false) }
    var scanRunning by remember { mutableStateOf(false) }
    var libraryRunning by remember { mutableStateOf(false) }
    var archiveRunning by remember { mutableStateOf(false) }
    var infoRunning by remember { mutableStateOf(false) }
    var settingsRunning by remember { mutableStateOf(false) }
    val isRunning = mergeRunning || scanRunning || libraryRunning || archiveRunning || infoRunning || settingsRunning
    val mergeInputCount = mergeInputs.lines().count { it.trim().isNotBlank() }
    var showConsoleModal by remember { mutableStateOf(false) }
    var showFaultyDeletePrompt by remember { mutableStateOf(false) }
    var pendingFaultyDeletePaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingFaultyDeleteContext by remember { mutableStateOf("") }
    var pendingFaultyDeleteDecision by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var showDuplicateDeletePrompt by remember { mutableStateOf(false) }
    var pendingDuplicateDeletePaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingDuplicateDeleteContext by remember { mutableStateOf("") }
    var pendingDuplicateDeleteDecision by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var importFolderPaused by remember { mutableStateOf(false) }
    var showPermissionWizard by remember { mutableStateOf(false) }
    val titleDbCacheDir = remember { File(context.cacheDir, "titledb").absolutePath }

    fun libraryCacheFile() = File(context.filesDir, "library_meta_cache.json")

    fun loadLibraryCache(): Map<String, JSONObject> {
        val file = libraryCacheFile()
        if (!file.exists()) return emptyMap()
        return try {
            val obj = JSONObject(file.readText())
            val entries = obj.optJSONObject("entries") ?: return emptyMap()
            val map = mutableMapOf<String, JSONObject>()
            for (key in entries.keys()) map[key] = entries.getJSONObject(key)
            map
        } catch (_: Exception) { emptyMap() }
    }

    val libraryCache = remember {
        val map = mutableMapOf<String, JSONObject>()
        map.putAll(loadLibraryCache())
        map
    }

    fun saveLibraryCache() {
        try {
            val entries = JSONObject()
            for ((k, v) in libraryCache) entries.put(k, v)
            libraryCacheFile().writeText(JSONObject().put("version", 1).put("entries", entries).toString())
        } catch (_: Exception) { }
    }

    fun parseLibraryStatusJson(raw: String, base: LibraryFile): LibraryFile {
        return try {
            val json = JSONObject(raw)
            val titles = json.getJSONArray("titles")
            if (titles.length() == 0) {
                base.copy(titleSummary = "No title metadata found", versionStatus = "unknown")
            } else {
                val names = mutableListOf<String>()
                val versions = mutableListOf<String>()
                var imageUrl: String? = null
                val details = mutableListOf<LibraryTitleDetail>()
                var aggregateStatus = "current"
                for (i in 0 until titles.length()) {
                    val item = titles.getJSONObject(i)
                    val titleId = item.getString("title_id")
                    val name = item.optString("title_name", "Unknown")
                    val localVersion = item.getLong("local_version")
                    val latestVersion = if (item.isNull("latest_version")) null else item.getLong("latest_version")
                    val releaseDate = if (item.isNull("release_date")) null else item.getString("release_date")
                    val publisher = if (item.isNull("publisher")) null else item.getString("publisher")
                    val description = if (item.isNull("description")) null else item.getString("description")
                    val detailImageUrl = if (item.isNull("image_url")) null else item.getString("image_url")
                    val screenshotUrls = mutableListOf<String>()
                    if (!item.isNull("screenshot_urls")) {
                        val shots = item.getJSONArray("screenshot_urls")
                        for (j in 0 until shots.length()) screenshotUrls.add(shots.getString(j))
                    }
                    val languages = mutableListOf<String>()
                    if (!item.isNull("languages")) {
                        val langs = item.getJSONArray("languages")
                        for (j in 0 until langs.length()) languages.add(langs.getString(j))
                    }
                    if (imageUrl == null && !item.isNull("image_url")) imageUrl = item.getString("image_url")
                    val status = item.getString("status")
                    names.add("$name [$titleId]")
                    versions.add("local $localVersion" + if (latestVersion != null) " / latest $latestVersion" else " / latest unknown")
                    details.add(
                        LibraryTitleDetail(
                            titleId = titleId, titleName = name, localVersion = localVersion,
                            latestVersion = latestVersion, releaseDate = releaseDate,
                            publisher = publisher, languages = languages, description = description,
                            imageUrl = detailImageUrl, screenshotUrls = screenshotUrls, status = status
                        )
                    )
                    aggregateStatus = when {
                        status == "outdated" -> "outdated"
                        aggregateStatus != "outdated" && status == "unknown" -> "unknown"
                        else -> aggregateStatus
                    }
                }
                base.copy(
                    titleSummary = names.take(3).joinToString("\n"),
                    versionSummary = versions.take(3).joinToString("\n"),
                    versionStatus = aggregateStatus,
                    imageUrl = imageUrl,
                    details = details
                )
            }
        } catch (_: Exception) {
            base.copy(titleSummary = raw.take(200), versionStatus = "error")
        }
    }

    fun buildLibraryFileFromCache(base: LibraryFile): LibraryFile? {
        val entry = libraryCache[base.path] ?: return null
        if (entry.optLong("size") != base.size || entry.optLong("modified") != base.modified) return null
        if (entry.optBoolean("hasError", false)) {
            return base.copy(titleSummary = entry.optString("titleSummary", "Cache error"), versionStatus = "error")
        }
        val rawJson = entry.optString("rawJson", "")
        if (rawJson.isBlank()) return null
        return parseLibraryStatusJson(rawJson, base)
    }

    fun putLibraryCacheEntry(path: String, size: Long, modified: Long, raw: String?, hasError: Boolean = false, titleSummary: String = "") {
        val entry = JSONObject()
        entry.put("size", size)
        entry.put("modified", modified)
        if (hasError) {
            entry.put("hasError", true)
            entry.put("titleSummary", titleSummary)
        } else if (raw != null) {
            entry.put("rawJson", raw)
        }
        libraryCache[path] = entry
    }

    fun appendLog(line: String) {
        Log.i(NSCB_LOG_TAG, line)
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        output = if (output == "Ready" || output == "Idle" || output == "Cleared") {
            "[$timestamp] $line"
        } else {
            output + "\n[$timestamp] $line"
        }
    }

    fun flushRustLogs() {
        val logs = NscbBridge.getLogs()
        if (logs.isNotBlank()) {
            for (ln in logs.lineSequence()) {
                if (ln.isNotBlank()) appendLog(ln)
            }
        }
    }

    fun setProgress(message: String) {
        progressStatus = message
        appendLog(message)
    }

    fun hasPersistedTreePermission(uriString: String, needsWrite: Boolean): Boolean {
        if (uriString.isBlank()) return false
        val target = Uri.parse(uriString)
        if (context.contentResolver.persistedUriPermissions.any { perm ->
                perm.uri == target && perm.isReadPermission && (!needsWrite || perm.isWritePermission)
            }) return true
        // USB and some SD card providers don't support persistent permissions.
        // Check for live (session) access via DocumentFile as a fallback.
        return try {
            DocumentFile.fromTreeUri(context, target)?.isDirectory == true
        } catch (_: Exception) {
            false
        }
    }

    fun permissionWizardReady(): Boolean {
        return viewModel.lastScanUri.isNotBlank() || viewModel.lastScanPath.isNotBlank()
    }

    fun refreshPermissionWizardState() {
        if (!permissionWizardReady()) {
            showPermissionWizard = true
        }
    }

    fun extForMergeType(type: String): String = if (type.equals("xci", true)) "xci" else "nsp"
    fun extensionForPath(path: String): String = path.substringBefore('?').substringAfterLast('/', path).substringAfterLast('.', "").lowercase()
    fun archiveOutputExt(input: String, mode: String): String {
        return when (extensionForPath(input)) {
            "nsp" -> if (mode == "compress") "nsz" else "nsp"
            "xci" -> if (mode == "compress") "xcz" else "xci"
            "nsz" -> "nsp"
            "xcz" -> "xci"
            "ncz" -> "nca"
            else -> if (mode == "compress") "nsz" else "nsp"
        }
    }
    fun outputNameWithExt(name: String, ext: String): String {
        val base = name.trim().ifEmpty { "converted" }
        return if (base.lowercase().endsWith(".$ext")) base else "$base.$ext"
    }
    fun usesSafOutputFolder(): Boolean {
        return viewModel.outputFolderUri.isNotBlank()
    }
    fun storageRootForPath(path: String): String? {
        val clean = path.substringBefore('?')
        if (!clean.startsWith("/storage/")) return null
        val parts = clean.split('/').filter { it.isNotBlank() }
        if (parts.size < 2 || parts[0] != "storage") return null
        return "/storage/${parts[1]}"
    }
    fun tempRootScore(dir: File): Int {
        val dirPath = dir.absolutePath
        val dirRoot = storageRootForPath(dirPath)
        val outputRoot = storageRootForPath(viewModel.outputDirectory)
        val inputRoot = storageRootForPath(viewModel.lastScanPath)
        // Always prefer internal app-private storage to avoid scoped-storage symlink kills by vold
        val isInternal = dirPath.startsWith(context.filesDir.absolutePath) ||
            (context.cacheDir != null && dirPath.startsWith(context.cacheDir.absolutePath))
        return when {
            isInternal -> 0
            outputRoot != null && dirRoot == outputRoot -> 1
            inputRoot != null && dirRoot == inputRoot -> 2
            dirPath.startsWith("/storage/emulated/") -> 3
            else -> 4
        }
    }
    fun operationTempCandidates(): List<File> {
        return (
            listOf(File(context.filesDir, "tmp")) +
                context.getExternalFilesDirs("tmp").filterNotNull()
            )
            .distinctBy { it.absolutePath }
            .sortedWith(compareBy<File> { tempRootScore(it) }.thenByDescending { it.usableSpace })
    }
    fun operationTempRoot(): File {
        for (dir in operationTempCandidates()) {
            if (!dir.exists() && !dir.mkdirs()) {
                continue
            }
            if (!dir.isDirectory || !dir.canWrite()) {
                continue
            }
            val probe = File(dir, ".write_test")
            val writable = runCatching {
                FileOutputStream(probe).use { it.write(0) }
                probe.delete()
            }.isSuccess
            if (writable) return dir
        }
        throw IllegalStateException("Could not create a writable app temp folder.")
    }
    fun configureNativeTempRoot(): File {
        val dir = operationTempRoot()
        val result = NscbBridge.configureTempRoot(dir.absolutePath)
        require(result.startsWith("OK")) { result }
        return dir
    }
    fun ensureFileOperationStorage() {
        configureNativeTempRoot()
        File(context.cacheDir, "titledb").mkdirs()
        if (usesSafOutputFolder()) {
            require(hasPersistedTreePermission(viewModel.outputFolderUri, true)) {
                "Output folder permission is not available. Re-pick the output folder and try again."
            }
        } else if (viewModel.outputDirectory.isNotBlank()) {
            val outputDir = File(viewModel.outputDirectory)
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                throw IllegalStateException("Could not create output folder: ${outputDir.absolutePath}")
            }
            if (outputDir.exists() && !outputDir.canWrite()) {
                throw IllegalStateException("Output folder is not writable: ${outputDir.absolutePath}")
            }
        }
    }
    fun buildMergeOutputPath(): String {
        val base = mergeOutputName.trim().ifEmpty { "merged_output" }
        val ext = extForMergeType(mergeType)
        val fixed = if (base.lowercase().endsWith(".${ext}")) base else "${base}.${ext}"
        return File(viewModel.outputDirectory, fixed).absolutePath
    }
    fun buildArchiveOutputPath(): String {
        val ext = archiveOutputExt(archiveInput, archiveMode)
        return File(viewModel.outputDirectory, outputNameWithExt(archiveOutputName, ext)).absolutePath
    }
    fun uniqueOutputPath(fileName: String): String {
        val first = File(viewModel.outputDirectory, fileName)
        if (!first.exists()) return first.absolutePath

        val ext = fileName.substringAfterLast('.', "")
        val stem = if (ext.isBlank()) fileName else fileName.removeSuffix(".$ext")
        var index = 2
        while (true) {
            val candidateName = if (ext.isBlank()) "$stem ($index)" else "$stem ($index).$ext"
            val candidate = File(viewModel.outputDirectory, candidateName)
            if (!candidate.exists()) return candidate.absolutePath
            index += 1
        }
    }
    fun tempOutputPathFor(finalPath: String): String {
        val file = File(finalPath)
        val ext = file.extension
        val name = file.nameWithoutExtension
        val parent = operationTempRoot()
        val tempName = if (ext.isBlank()) "$name.tmp" else "$name.tmp.$ext"
        return File(parent, tempName).absolutePath
    }
    fun prepareTempOutput(finalPath: String): String {
        val tempPath = tempOutputPathFor(finalPath)
        File(tempPath).delete()
        try {
            File(tempPath).parentFile?.mkdirs()
            FileOutputStream(tempPath).use { }
        } catch (err: Throwable) {
            throw IllegalStateException(
                "Could not create temp file at $tempPath: ${err.message}",
                err
            )
        }
        File(tempPath).delete()
        return tempPath
    }
    fun outputTreeDocument(): DocumentFile? {
        val treeUri = viewModel.outputFolderUri.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
        return DocumentFile.fromTreeUri(context, treeUri)
    }
    fun outputDocumentUriForName(fileName: String): Uri? {
        val root = outputTreeDocument() ?: return null
        val mime = when (extensionForPath(fileName)) {
            "nsp", "nsz", "xci", "xcz" -> "application/octet-stream"
            else -> "application/octet-stream"
        }
        val existing = root.listFiles().firstOrNull { it.name == fileName }
        existing?.delete()
        return root.createFile(mime, fileName)?.uri
    }

    fun outputDocumentExists(fileName: String): Boolean {
        val root = outputTreeDocument() ?: return false
        return root.listFiles().any { it.name == fileName }
    }
    suspend fun copyLocalFileToUri(localPath: String, uri: Uri) {
        withContext(Dispatchers.IO) {
            val src = File(localPath)
            require(src.exists()) { "Merged file not found at $localPath" }
            context.contentResolver.openOutputStream(uri, "wt").use { out ->
                requireNotNull(out) { "Failed to open output destination" }
                FileInputStream(src).use { input ->
                    input.copyTo(out)
                }
            }
        }
    }

    suspend fun copySourceRefToUri(sourceRef: String, uri: Uri) {
        withContext(Dispatchers.IO) {
            val sourceUri = runCatching { Uri.parse(sourceRef) }.getOrNull()
            when {
                sourceUri?.scheme == "content" -> {
                    context.contentResolver.openInputStream(sourceUri).use { input ->
                        requireNotNull(input) { "Failed to open source file: $sourceRef" }
                        context.contentResolver.openOutputStream(uri, "wt").use { out ->
                            requireNotNull(out) { "Failed to open output destination" }
                            input.copyTo(out)
                        }
                    }
                }
                sourceUri?.scheme == "file" -> {
                    val srcPath = sourceUri.path ?: sourceRef
                    FileInputStream(File(srcPath)).use { input ->
                        context.contentResolver.openOutputStream(uri, "wt").use { out ->
                            requireNotNull(out) { "Failed to open output destination" }
                            input.copyTo(out)
                        }
                    }
                }
                else -> {
                    FileInputStream(File(sourceRef)).use { input ->
                        context.contentResolver.openOutputStream(uri, "wt").use { out ->
                            requireNotNull(out) { "Failed to open output destination" }
                            input.copyTo(out)
                        }
                    }
                }
            }
        }
    }

    suspend fun copySourceRefToPath(sourceRef: String, destinationPath: String) {
        withContext(Dispatchers.IO) {
            val sourceUri = runCatching { Uri.parse(sourceRef) }.getOrNull()
            when {
                sourceUri?.scheme == "content" -> {
                    context.contentResolver.openInputStream(sourceUri).use { input ->
                        requireNotNull(input) { "Failed to open source file: $sourceRef" }
                        FileOutputStream(destinationPath).use { out ->
                            input.copyTo(out)
                        }
                    }
                }
                sourceUri?.scheme == "file" -> {
                    val srcPath = sourceUri.path ?: sourceRef
                    FileInputStream(File(srcPath)).use { input ->
                        FileOutputStream(destinationPath).use { out ->
                            input.copyTo(out)
                        }
                    }
                }
                else -> {
                    FileInputStream(File(sourceRef)).use { input ->
                        FileOutputStream(destinationPath).use { out ->
                            input.copyTo(out)
                        }
                    }
                }
            }
        }
    }
    suspend fun finalizeTempOutput(tempPath: String, finalPath: String) {
        val temp = File(tempPath)
        require(temp.exists()) { "Temp output file missing: $tempPath" }

        // App-private paths: rename is safe and avoids a copy
        val privatePrefixes = listOfNotNull(
            context.filesDir?.parentFile?.absolutePath,
            context.filesDir?.absolutePath,
            context.cacheDir?.absolutePath,
            context.externalCacheDir?.absolutePath,
            context.getExternalFilesDir(null)?.absolutePath
        ).map { it.trimEnd('/') + "/" }
        if (privatePrefixes.any { finalPath.startsWith(it) }) {
            val final = File(finalPath)
            final.delete()
            require(temp.renameTo(final)) { "Could not move output to: $finalPath" }
            return
        }

        // Try direct write first — works on internal storage and permissive device ROMs (e.g. Retroid).
        // SAF grants are revoked on app reinstall so this path is more reliable when the ROM allows it.
        val directOk = runCatching {
            withContext(Dispatchers.IO) {
                File(finalPath).parentFile?.mkdirs()
                FileOutputStream(finalPath).use { out ->
                    FileInputStream(temp).use { inp -> inp.copyTo(out) }
                }
            }
        }.isSuccess
        if (directOk) {
            temp.delete()
            return
        }

        // SAF fallback — required on strict Android 10+ scoped storage when direct write is blocked
        if (usesSafOutputFolder()) {
            val finalName = File(finalPath).name
            val destUri = outputDocumentUriForName(finalName)
                ?: throw IllegalStateException(
                    "Cannot write to output folder. Re-pick the output folder to refresh permissions."
                )
            copyLocalFileToUri(temp.absolutePath, destUri)
            temp.delete()
            return
        }

        throw IllegalStateException(
            "Cannot write to $finalPath — direct write blocked and no SAF folder configured. Use the folder picker to set an output folder."
        )
    }
    fun formatSize(size: Long): String {
        val units = listOf("B", "KB", "MB", "GB")
        var value = size.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit += 1
        }
        return if (unit == 0) "${size} B" else String.format(Locale.US, "%.1f %s", value, units[unit])
    }
    fun formatModified(time: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(time))
    }
    fun expectedMergeFloor(inputPaths: List<String>): Long {
        val largestInput = inputPaths
            .mapNotNull { path -> File(path).takeIf { it.exists() && it.isFile }?.length() }
            .maxOrNull()
            ?: 0L
        return largestInput / 2
    }
    fun requireCompletedOutput(path: String, inputPaths: List<String> = emptyList()): Long {
        val file = File(path)
        require(file.exists()) { "Merge reported success but output file is missing: $path" }
        val size = file.length()
        require(size > 16 * 1024) { "Merge output is incomplete (${formatSize(size)}): $path" }
        val floor = expectedMergeFloor(inputPaths)
        require(floor == 0L || size >= floor) {
            "Merge output is suspiciously small (${formatSize(size)}). Largest input was ${formatSize(floor * 2)}; refusing to keep a likely partial merge."
        }
        return size
    }

    suspend fun getFileNameFromUri(uri: Uri): String? {
        return withContext(Dispatchers.IO) {
            var name: String? = null
            if (uri.scheme == "content") {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            name = cursor.getString(index)
                        }
                    }
                }
            }
            name ?: uri.path?.substringAfterLast('/')
        }
    }

    fun displayNameForRef(ref: String): String {
        return if (ref.startsWith("content://")) {
            Uri.parse(ref).lastPathSegment ?: ref
        } else {
            File(ref).name
        }
    }

    fun isAppPrivatePath(path: String): Boolean {
        val abs = File(path).absolutePath
        val roots = listOfNotNull(
            context.filesDir?.absolutePath,
            context.cacheDir?.absolutePath,
            context.externalCacheDir?.absolutePath,
            context.getExternalFilesDir(null)?.absolutePath
        )
        return roots.any { abs.startsWith(it) }
    }

    fun externalStorageDocumentPath(documentId: String): String? {
        val split = documentId.split(":", limit = 2)
        if (split.isEmpty()) return null

        val type = split[0]
        val relativePath = split.getOrNull(1).orEmpty()
        val root = if ("primary".equals(type, ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$type"
        }

        return if (relativePath.isBlank()) root else "$root/$relativePath"
    }

    fun resolvedLocalPathForUri(uri: Uri): String? {
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path?.takeIf { File(it).isFile && File(it).canRead() }
        }
        // Child file URIs inside a tree match BOTH isTreeUri and isDocumentUri.
        // Must use the specific documentId so we map the file, not the parent tree.
        val documentId = when {
            DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.getDocumentId(uri)
            DocumentsContract.isTreeUri(uri) -> DocumentsContract.getTreeDocumentId(uri)
            else -> return null
        }
        val rawPath = externalStorageDocumentPath(documentId)
        return rawPath?.takeIf { File(it).isFile && File(it).canRead() }
    }

    suspend fun getSourceRefSize(sourceRef: String): Long? {
        return withContext(Dispatchers.IO) {
            val sourceUri = runCatching { Uri.parse(sourceRef) }.getOrNull()
            when {
                sourceUri?.scheme == "content" -> {
                    context.contentResolver.query(sourceUri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (index != -1 && !cursor.isNull(index)) {
                                return@withContext cursor.getLong(index)
                            }
                        }
                    }
                    null
                }
                sourceUri?.scheme == "file" -> {
                    val srcPath = sourceUri.path ?: sourceRef
                    File(srcPath).takeIf { it.isFile }?.length()
                }
                else -> File(sourceRef).takeIf { it.isFile }?.length()
            }
        }
    }

    fun isPermissionLikeError(error: Throwable): Boolean {
        val text = buildString {
            append(error.message.orEmpty())
            append(' ')
            append(error.toString())
            error.cause?.message?.let {
                append(' ')
                append(it)
            }
        }.lowercase()
        return listOf("eperm", "eacces", "permission denied", "no permission granted", "securityexception", "failed to open", "access denied")
            .any { token -> text.contains(token) }
    }

    suspend fun copyUriToCache(uri: Uri, label: String, targetDir: File? = null): String {
        return withContext(Dispatchers.IO) {
            val fileName = getFileNameFromUri(uri)
            val extension = fileName?.substringAfterLast('.', "")?.lowercase()
                ?.ifBlank {
                    context.contentResolver.getType(uri)
                        ?.substringAfterLast('/')
                        ?.ifBlank { "bin" }
                        ?: "bin"
                } ?: "bin"
            val parentDir = targetDir ?: operationTempRoot()
            parentDir.mkdirs()
            val sourceSize = getSourceRefSize(uri.toString())
            if (sourceSize != null && sourceSize > parentDir.usableSpace) {
                throw IllegalStateException(
                    "Not enough temp space on ${parentDir.absolutePath}. Need ${formatSize(sourceSize)}, available ${formatSize(parentDir.usableSpace)}."
                )
            }
            val dst = File(parentDir, "${label}_${UUID.randomUUID()}.${extension}")
            Log.i(NSCB_LOG_TAG, "Staging ${displayNameForRef(uri.toString())} to ${dst.absolutePath} (free ${formatSize(parentDir.usableSpace)})")
            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    if (input == null) {
                        throw IllegalStateException("Failed to open source file: ${displayNameForRef(uri.toString())}")
                    }
                    FileOutputStream(dst).use { out ->
                        input.copyTo(out)
                    }
                }
            } catch (primary: Throwable) {
                if (!isPermissionLikeError(primary)) throw primary
                // USB OTG tree permission can appear valid but child stream open fails.
                // Try via ParcelFileDescriptor (different OS codepath).
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                            FileOutputStream(dst).use { out ->
                                input.copyTo(out)
                            }
                        }
                    } ?: throw IllegalStateException("openFileDescriptor returned null")
                } catch (secondary: Throwable) {
                    if (!isPermissionLikeError(secondary)) throw secondary
                    // Reconstruct child URI under the tree explicitly; some vendors require this.
                    try {
                        val docId = DocumentsContract.getDocumentId(uri)
                        val treeUri = viewModel.lastScanUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                            ?: throw IllegalStateException("No tree URI available for DocumentsContract fallback")
                        val rebuiltUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        context.contentResolver.openInputStream(rebuiltUri).use { input ->
                            if (input == null) throw IllegalStateException("Rebuilt URI stream null")
                            FileOutputStream(dst).use { out ->
                                input.copyTo(out)
                            }
                        }
                    } catch (tertiary: Throwable) {
                        throw IllegalStateException(
                            "Permission error reading ${displayNameForRef(uri.toString())} from removable storage. " +
                            "The USB drive may have been remounted. Re-pick the import folder (keep it connected) and try again.",
                            tertiary
                        )
                    }
                }
            }
            dst.absolutePath
        }
    }

    suspend fun ensureNativeReadable(uriOrPath: String, label: String, targetDir: File? = null): String {
        if (uriOrPath.startsWith("content://")) {
            val raw = resolvedLocalPathForUri(Uri.parse(uriOrPath))
            if (raw != null) return raw
            return copyUriToCache(Uri.parse(uriOrPath), label, targetDir)
        }
        val f = File(uriOrPath)
        if (f.exists() && f.canRead()) { return uriOrPath }
        if (isAppPrivatePath(uriOrPath)) { return uriOrPath }
        val attemptUri = runCatching { Uri.fromFile(f) }.getOrNull()
            ?: throw IllegalStateException("Cannot read $uriOrPath and cannot convert to content URI. Re-pick the folder and grant full access.")
        return copyUriToCache(attemptUri, label, targetDir)
    }

    suspend fun importUriToCache(uri: Uri, label: String, targetDir: File? = null): String {
        val scheme = uri.scheme
        if (scheme == null || scheme == "file") {
            val path = uri.path ?: return ""
            if (File(path).exists() && File(path).canRead()) return path
            if (isAppPrivatePath(path)) return path
            val f = File(path)
            val attemptUri = runCatching { Uri.fromFile(f) }.getOrNull()
                ?: throw IllegalStateException("Cannot read $path and cannot convert to content URI. Re-pick the folder and grant full access.")
            return copyUriToCache(attemptUri, label, targetDir)
        }
        val raw = resolvedLocalPathForUri(uri)
        if (raw != null) return raw
        return copyUriToCache(uri, label, targetDir)
    }

    fun isOutputPermissionError(err: Throwable): Boolean {
        val text = buildString {
            append(err.message.orEmpty())
            append(' ')
            append(err.toString())
            err.cause?.message?.let {
                append(' ')
                append(it)
            }
        }.lowercase()
        return listOf("copy failed", "output", "write", "open failed", "eperm", "eacces", "permission denied")
            .any { token -> text.contains(token) }
    }

    fun isAppCachePath(path: String): Boolean {
        val abs = File(path).absolutePath
        return listOfNotNull(
            context.cacheDir.absolutePath,
            context.externalCacheDir?.absolutePath,
            *operationTempCandidates().map { it.absolutePath }.toTypedArray()
        ).any { root -> abs.startsWith(root.trimEnd('/') + "/") }
    }

    fun deleteIfAppCachePath(path: String) {
        if (isAppCachePath(path)) {
            File(path).delete()
        }
    }

    fun clearTempFilesWithPrefixes(root: File, prefixes: Set<String>) {
        root.listFiles()?.forEach { file ->
            if (prefixes.any { prefix -> file.name.startsWith(prefix) }) {
                file.deleteRecursively()
            }
        }
    }

    fun clearOperationTempCache() {
        val prefixes = setOf(
            "merge_input_",
            "temp_name_suggest_",
            "archive_input_",
            "info_input_",
            "content_input_",
            "import_game_",
            "fault_scan_",
            "library_scan_"
        )
        clearTempFilesWithPrefixes(context.cacheDir, prefixes)
        context.externalCacheDir?.let { clearTempFilesWithPrefixes(it, prefixes) }
        operationTempCandidates().forEach { dir ->
            if (dir.exists()) dir.listFiles()?.forEach { f -> f.deleteRecursively() }
        }
        configureNativeTempRoot()
    }

    fun clearMergeTempCache() {
        clearOperationTempCache()
        if (viewModel.outputDirectory.isBlank()) return
        File(viewModel.outputDirectory).listFiles()?.forEach { file ->
            if (file.isFile && (file.name.endsWith(".part") || file.name.contains(".tmp."))) {
                file.delete()
            }
        }
    }

    fun deleteLocalSourceFiles(paths: List<String>): Boolean {
        var allDeleted = true
        paths.distinct().forEach { path ->
            if (path.startsWith("content://")) {
                val uri = Uri.parse(path)
                var deleted = DocumentFile.fromSingleUri(context, uri)?.delete() ?: false
                if (!deleted) {
                    deleted = runCatching {
                        DocumentsContract.deleteDocument(context.contentResolver, uri)
                    }.getOrDefault(false)
                }
                if (!deleted) {
                    appendLog("Failed to delete SAF source: $path")
                    allDeleted = false
                }
            } else {
                val result = NscbBridge.deleteFile(path)
                if (result.startsWith("ERROR")) {
                    appendLog(result)
                    allDeleted = false
                }
            }
        }
        return allDeleted
    }

    fun promptDuplicateDelete(paths: List<String>, contextLabel: String, decision: CompletableDeferred<Boolean>? = null) {
        pendingDuplicateDeletePaths = paths.distinct()
        pendingDuplicateDeleteContext = contextLabel
        pendingDuplicateDeleteDecision = decision
        showDuplicateDeletePrompt = true
        showConsoleModal = true
        appendLog("Duplicate files detected during $contextLabel: ${paths.distinct().size} file(s)")
    }

    fun requireReadableFiles(paths: List<String>, label: String) {
        val missing = paths.firstOrNull { path ->
            path.isBlank() || !File(path).isFile || !File(path).canRead()
        }
        require(missing == null) {
            "$label is missing or unreadable: $missing"
        }
    }

    fun filenameForSingleImport(inputPath: String, fallbackName: String): String {
        val inputExt = extensionForPath(inputPath).ifBlank { extForMergeType(mergeType) }
        val cleanName = fallbackName.trim().ifEmpty {
            File(inputPath).nameWithoutExtension.ifBlank { "imported_package" }
        }
        return if (cleanName.lowercase().endsWith(".$inputExt")) cleanName else "$cleanName.$inputExt"
    }

    fun stripSupportedPackageExt(name: String): String {
        val ext = extensionForPath(name)
        return if (ext in setOf("nsp", "nsz", "xci", "xcz")) name.substringBeforeLast('.') else name
    }

    fun isTempDerivedPackageName(name: String): Boolean {
        val stem = File(name).nameWithoutExtension.lowercase()
        return listOf(
            "merge_input_",
            "temp_name_suggest_",
            "archive_input_",
            "info_input_",
            "content_input_",
            "import_game_",
            "fault_scan_",
            "library_scan_"
        ).any { stem.startsWith(it) }
    }

    fun replaceTempDerivedPackageStem(suggestedName: String, originalName: String): String {
        if (!isTempDerivedPackageName(suggestedName)) return suggestedName
        val originalStem = stripSupportedPackageExt(originalName).ifBlank { "imported_package" }
        val suggestedExt = extensionForPath(suggestedName)
            .ifBlank { extensionForPath(originalName).ifBlank { extForMergeType(mergeType) } }
        val suffixStart = suggestedName.indexOf(" [")
        val suffix = if (suffixStart >= 0) suggestedName.substring(suffixStart).substringBeforeLast('.', "") else ""
        val fixed = "$originalStem$suffix"
        return if (fixed.lowercase().endsWith(".$suggestedExt")) fixed else "$fixed.$suggestedExt"
    }

    suspend fun copyFilePath(sourcePath: String, destinationPath: String) {
        ensureFileOperationStorage()
        val src = File(sourcePath)
        require(src.isFile && src.canRead()) { "Import input is missing or unreadable: $sourcePath" }
        val tempOutputPath = prepareTempOutput(destinationPath)
        try {
            try {
                FileInputStream(src).use { input ->
                    FileOutputStream(tempOutputPath).use { outputStream ->
                        input.copyTo(outputStream)
                    }
                }
            } catch (err: Throwable) {
                throw IllegalStateException(
                    "Copy failed from $sourcePath to $tempOutputPath: ${err.message}",
                    err
                )
            }
            requireCompletedOutput(tempOutputPath, listOf(sourcePath))
            finalizeTempOutput(tempOutputPath, destinationPath)
        } catch (err: Throwable) {
            File(tempOutputPath).delete()
            throw err
        }
    }

    suspend fun importSingleToPath(inputLine: String, outputPath: String): String {
        ensureFileOperationStorage()
        setProgress(if (viewModel.analyzePackageBeforeImport) {
            "Preparing analyzed package import"
        } else {
            "Preparing original file move"
        })
        try {
            val outputExists = File(outputPath).exists() ||
                (usesSafOutputFolder() && outputDocumentExists(File(outputPath).name))
            if (outputExists) {
                promptDuplicateDelete(listOf(inputLine), "import folder")
                error("Target already exists in output library: ${File(outputPath).name}")
            }
            // Try direct write first; SAF fallback for strict scoped-storage enforcement
            val directOk = runCatching {
                withContext(Dispatchers.IO) {
                    File(outputPath).parentFile?.mkdirs()
                    copySourceRefToPath(inputLine, outputPath)
                }
            }.isSuccess
            val size = if (directOk) {
                setProgress(if (viewModel.analyzePackageBeforeImport) {
                    "Writing analyzed package to output library"
                } else {
                    "Moving original file to output library"
                })
                File(outputPath).length()
            } else if (usesSafOutputFolder()) {
                val destUri = outputDocumentUriForName(File(outputPath).name)
                    ?: throw IllegalStateException("Cannot write to output folder. Re-pick the output folder to refresh permissions.")
                setProgress(if (viewModel.analyzePackageBeforeImport) {
                    "Writing analyzed package to output library (SAF)"
                } else {
                    "Moving original file to output library (SAF)"
                })
                withContext(Dispatchers.IO) { copySourceRefToUri(inputLine, destUri) }
                getSourceRefSize(inputLine) ?: File(outputPath).length()
            } else {
                throw IllegalStateException("Cannot write to $outputPath — direct write blocked and no SAF folder configured.")
            }
            setProgress("Import complete: ${formatSize(size)}")
            if (viewModel.deleteSourcesAfterMerge) {
                setProgress("Deleting source file")
                deleteLocalSourceFiles(listOf(inputLine))
            }
            if (inputLine.startsWith("content://")) {
                val uri = Uri.parse(inputLine)
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            return "Imported package\nSaved ${formatSize(size)} to $outputPath"
        } catch (err: Throwable) {
            appendLog("Import failed: ${err.message}")
            throw err
        }
    }

    fun uriToPath(uri: Uri): String? {
        if (DocumentsContract.isTreeUri(uri)) {
            return externalStorageDocumentPath(DocumentsContract.getTreeDocumentId(uri))
        } else if (DocumentsContract.isDocumentUri(context, uri)) {
            return externalStorageDocumentPath(DocumentsContract.getDocumentId(uri))
        } else if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }
        return null
    }

    fun writableFilePathForUri(uri: Uri): String? {
        if (!"file".equals(uri.scheme, ignoreCase = true)) {
            return null
        }
        val path = uriToPath(uri) ?: return null
        val parent = File(path).parentFile ?: return null
        return path.takeIf { parent.exists() && parent.canWrite() }
    }

    val pickKeys = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            settingsRunning = true
            output = "Importing prod.keys..."
            scope.launch {
                runCatching {
                    val keysDir = File(context.filesDir, "keys")
                    keysDir.mkdirs()
                    keysDir.listFiles()?.forEach { file ->
                        if (file.name.startsWith("prod_keys_")) file.delete()
                    }
                    val path = importUriToCache(uri, "prod_keys", keysDir)
                    viewModel.updateKeysPath(path)
                    output = "prod.keys imported"
                }.onFailure {
                    output = "ERROR: Failed to import prod.keys: ${it.message}"
                }
                settingsRunning = false
            }
        }
    }

    val pickMergeInputs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            mergeRunning = true
            output = "Updating merge input files..."
            scope.launch {
                runCatching {
                    mergeInputs = uris.joinToString("\n") { it.toString() }
                    
                    withContext(Dispatchers.IO) { clearOperationTempCache() }
                    val tempPaths = uris.map { importUriToCache(it, "temp_name_suggest") }
                    val suggested = withContext(Dispatchers.IO) {
                        NscbBridge.getSuggestedFileName(tempPaths.joinToString("\n"), viewModel.keysPath, titleDbCacheDir, mergeType)
                    }
                    mergeOutputName = suggested
                    tempPaths.forEach { deleteIfAppCachePath(it) }

                    output = "Selected ${uris.size} merge file(s). Suggested name: $suggested"
                }.onFailure {
                    output = "ERROR: ${it.message}"
                }
                mergeRunning = false
            }
        }
    }

    val pickMergeOutput = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            mergeOutputUri = uri
            output = "Selected output destination: ${uri.path}"
        }
    }

    val pickSingleInput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            singleFileInput = uri.toString()
            output = "File selected"
        }
    }

    val pickArchiveInput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            archiveInput = uri.toString()
            val name = uri.path?.substringAfterLast('/')?.substringBeforeLast('.') ?: "converted"
            archiveOutputName = name
            archiveOutputUri = null
            output = "Archive input selected"
        }
    }

    val pickArchiveOutput = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            archiveOutputUri = uri
            output = "Selected archive output destination: ${uri.path}"
        }
    }

    val pickScanFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.updateLastScanUri(uri.toString())
            refreshPermissionWizardState()
            val path = uriToPath(uri)
            if (path != null) {
                viewModel.updateLastScanPath(path)
                output = "Folder selected: $path"
            } else {
                output = "ERROR: Could not resolve local path from folder URI."
            }
        }
    }

    val pickOutputFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.updateOutputFolderUri(uri.toString())
            val path = uriToPath(uri)
            if (path != null) {
                viewModel.updateOutputDirectory(path)
                output = "Output folder selected: $path"
            } else {
                output = "ERROR: Could not resolve local path from output folder URI."
            }
            refreshPermissionWizardState()
        }
    }

    val requestStoragePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        // Permission result is handled naturally by the app via SAF folder picking; this just satisfies Play Store requirement
        appendLog("Storage permissions updated")
    }

    fun ensureStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return // scoped storage / SAF only from Android 13+
        }
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 &&
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (missing.isNotEmpty()) {
            requestStoragePermission.launch(missing.toTypedArray())
        }
    }

    fun requireKeysOrError(action: () -> Unit) {
        if (viewModel.keysPath.isBlank()) {
            output = "ERROR: prod.keys path is required. Go to Settings."
            selectedTabIndex = 5
            return
        }
        action()
    }

    fun promptFaultyDelete(
        paths: List<String>,
        contextLabel: String,
        decision: CompletableDeferred<Boolean>? = null
    ) {
        pendingFaultyDeletePaths = paths.distinct()
        pendingFaultyDeleteContext = contextLabel
        pendingFaultyDeleteDecision = decision
        showFaultyDeletePrompt = true
        showConsoleModal = true
        appendLog("Faulty files detected during $contextLabel: ${paths.distinct().size} file(s)")
    }

    fun bestMergeFilesForGroup(group: ScanGroup): List<ScanFile> {
        return group.items
            .filter { item ->
                !viewModel.ignoreXciInLibraryMerge || extensionForPath(item.path) !in setOf("xci", "xcz")
            }
            .groupBy { it.titleId }
            .map { (_, items) -> items.maxBy { it.version } }
    }

    fun collectDuplicateLibraryPaths(groups: List<ScanGroup>): List<String> {
        return groups.flatMap { group ->
            group.items
                .groupBy { it.titleId }
                .values
                .flatMap { sameTitle ->
                    sameTitle
                        .groupBy { it.version }
                        .values
                        .flatMap { sameVersion ->
                            sameVersion.sortedWith(
                                compareBy<ScanFile> { it.filename.lowercase() }
                                    .thenBy { it.path.lowercase() }
                            ).drop(1)
                        }
                }
        }.map { it.path }.distinct()
    }

    fun isSupportedPackageName(name: String?): Boolean {
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return ext in setOf("nsp", "nsz", "xci", "xcz")
    }

    fun collectMergeFilesFromInputLibrary(): List<String> {
        val treeUri = viewModel.lastScanUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
        if (treeUri == null) return emptyList()
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val docs = mutableListOf<DocumentFile>()
        fun walk(doc: DocumentFile) {
            if (doc.isFile) {
                if (isSupportedPackageName(doc.name)) docs.add(doc)
                return
            }
            if (!doc.isDirectory) return
            doc.listFiles().forEach { child -> walk(child) }
        }
        walk(rootDoc)
        return docs
            .sortedBy { it.name?.lowercase().orEmpty() }
            .map { doc -> resolvedLocalPathForUri(doc.uri) ?: doc.uri.toString() }
    }

    fun loadImportFolderIntoMergeInputs() {
        val files = collectMergeFilesFromInputLibrary()
        mergeInputs = files.joinToString("\n")
        output = if (files.isEmpty()) {
            "No supported package files found in import folder."
        } else {
            "Loaded ${files.size} file(s) from import folder."
        }
    }

    fun collectSupportedDocumentFiles(root: DocumentFile): List<DocumentFile> {
        val results = mutableListOf<DocumentFile>()
        fun walk(doc: DocumentFile) {
            if (doc.isFile) {
                if (isSupportedPackageName(doc.name)) {
                    results.add(doc)
                }
                return
            }
            if (!doc.isDirectory) return
            doc.listFiles().forEach { child -> walk(child) }
        }
        walk(root)
        return results.sortedBy { it.name?.lowercase().orEmpty() }
    }

    fun collectSupportedLibraryDocumentFiles(root: DocumentFile): List<DocumentFile> {
        val supported = setOf("nsp", "nsz", "xci", "xcz", "nca", "ncz")
        val results = mutableListOf<DocumentFile>()
        fun walk(doc: DocumentFile) {
            if (doc.isFile) {
                val ext = extensionForPath(doc.name.orEmpty())
                if (ext in supported) {
                    results.add(doc)
                }
                return
            }
            if (!doc.isDirectory) return
            doc.listFiles().forEach { child -> walk(child) }
        }
        walk(root)
        return results.sortedByDescending { it.lastModified() }
    }

    suspend fun scanDirectoryFromDocumentTree(root: DocumentFile): String {
        val tempDir = File(operationTempRoot(), "library_scan_${UUID.randomUUID()}")
        tempDir.mkdirs()
        try {
            val docs = withContext(Dispatchers.IO) { collectSupportedLibraryDocumentFiles(root) }
            withContext(Dispatchers.IO) {
                clearOperationTempCache()
                docs.forEach { doc ->
                    importUriToCache(doc.uri, "library_scan", tempDir)
                }
            }
            return withContext(Dispatchers.IO) {
                NscbBridge.scanDirectory(tempDir.absolutePath, viewModel.keysPath, titleDbCacheDir)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun pruneEmptyDocumentFolders(root: DocumentFile): Boolean {
        if (!root.isDirectory) return false
        var hasContent = false
        root.listFiles().forEach { child ->
            when {
                child.isDirectory -> {
                    val childHasContent = pruneEmptyDocumentFolders(child)
                    if (!childHasContent) {
                        child.delete()
                    } else {
                        hasContent = true
                    }
                }
                child.isFile -> hasContent = true
            }
        }
        return hasContent
    }

    fun parseScanGroups(json: String): List<ScanGroup> {
        val groups = mutableListOf<ScanGroup>()
        val arr = JSONArray(json)
        appendLog("Parsing ${arr.length()} scanned title group(s)")
        for (i in 0 until arr.length()) {
            val groupObj = arr.getJSONObject(i)
            val itemsArr = groupObj.getJSONArray("items")
            val itemsList = mutableListOf<ScanFile>()
            for (j in 0 until itemsArr.length()) {
                val itemObj = itemsArr.getJSONObject(j)
                itemsList.add(ScanFile(
                    path = itemObj.getString("path"),
                    filename = itemObj.getString("filename"),
                    titleId = itemObj.getString("title_id"),
                    version = itemObj.getLong("version"),
                    kind = itemObj.getString("kind")
                ))
            }
            groups.add(ScanGroup(
                baseId = groupObj.getString("base_id"),
                titleName = groupObj.getString("title_name"),
                latestVersionDb = groupObj.optLong("latest_version_db", 0),
                items = itemsList
            ))
        }
        return groups
    }

    suspend fun scanFaultyFilesAt(path: String): List<FaultyFile> {
        val json = withContext(Dispatchers.IO) {
            NscbBridge.scanFaultyFiles(path, viewModel.keysPath)
        }
        if (json.startsWith("ERROR")) {
            output = json
            return emptyList()
        }
        val results = mutableListOf<FaultyFile>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            results.add(
                FaultyFile(
                    path = item.getString("path"),
                    filename = item.getString("filename"),
                    reason = item.getString("reason")
                )
            )
        }
        return results
    }

    suspend fun runMergeToPath(inputLines: List<String>, outputPath: String): String {
        require(inputLines.isNotEmpty()) { "No merge inputs selected." }
        ensureFileOperationStorage()
        withContext(Dispatchers.IO) { clearMergeTempCache() }
        if (inputLines.size == 1) {
            val localPath = if (inputLines.first().startsWith("content://") || inputLines.first().startsWith("file://")) {
                importUriToCache(Uri.parse(inputLines.first()), "merge_input")
            } else {
                ensureNativeReadable(inputLines.first(), "merge_input")
            }
            try {
                return importSingleToPath(localPath, outputPath)
            } finally {
                deleteIfAppCachePath(localPath)
            }
        }
        val selectedFaulty = faultyFiles.map { it.path }.toSet().let { bad ->
            inputLines.filter { it in bad }
        }
        require(selectedFaulty.isEmpty()) {
            "Faulty files are selected for merge. Delete them from the prompt or remove them from the merge list."
        }
        setProgress("Preparing ${inputLines.size} merge input(s)")
        val localPaths = inputLines.map { input ->
            if (input.startsWith("content://") || input.startsWith("file://")) {
                importUriToCache(Uri.parse(input), "merge_input")
            } else {
                ensureNativeReadable(input, "merge_input")
            }
        }
        requireReadableFiles(localPaths, "Merge input")
        val tempOutputPath = prepareTempOutput(outputPath)
        try {
            setProgress("Merging ${localPaths.size} input(s) to ${File(outputPath).name}")
            val mergeResult = withContext(Dispatchers.IO) {
                NscbBridge.merge(localPaths.joinToString("\n"), tempOutputPath, viewModel.keysPath, mergeType)
            }
            if (!mergeResult.startsWith("OK")) {
                File(tempOutputPath).delete()
                error(mergeResult)
            }
            val completedSize = requireCompletedOutput(tempOutputPath, localPaths)
            finalizeTempOutput(tempOutputPath, outputPath)
            if (viewModel.deleteSourcesAfterMerge) {
                setProgress("Deleting ${inputLines.distinct().size} source file(s)")
                deleteLocalSourceFiles(inputLines)
            }
            setProgress("Merge complete: ${formatSize(completedSize)}")
            return "$mergeResult\nSaved ${formatSize(completedSize)} to $outputPath"
        } catch (err: Throwable) {
            File(tempOutputPath).delete()
            appendLog("Merge failed: ${err.message}")
            throw err
        } finally {
            localPaths.forEach { p -> deleteIfAppCachePath(p) }
        }
    }

    suspend fun mergeScanGroupToOutputFolder(group: ScanGroup): String {
        ensureFileOperationStorage()
        withContext(Dispatchers.IO) { clearMergeTempCache() }
        val bestFiles = bestMergeFilesForGroup(group)
        require(bestFiles.isNotEmpty()) { "Group has no mergeable files." }
        if (bestFiles.size == 1) {
            val path = ensureNativeReadable(bestFiles.first().path, "merge_single_input")
            try {
                val fileName = if (viewModel.analyzePackageBeforeImport) {
                    setProgress("Determining output name")
                    val suggested = withContext(Dispatchers.IO) {
                        NscbBridge.getSuggestedFileName(path, viewModel.keysPath, titleDbCacheDir, mergeType)
                    }
                    filenameForSingleImport(path, suggested)
                } else {
                    File(path).name
                }
                val outputPath = File(viewModel.outputDirectory, fileName).absolutePath
                return importSingleToPath(path, outputPath)
            } finally {
                deleteIfAppCachePath(path)
            }
        }
        val badPaths = faultyFiles.map { it.path }.toSet()
        val faultyInput = bestFiles.firstOrNull { it.path in badPaths }
        require(faultyInput == null) { "Refusing to merge faulty file: ${faultyInput?.filename}" }

        if (!usesSafOutputFolder()) {
            File(viewModel.outputDirectory).mkdirs()
        }
        val localPaths = bestFiles.map { ensureNativeReadable(it.path, "merge_group_input") }
        val pathsJoined = localPaths.joinToString("\n")
        requireReadableFiles(localPaths, "Merge input")
        try {
            setProgress("Generating output name for ${group.titleName}")
            val suggested = withContext(Dispatchers.IO) {
                NscbBridge.getSuggestedFileName(pathsJoined, viewModel.keysPath, titleDbCacheDir, mergeType)
            }
            val outputPath = uniqueOutputPath(suggested)
            val tempOutputPath = prepareTempOutput(outputPath)
            setProgress("Merging ${bestFiles.size} file(s): ${group.titleName}")
            val result = withContext(Dispatchers.IO) {
                NscbBridge.merge(pathsJoined, tempOutputPath, viewModel.keysPath, mergeType)
            }
            if (!result.startsWith("OK")) {
                File(tempOutputPath).delete()
                error(result)
            }
            val completedSize = requireCompletedOutput(tempOutputPath, localPaths)
            finalizeTempOutput(tempOutputPath, outputPath)
            if (viewModel.deleteSourcesAfterMerge) {
                setProgress("Deleting ${bestFiles.size} source file(s)")
                deleteLocalSourceFiles(bestFiles.map { it.path })
            }
            setProgress("Merge complete: ${group.titleName} (${formatSize(completedSize)})")
            return "Merged ${group.titleName}\nSaved ${formatSize(completedSize)} to $outputPath"
        } finally {
            localPaths.forEach { p -> deleteIfAppCachePath(p) }
        }
    }

    suspend fun refreshLibrary() {
        val existingByPath = libraryFiles.associateBy { it.path }
        withContext(Dispatchers.IO) {
            val mapped = mutableListOf<LibraryFile>()
            if (usesSafOutputFolder()) {
                val root = outputTreeDocument()
                val docs = if (root != null) collectSupportedLibraryDocumentFiles(root) else emptyList()
                withContext(Dispatchers.Main) {
                    setProgress("Loading library from ${viewModel.outputDirectory} (${docs.size} file(s))")
                }
                for ((index, doc) in docs.withIndex()) {
                    if (index == 0 || (index + 1) % 10 == 0 || index + 1 == docs.size) {
                        withContext(Dispatchers.Main) {
                            appendLog("[${index + 1}/${docs.size}] Indexing ${doc.name ?: doc.uri.toString()}")
                        }
                    }
                    val path = doc.uri.toString()
                    val name = doc.name ?: doc.uri.lastPathSegment ?: "file"
                    val size = doc.length()
                    val modified = doc.lastModified()
                    val existing = existingByPath[path]
                    val cached = existing?.let { buildLibraryFileFromCache(it) }
                    if (cached != null) {
                        mapped.add(cached)
                    } else {
                        mapped.add(
                            existing?.takeIf { it.size == size && it.modified == modified }
                                ?: LibraryFile(
                                    path = path,
                                    filename = name,
                                    size = size,
                                    modified = modified,
                                    extension = extensionForPath(name).uppercase()
                                )
                        )
                    }
                }
            } else {
                val dir = File(viewModel.outputDirectory)
                val files = dir.listFiles()
                    ?.filter { file ->
                        file.isFile && extensionForPath(file.name) in setOf("nsp", "nsz", "xci", "xcz", "nca", "ncz")
                    }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()
                withContext(Dispatchers.Main) {
                    setProgress("Loading library from ${viewModel.outputDirectory} (${files.size} file(s))")
                }
                for ((index, file) in files.withIndex()) {
                    if (index == 0 || (index + 1) % 10 == 0 || index + 1 == files.size) {
                        withContext(Dispatchers.Main) {
                            appendLog("[${index + 1}/${files.size}] Indexing ${file.name}")
                        }
                    }
                    val path = file.absolutePath
                    val existing = existingByPath[path]
                    val cached = existing?.let { buildLibraryFileFromCache(it) }
                    if (cached != null) {
                        mapped.add(cached)
                    } else {
                        mapped.add(
                            existing?.takeIf { it.size == file.length() && it.modified == file.lastModified() }
                                ?: LibraryFile(
                                    path = path,
                                    filename = file.name,
                                    size = file.length(),
                                    modified = file.lastModified(),
                                    extension = extensionForPath(file.name).uppercase()
                                )
                        )
                    }
                }
            }
            withContext(Dispatchers.Main) {
                libraryFiles.clear()
                libraryFiles.addAll(mapped)
                setProgress("Library loaded: ${mapped.size} file(s)")
            }
        }
    }

    suspend fun deleteLibraryFile(path: String) {
        withContext(Dispatchers.IO) {
            if (path.startsWith("content://")) {
                deleteLocalSourceFiles(listOf(path))
            } else {
                NscbBridge.deleteFile(path)
            }
        }
        libraryFiles.removeAll { it.path == path }
        appendLog("Deleted library file: ${File(path).name}")
    }

    suspend fun checkLibraryVersions() {
        if (viewModel.keysPath.isBlank()) {
            output = "ERROR: prod.keys path is required. Go to Settings."
            selectedTabIndex = 5
            return
        }
        val unchecked = libraryFiles.mapIndexedNotNull { idx, file ->
            val cached = buildLibraryFileFromCache(file)
            if (cached != null) {
                idx to cached
            } else {
                null
            }
        }
        if (unchecked.size == libraryFiles.size) {
            libraryFiles.clear()
            libraryFiles.addAll(unchecked.map { it.second })
            output = "Library versions restored from cache."
            return
        }
        val checked = withContext(Dispatchers.IO) {
            libraryFiles.map { file ->
                val cached = buildLibraryFileFromCache(file)
                if (cached != null) {
                    return@map cached
                }
                val raw = NscbBridge.libraryStatus(file.path, viewModel.keysPath, titleDbCacheDir)
                if (raw.startsWith("ERROR")) {
                    putLibraryCacheEntry(file.path, file.size, file.modified, null, true, raw)
                    file.copy(titleSummary = raw, versionStatus = "error")
                } else {
                    putLibraryCacheEntry(file.path, file.size, file.modified, raw, false)
                    parseLibraryStatusJson(raw, file)
                }
            }
        }
        saveLibraryCache()
        libraryFiles.clear()
        libraryFiles.addAll(checked)
    }

    suspend fun performFaultScan() {
        val results = if (usesSafOutputFolder()) {
            val root = outputTreeDocument()
            if (root != null) {
                val tempDir = File(operationTempRoot(), "fault_scan_${UUID.randomUUID()}")
                tempDir.mkdirs()
                try {
                    collectSupportedLibraryDocumentFiles(root).forEach { doc ->
                        importUriToCache(doc.uri, "fault_scan", tempDir)
                    }
                    scanFaultyFilesAt(tempDir.absolutePath)
                } finally {
                    tempDir.deleteRecursively()
                }
            } else {
                scanFaultyFilesAt(viewModel.outputDirectory)
            }
        } else {
            scanFaultyFilesAt(viewModel.outputDirectory)
        }
        faultyFiles.clear()
        faultyFiles.addAll(results)
    }

    fun startLogPolling() {
        showConsoleModal = true
        scope.launch {
            while (mergeRunning || scanRunning || libraryRunning || archiveRunning || infoRunning || settingsRunning) {
                val logs = NscbBridge.getLogs()
                if (logs.isNotEmpty()) {
                    output = if (output == "Ready" || output == "Idle" || output == "Cleared") {
                        logs.trimEnd()
                    } else {
                        output + "\n" + logs.trimEnd()
                    }
                }
                kotlinx.coroutines.delay(100)
            }
        }
    }

    suspend fun performScan() {
        if (viewModel.outputDirectory.isBlank()) return
        ensureFileOperationStorage()
        setProgress("Scanning ${viewModel.outputDirectory}")
        runCatching {
            val rawOutput = viewModel.outputDirectory
            val rawReadable = rawOutput.isNotBlank() && File(rawOutput).isDirectory && File(rawOutput).canRead()
            val json = withContext(Dispatchers.IO) {
                if (usesSafOutputFolder() && !rawReadable) {
                    val root = outputTreeDocument()
                    if (root != null) {
                        scanDirectoryFromDocumentTree(root)
                    } else {
                        NscbBridge.scanDirectory(rawOutput, viewModel.keysPath, titleDbCacheDir)
                    }
                } else {
                    NscbBridge.scanDirectory(rawOutput, viewModel.keysPath, titleDbCacheDir)
                }
            }
            if (json.startsWith("ERROR")) {
                output = json
            } else {
                scanResults.clear()
                val arr = JSONArray(json)
                appendLog("Parsing ${arr.length()} scanned title group(s)")
                for (i in 0 until arr.length()) {
                    val groupObj = arr.getJSONObject(i)
                    val itemsArr = groupObj.getJSONArray("items")
                    val itemsList = mutableListOf<ScanFile>()
                    for (j in 0 until itemsArr.length()) {
                        val itemObj = itemsArr.getJSONObject(j)
                        itemsList.add(ScanFile(
                            path = itemObj.getString("path"),
                            filename = itemObj.getString("filename"),
                            titleId = itemObj.getString("title_id"),
                            version = itemObj.getLong("version"),
                            kind = itemObj.getString("kind")
                        ))
                    }
                    scanResults.add(ScanGroup(
                        baseId = groupObj.getString("base_id"),
                        titleName = groupObj.getString("title_name"),
                        latestVersionDb = groupObj.optLong("latest_version_db", 0),
                        items = itemsList
                    ))
                }
                setProgress("Checking scanned files for faults")
                performFaultScan()
                if (faultyFiles.isNotEmpty()) {
                    promptFaultyDelete(faultyFiles.map { it.path }, "scan")
                }
                duplicateLibraryPaths.clear()
                duplicateLibraryPaths.addAll(collectDuplicateLibraryPaths(scanResults))
                if (duplicateLibraryPaths.isNotEmpty()) {
                    appendLog("Duplicate copies found: ${duplicateLibraryPaths.size} file(s)")
                    promptDuplicateDelete(duplicateLibraryPaths, "library scan")
                }
                appendLog("Scan complete: ${scanResults.size} group(s), ${faultyFiles.size} faulty file(s)")
            }
        }.onFailure {
            output = "ERROR: ${it.message}"
            appendLog("Scan failed: ${it.message}")
        }
    }

    suspend fun verifyAndRenameLibrary() {
        if (viewModel.outputDirectory.isBlank()) return
        ensureFileOperationStorage()
        setProgress("Verifying game library")
        runCatching {
            performScan()
            if (faultyFiles.isNotEmpty()) {
                val paths = faultyFiles.map { it.path }
                setProgress("Verify found ${paths.size} faulty file(s)")
                promptFaultyDelete(paths, "verify library")
                output = "Verify stopped. Found ${paths.size} faulty file(s). Delete or move them, then run Verify + Rename again."
                return@runCatching
            }

            if (usesSafOutputFolder()) {
                appendLog("SAF output folder active; skipping Rust renamePath (use manual rename).")
                output = "SAF output folder active; skipping rename. Files verified."
                return@runCatching
            }

            setProgress("Renaming verified game library")
            val renameResult = withContext(Dispatchers.IO) {
                NscbBridge.renamePath(
                    viewModel.outputDirectory,
                    viewModel.keysPath,
                    titleDbCacheDir,
                    "skip_corr_tid",
                    "true",
                    "false",
                    "tag"
                )
            }
            appendLog(renameResult)

            setProgress("Refreshing renamed library")
            performScan()
            output = "Verify + rename complete. ${scanResults.size} group(s), ${faultyFiles.size} faulty file(s).\n$renameResult"
        }.onFailure {
            output = "ERROR: ${it.message}"
            appendLog("Verify + rename failed: ${it.message}")
        }
    }

    suspend fun importFolderScanAndBulkImport() {
        ensureFileOperationStorage()
        val treeUriString = viewModel.lastScanUri
        if (treeUriString.isBlank()) {
            output = "ERROR: Pick the import folder with the folder picker first."
            return
        }
        if (!hasPersistedTreePermission(treeUriString, true)) {
            output = "ERROR: Import folder permission is not available. Re-pick the import folder and try again."
            refreshPermissionWizardState()
            return
        }
        val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(treeUriString))
        if (rootDoc == null || !rootDoc.isDirectory) {
            output = "ERROR: Could not open the import folder. Re-pick it with the folder picker."
            refreshPermissionWizardState()
            return
        }
        setProgress("Collecting import files")
        val packageDocs = withContext(Dispatchers.IO) { collectSupportedDocumentFiles(rootDoc) }
        if (packageDocs.isEmpty()) {
            output = "No supported package files found in the import folder."
            return
        }
        setProgress("Scanning import folder ${viewModel.lastScanPath}")
        runCatching {
            withContext(Dispatchers.IO) { clearOperationTempCache() }
            var imported = 0
            var outputWriteFailed = false
            for ((index, doc) in packageDocs.withIndex()) {
                val name = doc.name ?: "package"
                progressStatus = "Checking package file ${index + 1}/${packageDocs.size}: $name"
                appendLog("Checking package file ${index + 1}/${packageDocs.size}: $name")
                val sourceRef = resolvedLocalPathForUri(doc.uri)
                val tempDir = if (sourceRef == null) File(operationTempRoot(), "import_game_${UUID.randomUUID()}") else null
                tempDir?.mkdirs()
                try {
                    val localPath = try {
                        sourceRef ?: importUriToCache(doc.uri, "import_game", tempDir)
                    } catch (err: Throwable) {
                        if (isPermissionLikeError(err)) {
                            val decision = CompletableDeferred<Boolean>()
                            faultyFiles.clear()
                            faultyFiles.add(
                                FaultyFile(
                                    path = doc.uri.toString(),
                                    filename = name,
                                    reason = "Permission error reading file. Re-pick the import folder or delete the file."
                                )
                            )
                            promptFaultyDelete(listOf(doc.uri.toString()), "import folder", decision)
                            output = "Could not read $name."
                            if (decision.await()) {
                                withContext(Dispatchers.IO) { pruneEmptyDocumentFolders(rootDoc) }
                            }
                            continue
                        }
                        throw err
                    }
                    val sourceLabel = sourceRef ?: doc.uri.toString()
                    val outputPath = if (viewModel.analyzePackageBeforeImport) {
                        progressStatus = "Analyzing package structure in ${index + 1}/${packageDocs.size}: $name"
                        appendLog("Analyzing package structure in: $name")
                        val readablePath = ensureNativeReadable(localPath, "import_game_check", tempDir)
                        try {
                            val faults = scanFaultyFilesAt(readablePath)
                            flushRustLogs()
                            if (faults.isNotEmpty()) {
                                val refs = listOf(sourceLabel)
                                faultyFiles.clear()
                                faultyFiles.addAll(
                                    faults.map { fault ->
                                        fault.copy(path = sourceLabel, filename = name)
                                    }
                                )
                                val decision = CompletableDeferred<Boolean>()
                                promptFaultyDelete(refs, "import folder", decision)
                                output = "Found ${faults.size} faulty file(s) in $name."
                                if (decision.await()) {
                                    deleteLocalSourceFiles(refs)
                                    withContext(Dispatchers.IO) { pruneEmptyDocumentFolders(rootDoc) }
                                }
                                continue
                            }

                            progressStatus = "Determining output filename from metadata for $name"
                            appendLog("Determining output filename from metadata: $name")
                            val suggested = withContext(Dispatchers.IO) {
                                NscbBridge.getSuggestedFileName(readablePath, viewModel.keysPath, titleDbCacheDir, mergeType)
                            }
                            flushRustLogs()
                            val fileName = replaceTempDerivedPackageStem(
                                filenameForSingleImport(readablePath, suggested),
                                name
                            )
                            File(viewModel.outputDirectory, fileName).absolutePath
                        } finally {
                            if (readablePath != localPath) {
                                deleteIfAppCachePath(readablePath)
                            }
                        }
                    } else {
                        progressStatus = "Preparing direct file move for $name"
                        appendLog("Preparing direct file move: $name")
                        File(viewModel.outputDirectory, name).absolutePath
                    }
                    if (File(outputPath).exists()) {
                        val decision = CompletableDeferred<Boolean>()
                        promptDuplicateDelete(listOf(sourceLabel), "import folder bulk", decision)
                        val deleteAndContinue = decision.await()
                        if (!deleteAndContinue) continue
                        deleteLocalSourceFiles(listOf(sourceLabel))
                        withContext(Dispatchers.IO) { pruneEmptyDocumentFolders(rootDoc) }
                        appendLog("Duplicate source deleted. Continuing import.")
                        continue
                    }
                    progressStatus = if (viewModel.analyzePackageBeforeImport) {
                        "Importing analyzed package ${index + 1}/${packageDocs.size}: $name"
                    } else {
                        "Moving original file ${index + 1}/${packageDocs.size}: $name"
                    }
                    appendLog("Importing ${index + 1}/${packageDocs.size}: $name -> ${File(outputPath).name}")
                    try {
                        importSingleToPath(localPath, outputPath)
                    } catch (err: Throwable) {
                        if (isOutputPermissionError(err)) {
                            output = "ERROR: Output folder permission error. Re-pick the output folder or restore write access."
                            appendLog("Import failed writing to output folder: ${err.message}")
                            importFolderPaused = true
                            outputWriteFailed = true
                            break
                        }
                        throw err
                    }
                    deleteLocalSourceFiles(listOf(sourceLabel))
                    withContext(Dispatchers.IO) { pruneEmptyDocumentFolders(rootDoc) }
                    imported += 1
                } finally {
                    tempDir?.deleteRecursively()
                }
            }
            if (outputWriteFailed) {
                return@runCatching
            }
            refreshLibrary()
            performScan()
            output = "Imported $imported file(s) from import folder into ${viewModel.outputDirectory}."
        }.onFailure {
            output = "ERROR: ${it.message}"
            appendLog("Import from folder failed: ${it.message}")
        }
    }

    LaunchedEffect(Unit) {
        val storageReady = withContext(Dispatchers.IO) {
            runCatching {
                configureNativeTempRoot()
                clearOperationTempCache()
                ensureFileOperationStorage()
            }
        }
        storageReady.onFailure {
            output = "ERROR: ${it.message}"
            appendLog("Startup storage check failed: ${it.message}")
            showPermissionWizard = true
            return@LaunchedEffect
        }
        if (permissionWizardReady()) {
            showPermissionWizard = false
            if (!usesSafOutputFolder()) {
                File(viewModel.outputDirectory).mkdirs()
            }
            refreshLibrary()
        } else {
            showPermissionWizard = true
        }
    }


    LaunchedEffect(selectedTabIndex) {
        if (selectedTabIndex == 4 && !libraryRunning && !isRunning && permissionWizardReady()) {
            libraryRunning = true
            progressStatus = "Loading library..."
            startLogPolling()
            try {
                refreshLibrary()
                if (libraryFiles.isNotEmpty()) {
                    checkLibraryVersions()
                }
                output = "Loaded ${libraryFiles.size} output file(s)."
            } catch (e: Exception) {
                output = "ERROR: ${e.message}"
            }
            progressStatus = "Idle"
            libraryRunning = false
        } else if (!permissionWizardReady()) {
            showPermissionWizard = true
        }
    }

    if (showConsoleModal) {
        Dialog(
            onDismissRequest = { showConsoleModal = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .fillMaxHeight(0.92f)
                    .padding(8.dp),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Title row
                    Text(
                        text = if (isRunning) "Progress" else "Console",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    // Progress section
                    if (isRunning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            progressStatus,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    // Verbose log area
                    val scrollState = rememberScrollState()
                    LaunchedEffect(output) { scrollState.animateScrollTo(scrollState.maxValue) }
                    Text(
                        text = output,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(scrollState)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                    // Actions row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { output = "Cleared" }) {
                            Text("Clear")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { showConsoleModal = false }) {
                            Text("Hide")
                        }
                    }
                }
            }
        }
    }

    if (showPermissionWizard) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Folder Permissions") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Pick the import folder (where your files come from) and the game library folder (where merged files go).")
                    Text("Internal storage persists across restarts. USB/SD card must be re-picked each session.")
                    val importStatus = when {
                        viewModel.lastScanUri.isBlank() -> "not set"
                        hasPersistedTreePermission(viewModel.lastScanUri, true) -> "granted"
                        else -> "set (tap Continue to use)"
                    }
                    val outputStatus = when {
                        viewModel.outputFolderUri.isBlank() -> "not set"
                        hasPersistedTreePermission(viewModel.outputFolderUri, true) -> "granted"
                        else -> "set (tap Continue to use)"
                    }
                        val allFilesStatus = when {
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> "n/a"
                            Environment.isExternalStorageManager() -> "allowed"
                            else -> "denied"
                        }
                        Text("Import folder: $importStatus")
                        Text("Game library folder: $outputStatus")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { pickScanFolder.launch(null) }, enabled = !isRunning) {
                                Text("Pick Import Folder")
                            }
                            Button(onClick = { pickOutputFolder.launch(null) }, enabled = !isRunning) {
                                Text("Pick Game Library")
                            }
                        }
                }
            },
            confirmButton = {
                // Enable Continue whenever both URIs are set, even if the permission check is uncertain
                val bothSet = viewModel.lastScanUri.isNotBlank() && viewModel.outputFolderUri.isNotBlank()
                Button(
                    onClick = {
                        showPermissionWizard = false
                        output = "Folders set. Ready."
                        scope.launch {
                            if (!usesSafOutputFolder()) File(viewModel.outputDirectory).mkdirs()
                            refreshLibrary()
                        }
                    },
                    enabled = bothSet && !isRunning
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionWizard = false }) {
                    Text("Skip")
                }
            }
        )
    }

    if (showDuplicateDeletePrompt) {
        AlertDialog(
            onDismissRequest = { showDuplicateDeletePrompt = false },
            title = { Text("Duplicate Files Found") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${pendingDuplicateDeletePaths.size} duplicate file(s) found during $pendingDuplicateDeleteContext.")
                    Text(
                        if (pendingDuplicateDeleteContext.contains("bulk", ignoreCase = true)) {
                            "Delete the source file(s) in the import folder, then continue the import."
                        } else if (pendingDuplicateDeleteContext.contains("import", ignoreCase = true)) {
                            "Delete the source file(s) in the import folder. The game library copy will stay."
                        } else {
                            "Delete the existing file(s) if you want to import the new copy."
                        }
                    )
                    pendingDuplicateDeletePaths.take(8).forEach { path ->
                        Text(
                            displayNameForRef(path),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (pendingDuplicateDeletePaths.size > 8) {
                        Text("Showing first 8.", style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val paths = pendingDuplicateDeletePaths
                        val decision = pendingDuplicateDeleteDecision
                        val shouldResumeImport = pendingDuplicateDeleteContext.contains("bulk", ignoreCase = true)
                        showDuplicateDeletePrompt = false
                        scope.launch {
                            startLogPolling()
                            setProgress("Deleting ${paths.size} duplicate file(s)")
                            val deleted = withContext(Dispatchers.IO) { deleteLocalSourceFiles(paths) }
                            val continueImport = deleted && shouldResumeImport
                            if (continueImport) {
                                if (viewModel.lastScanUri.isNotBlank()) {
                                    val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(viewModel.lastScanUri))
                                    if (rootDoc != null) {
                                        withContext(Dispatchers.IO) { pruneEmptyDocumentFolders(rootDoc) }
                                    }
                                }
                            } else if (viewModel.outputDirectory.isNotBlank()) {
                                refreshLibrary()
                                performScan()
                            }
                            decision?.complete(continueImport)
                            if (!deleted && shouldResumeImport) {
                                importFolderPaused = true
                                appendLog("Duplicate source delete failed; import stopped.")
                            }
                            duplicateLibraryPaths.removeAll { it in paths.toSet() }
                            setProgress("Duplicate file delete complete")
                            pendingDuplicateDeleteDecision = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    appendLog("Ignored duplicate file prompt for $pendingDuplicateDeleteContext")
                    pendingDuplicateDeleteDecision?.complete(false)
                    if (pendingDuplicateDeleteContext.contains("bulk", ignoreCase = true)) {
                        importFolderPaused = true
                    }
                    pendingDuplicateDeleteDecision = null
                    if (pendingDuplicateDeleteContext.contains("library scan", ignoreCase = true)) {
                        duplicateLibraryPaths.clear()
                    }
                    showDuplicateDeletePrompt = false
                }) {
                    Text("Ignore")
                }
            }
        )
    }

    if (showFaultyDeletePrompt) {
        AlertDialog(
            onDismissRequest = { showFaultyDeletePrompt = false },
            title = { Text("Faulty Files Found") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${pendingFaultyDeletePaths.size} faulty file(s) found during $pendingFaultyDeleteContext.")
                    Text("Delete them now, or ignore and review them in the scanner list.")
                    pendingFaultyDeletePaths.take(8).forEach { path ->
                        Text(
                            displayNameForRef(path),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (pendingFaultyDeletePaths.size > 8) {
                        Text("Showing first 8.", style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val paths = pendingFaultyDeletePaths
                        val decision = pendingFaultyDeleteDecision
                        val shouldResumeBulk = pendingFaultyDeleteContext.contains("import", ignoreCase = true)
                        showFaultyDeletePrompt = false
                        scope.launch {
                            startLogPolling()
                            setProgress("Deleting ${paths.size} faulty file(s)")
                            val deleted = withContext(Dispatchers.IO) { deleteLocalSourceFiles(paths) }
                            faultyFiles.removeAll { it.path in paths.toSet() }
                            if (shouldResumeBulk && deleted && viewModel.lastScanUri.isNotBlank()) {
                                val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(viewModel.lastScanUri))
                                if (rootDoc != null) {
                                    withContext(Dispatchers.IO) { pruneEmptyDocumentFolders(rootDoc) }
                                }
                            }
                            decision?.complete(deleted && shouldResumeBulk)
                            setProgress("Faulty file delete complete")
                            pendingFaultyDeleteDecision = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    appendLog("Ignored faulty file prompt for $pendingFaultyDeleteContext")
                    pendingFaultyDeleteDecision?.complete(false)
                    pendingFaultyDeleteDecision = null
                    showFaultyDeletePrompt = false
                }) {
                    Text("Ignore")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TabRow(selectedTabIndex = selectedTabIndex) {
            Tab(selected = selectedTabIndex == 0, enabled = !isRunning, onClick = { selectedTabIndex = 0 }) {
                Text("Merge", modifier = Modifier.padding(3.dp), style = MaterialTheme.typography.labelSmall)
            }
            Tab(selected = selectedTabIndex == 1, enabled = !isRunning, onClick = { selectedTabIndex = 1 }) {
                Text("Info", modifier = Modifier.padding(3.dp), style = MaterialTheme.typography.labelSmall)
            }
            Tab(selected = selectedTabIndex == 2, enabled = !isRunning, onClick = { selectedTabIndex = 2 }) {
                Text("Scan", modifier = Modifier.padding(3.dp), style = MaterialTheme.typography.labelSmall)
            }
            Tab(selected = selectedTabIndex == 3, enabled = !isRunning, onClick = { selectedTabIndex = 3 }) {
                Text("Zip", modifier = Modifier.padding(3.dp), style = MaterialTheme.typography.labelSmall)
            }
            Tab(selected = selectedTabIndex == 4, enabled = !isRunning, onClick = { selectedTabIndex = 4 }) {
                Text("Library", modifier = Modifier.padding(3.dp), style = MaterialTheme.typography.labelSmall)
            }
            Tab(selected = selectedTabIndex == 5, enabled = !isRunning, onClick = { selectedTabIndex = 5 }) {
                Text("Settings", modifier = Modifier.padding(3.dp), style = MaterialTheme.typography.labelSmall)
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (selectedTabIndex) {
                0 -> {
                    // Merge Tab
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(if (mergeInputCount <= 1) "Package Input" else "Merge Inputs")
                        Text("Import Folder: ${viewModel.lastScanPath}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        OutlinedTextField(
                            value = mergeInputs,
                            onValueChange = { mergeInputs = it },
                            label = { Text("Input URIs (one per line)") },
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { pickMergeInputs.launch(arrayOf("*/*")) }, enabled = !mergeRunning) {
                                Text("Pick package files")
                            }
                            Button(
                                onClick = { loadImportFolderIntoMergeInputs() },
                                enabled = !mergeRunning && viewModel.lastScanUri.isNotBlank(),
                            ) {
                                Text("Load Import Folder")
                            }
                        }
                        Button(
                            onClick = {
                                requireKeysOrError {
                                    progressStatus = "Starting bulk import"
                                    output = "Preparing import folder scan..."
                                    scope.launch {
                                        mergeRunning = true
                                        importFolderPaused = false
                                        startLogPolling()
                                        progressStatus = "Importing from folder..."
                                        runCatching {
                                            importFolderScanAndBulkImport()
                                        }.onFailure {
                                            progressStatus = "Failed"
                                            output = "ERROR: ${it.message}"
                                        }
                                        if (progressStatus != "Failed" && !importFolderPaused) {
                                            progressStatus = "Completed"
                                        }
                                        if (importFolderPaused) {
                                            progressStatus = "Paused"
                                        }
                                        importFolderPaused = false
                                        mergeRunning = false
                                    }
                                }
                            },
                            enabled = !mergeRunning && viewModel.lastScanUri.isNotBlank() && viewModel.outputDirectory.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Scan + Bulk Import Folder")
                        }
                        OutlinedTextField(
                            value = mergeOutputName,
                            onValueChange = { mergeOutputName = it },
                            label = { Text("Output file name") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Output: ${viewModel.outputDirectory}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { mergeType = "nsp" }, enabled = !mergeRunning) { Text("NSP") }
                            Button(onClick = { mergeType = "xci" }, enabled = !mergeRunning) { Text("XCI") }
                            Text("Type: ${mergeType.uppercase()}")
                        }
                        Button(
                            onClick = {
                                requireKeysOrError {
                                    scope.launch {
                                        mergeRunning = true
                                        startLogPolling()
                                        runCatching {
                                            val inputs = mergeInputs.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                                            val selectedFaulty = faultyFiles.map { it.path }.toSet().let { bad -> inputs.filter { it in bad } }
                                            if (selectedFaulty.isNotEmpty()) {
                                                promptFaultyDelete(selectedFaulty, "merge")
                                                error("Merge stopped because ${selectedFaulty.size} faulty file(s) are selected.")
                                            }
                                            val name = mergeOutputName.trim().ifEmpty { "merged_output" }
                                            val ext = extForMergeType(mergeType)
                                            val fileName = if (inputs.size == 1) {
                                                filenameForSingleImport(inputs.first(), name)
                                            } else if (name.lowercase().endsWith(".$ext")) {
                                                name
                                            } else {
                                                "$name.$ext"
                                            }
                                            val outputPath = uniqueOutputPath(fileName)
                                            output = runMergeToPath(inputs, outputPath)
                                            refreshLibrary()
                                        }.onFailure {
                                            progressStatus = "Failed"
                                            output = "ERROR: ${it.message}"
                                            if (isOutputPermissionError(it)) showPermissionWizard = true
                                        }
                                        if (progressStatus != "Failed") {
                                            progressStatus = "Completed"
                                        }
                                        mergeRunning = false
                                    }
                                }
                            },
                            enabled = !mergeRunning && mergeInputs.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (mergeInputCount <= 1) "Import/Repack to Output Library" else "Run Merge to Output Library")
                        }
                    }
                }
                1 -> {
                    // Info Tab
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("File Info / Content Summary")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = singleFileInput,
                                onValueChange = { singleFileInput = it },
                                label = { Text("Input URI or Local Path") },
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = { pickSingleInput.launch(arrayOf("*/*")) }, enabled = !infoRunning) {
                                Text("Pick")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    requireKeysOrError {
                                        scope.launch {
                                            infoRunning = true
                                            progressStatus = "Analyzing..."
                                            runCatching {
                                                val localPath = importUriToCache(Uri.parse(singleFileInput), "info_input")
                                                val result = withContext(Dispatchers.IO) { NscbBridge.fileList(localPath, viewModel.keysPath) }
                                                deleteIfAppCachePath(localPath)
                                                output = result
                                            }.onFailure {
                                                output = "ERROR: ${it.message}"
                                            }
                                            infoRunning = false
                                        }
                                    }
                                },
                                enabled = !infoRunning && singleFileInput.isNotBlank(),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Show Info")
                            }
                            Button(
                                onClick = {
                                    requireKeysOrError {
                                        scope.launch {
                                            infoRunning = true
                                            progressStatus = "Analyzing..."
                                            runCatching {
                                                val localPath = importUriToCache(Uri.parse(singleFileInput), "content_input")
                                                val result = withContext(Dispatchers.IO) { NscbBridge.contentList(localPath, viewModel.keysPath) }
                                                deleteIfAppCachePath(localPath)
                                                output = result
                                            }.onFailure {
                                                output = "ERROR: ${it.message}"
                                            }
                                            infoRunning = false
                                        }
                                    }
                                },
                                enabled = !infoRunning && singleFileInput.isNotBlank(),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Show Content")
                            }
                        }
                    }
                }
                2 -> {
                    // Scanner Tab
                    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Game Library: ${viewModel.outputDirectory}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Button(
                            onClick = {
                                requireKeysOrError {
                                    scope.launch {
                                        scanRunning = true
                                        startLogPolling()
                                        verifyAndRenameLibrary()
                                        progressStatus = "Idle"
                                        scanRunning = false
                                    }
                                }
                            },
                            enabled = !scanRunning && viewModel.outputDirectory.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Verify + Rename Game Library")
                        }
                        Button(
                            onClick = {
                                requireKeysOrError {
                                    scope.launch {
                                        mergeRunning = true
                                        cancelBulkAfterCurrent = false
                                        startLogPolling()
                                        progressStatus = "Bulk merging..."
                                        runCatching {
                                            ensureFileOperationStorage()
                                            withContext(Dispatchers.IO) { clearMergeTempCache() }
                                            if (!usesSafOutputFolder()) {
                                                File(viewModel.outputDirectory).mkdirs()
                                            }
                                            if (faultyFiles.isNotEmpty()) {
                                                promptFaultyDelete(faultyFiles.map { it.path }, "bulk merge")
                                            }
                                            val groupsToMerge = scanResults
                                                .filter { bestMergeFilesForGroup(it).size > 1 }
                                                .filter { group ->
                                                    val badPaths = faultyFiles.map { it.path }.toSet()
                                                    bestMergeFilesForGroup(group).none { it.path in badPaths }
                                                }
                                            if (groupsToMerge.isEmpty()) {
                                                error("No mergeable groups found with the current filters.")
                                            }
                                            var mergedCount = 0
                                            val failedGroups = mutableListOf<String>()
                                            val logLines = mutableListOf("Bulk merge started: ${groupsToMerge.size} group(s)")
                                            appendLog("Bulk merge started: ${groupsToMerge.size} group(s)")
                                            for ((index, group) in groupsToMerge.withIndex()) {
                                                val bestFiles = bestMergeFilesForGroup(group)
                                                val paths = bestFiles.map { it.path }
                                                val localPaths = bestFiles.map { ensureNativeReadable(it.path, "bulk_merge_input") }
                                                val pathsJoined = localPaths.joinToString("\n")
                                                try {
                                                    requireReadableFiles(localPaths, "Merge input")
                                                    progressStatus = "Merging ${index + 1}/${groupsToMerge.size}: ${group.titleName}"
                                                    appendLog("Merging ${index + 1}/${groupsToMerge.size}: ${group.titleName} (${paths.size} file(s))")
                                                    logLines.add("Merging ${index + 1}/${groupsToMerge.size}: ${group.titleName} (${paths.size} file(s))")
                                                    output = logLines.joinToString("\n")
                                                    val suggested = withContext(Dispatchers.IO) {
                                                        NscbBridge.getSuggestedFileName(pathsJoined, viewModel.keysPath, titleDbCacheDir, mergeType)
                                                    }
                                                    val outputPath = uniqueOutputPath(suggested)
                                                    val tempOutputPath = prepareTempOutput(outputPath)
                                                    appendLog("Output: $outputPath")
                                                    logLines.add("Output: $outputPath")
                                                    output = logLines.joinToString("\n")
                                                    val result = withContext(Dispatchers.IO) {
                                                        NscbBridge.merge(pathsJoined, tempOutputPath, viewModel.keysPath, mergeType)
                                                    }
                                                    if (!result.startsWith("OK")) {
                                                        File(tempOutputPath).delete()
                                                        val inputList = paths.joinToString("\n")
                                                        failedGroups.add("${group.titleName}: $result\n$inputList")
                                                        appendLog("FAILED: ${group.titleName}: $result")
                                                        logLines.add("FAILED: ${group.titleName}: $result")
                                                        logLines.add(inputList)
                                                        output = logLines.joinToString("\n")
                                                        if (cancelBulkAfterCurrent) {
                                                            logLines.add("Stopped after current group.")
                                                            break
                                                        }
                                                        continue
                                                    }
                                                    var completedSize = 0L
                                                    var outputCheckFailed = false
                                                    try {
                                                        completedSize = requireCompletedOutput(tempOutputPath, localPaths)
                                                        finalizeTempOutput(tempOutputPath, outputPath)
                                                    } catch (err: Throwable) {
                                                        File(tempOutputPath).delete()
                                                        val inputList = paths.joinToString("\n")
                                                        failedGroups.add("${group.titleName}: ${err.message}\n$inputList")
                                                        appendLog("FAILED: ${group.titleName}: ${err.message}")
                                                        logLines.add("FAILED: ${group.titleName}: ${err.message}")
                                                        logLines.add(inputList)
                                                        output = logLines.joinToString("\n")
                                                        outputCheckFailed = true
                                                    }
                                                    if (outputCheckFailed) {
                                                        if (cancelBulkAfterCurrent) {
                                                            logLines.add("Stopped after current group.")
                                                            break
                                                        }
                                                        continue
                                                    }
                                                    if (viewModel.deleteSourcesAfterMerge) {
                                                        deleteLocalSourceFiles(paths)
                                                        appendLog("Deleted ${paths.distinct().size} source file(s).")
                                                        logLines.add("Deleted ${paths.distinct().size} source file(s).")
                                                    }
                                                    mergedCount += 1
                                                    appendLog("OK: ${group.titleName} (${formatSize(completedSize)})")
                                                    logLines.add("OK: ${group.titleName} (${formatSize(completedSize)})")
                                                    output = logLines.joinToString("\n")
                                                    refreshLibrary()
                                                    if (cancelBulkAfterCurrent) {
                                                        logLines.add("Stopped after current group.")
                                                        break
                                                    }
                                                } finally {
                                                    localPaths.forEach { p -> deleteIfAppCachePath(p) }
                                                }
                                            }
                                            progressStatus = "Completed"
                                            val summary = if (failedGroups.isEmpty()) {
                                                "Bulk merged $mergedCount group(s) into ${viewModel.outputDirectory}"
                                            } else {
                                                "Bulk merged $mergedCount group(s), skipped ${failedGroups.size} failed group(s).\n" +
                                                    failedGroups.take(5).joinToString("\n")
                                            }
                                            output = (logLines + summary).joinToString("\n")
                                            performScan()
                                        }.onFailure {
                                            progressStatus = "Failed"
                                            output = "ERROR: ${it.message}"
                                        }
                                        cancelBulkAfterCurrent = false
                                        mergeRunning = false
                                    }
                                }
                            },
                            enabled = !mergeRunning && scanResults.any { bestMergeFilesForGroup(it).size > 1 },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Bulk Merge Library")
                        }
                        if (mergeRunning && progressStatus.startsWith("Merging")) {
                            Button(
                                onClick = {
                                    cancelBulkAfterCurrent = true
                                    output = output + "\nCancel requested. Bulk merge will stop after the current group."
                                },
                                enabled = !cancelBulkAfterCurrent,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text(if (cancelBulkAfterCurrent) "Cancel Pending" else "Cancel After Current")
                            }
                        }
                        if (duplicateLibraryPaths.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Duplicate copies found: ${duplicateLibraryPaths.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    duplicateLibraryPaths.take(20).forEach { path ->
                                        Text(
                                            displayNameForRef(path),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    if (duplicateLibraryPaths.size > 20) {
                                        Text("Showing first 20 duplicate files.", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    scanRunning = true
                                                    progressStatus = "Deleting duplicate copies..."
                                                    val paths = duplicateLibraryPaths.toList()
                                                    deleteLocalSourceFiles(paths)
                                                    duplicateLibraryPaths.clear()
                                                    performScan()
                                                    scanRunning = false
                                                }
                                            },
                                            enabled = !scanRunning,
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                        ) {
                                            Text("Delete Duplicate Copies")
                                        }
                                        Button(
                                            onClick = { duplicateLibraryPaths.clear() },
                                            enabled = !scanRunning,
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                                        ) {
                                            Text("Ignore")
                                        }
                                    }
                                }
                            }
                        }

                        if (faultyFiles.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Faulty files found: ${faultyFiles.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    faultyFiles.take(20).forEach { file ->
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(file.filename, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                                            Text(file.reason, style = MaterialTheme.typography.labelSmall)
                                            Text(file.path, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onErrorContainer)
                                        }
                                    }
                                    if (faultyFiles.size > 20) {
                                        Text("Showing first 20 faulty files.", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    scanRunning = true
                                                    progressStatus = "Deleting faulty files..."
                                                    val paths = faultyFiles.map { it.path }
                                                    deleteLocalSourceFiles(paths)
                                                    faultyFiles.clear()
                                                    performScan()
                                                    scanRunning = false
                                                }
                                            },
                                            enabled = !scanRunning,
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                        ) {
                                            Text("Delete Faulty Files")
                                        }
                                        Button(
                                            onClick = { faultyFiles.clear() },
                                            enabled = !scanRunning,
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                                        ) {
                                            Text("Ignore")
                                        }
                                    }
                                }
                            }
                        }

                        if (scanResults.isNotEmpty()) {
                            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(scanResults) { group ->
                                    ScanGroupCard(group, 
                                        onMergeRequest = {
                                            val bestFiles = bestMergeFilesForGroup(group)
                                            
                                            val pathsJoined = bestFiles.joinToString("\n") { it.path }
                                            mergeInputs = pathsJoined
                                            scope.launch {
                                                mergeRunning = true
                                                progressStatus = "Generating name..."
                                                runCatching {
                                                    val suggested = withContext(Dispatchers.IO) {
                                                        NscbBridge.getSuggestedFileName(pathsJoined, viewModel.keysPath, titleDbCacheDir, mergeType)
                                                    }
                                                    mergeOutputName = suggested
                                                    mergeOutputUri = null
                                                    selectedTabIndex = 0
                                                    output = "Group ready. Use the Merge tab to write to the output library."
                                                }.onFailure {
                                                    output = "ERROR: ${it.message}"
                                                    selectedTabIndex = 0
                                                }
                                                mergeRunning = false
                                            }
                                        },
                                        onMergeNowRequest = {
                                            requireKeysOrError {
                                                scope.launch {
                                                    mergeRunning = true
                                                    startLogPolling()
                                                    progressStatus = "Merging ${group.titleName}"
                                                    runCatching {
                                                        val bestFiles = bestMergeFilesForGroup(group)
                                                        val selectedFaulty = faultyFiles.map { it.path }.toSet().let { bad ->
                                                            bestFiles.map { it.path }.filter { it in bad }
                                                        }
                                                        if (selectedFaulty.isNotEmpty()) {
                                                            promptFaultyDelete(selectedFaulty, "merge")
                                                            error("Merge stopped because ${selectedFaulty.size} faulty file(s) are selected.")
                                                        }
                                                        val mergeMessage = mergeScanGroupToOutputFolder(group)
                                                        refreshLibrary()
                                                        performScan()
                                                        output = mergeMessage
                                                    }.onFailure {
                                                        progressStatus = "Failed"
                                                        output = "ERROR: ${it.message}"
                                                        if (isOutputPermissionError(it)) showPermissionWizard = true
                                                    }
                                                    if (progressStatus != "Failed") {
                                                        progressStatus = "Completed"
                                                    }
                                                    mergeRunning = false
                                                }
                                            }
                                        },
                                        onDeleteRequest = { path ->
                                            scope.launch {
                                                scanRunning = true
                                                progressStatus = "Deleting..."
                                                NscbBridge.deleteFile(path)
                                                performScan()
                                                scanRunning = false
                                            }
                                        }
                                    )
                                }
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No results. Configure path in Settings and Scan.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                3 -> {
                    // Compress / Decompress Tab
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Compress / Decompress")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { archiveMode = "compress"; archiveOutputUri = null }, enabled = !archiveRunning) {
                                Text("Compress")
                            }
                            Button(onClick = { archiveMode = "decompress"; archiveOutputUri = null }, enabled = !archiveRunning) {
                                Text("Decompress")
                            }
                            Text("Mode: ${archiveMode.replaceFirstChar { it.uppercase() }}")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = archiveInput,
                                onValueChange = { archiveInput = it },
                                label = { Text("Input URI or Local Path") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            Button(onClick = { pickArchiveInput.launch(arrayOf("*/*")) }, enabled = !archiveRunning) {
                                Text("Pick")
                            }
                        }
                        OutlinedTextField(
                            value = archiveOutputName,
                            onValueChange = {
                                archiveOutputName = it
                                archiveOutputUri = null
                            },
                            label = { Text("Output file name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        if (archiveMode == "compress") {
                            OutlinedTextField(
                                value = archiveLevel,
                                onValueChange = { archiveLevel = it.filter { ch -> ch.isDigit() }.take(2) },
                                label = { Text("Compression level 1-22") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = {
                                    val ext = archiveOutputExt(archiveInput, archiveMode)
                                    pickArchiveOutput.launch(outputNameWithExt(archiveOutputName, ext))
                                },
                                enabled = !archiveRunning && archiveInput.isNotBlank()
                            ) {
                                Text("Pick output destination")
                            }
                            if (archiveOutputUri != null) {
                                Text("Ready", color = Color(0xFF2E7D32), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Button(
                            onClick = {
                                val runAction: () -> Unit = {
                                    if (archiveOutputUri == null) {
                                        val ext = archiveOutputExt(archiveInput, archiveMode)
                                        pickArchiveOutput.launch(outputNameWithExt(archiveOutputName, ext))
                                        output = "Please select where to save the output file..."
                                    } else {
                                        scope.launch {
                                        archiveRunning = true
                                        startLogPolling()
                                        var localInput: String? = null
                                        var temporaryArchiveOutput: String? = null
                                        runCatching {
                                            configureNativeTempRoot()
                                            val inputUri = Uri.parse(archiveInput)
                                            progressStatus = "Importing input..."
                                            localInput = importUriToCache(inputUri, "archive_input")
                                            val destinationUri = requireNotNull(archiveOutputUri)
                                            val directOutputPath = writableFilePathForUri(destinationUri)
                                            val archiveOutputPath = directOutputPath ?: File(operationTempRoot(), outputNameWithExt(archiveOutputName, archiveOutputExt(archiveInput, archiveMode))).absolutePath
                                            if (directOutputPath == null) {
                                                temporaryArchiveOutput = archiveOutputPath
                                            }
                                            progressStatus = if (archiveMode == "compress") "Compressing..." else "Decompressing..."
                                            val result = withContext(Dispatchers.IO) {
                                                if (archiveMode == "compress") {
                                                    val level = archiveLevel.toIntOrNull()?.coerceIn(1, 22) ?: 3
                                                    NscbBridge.compress(requireNotNull(localInput), archiveOutputPath, viewModel.keysPath, level)
                                                } else {
                                                    NscbBridge.decompress(requireNotNull(localInput), archiveOutputPath)
                                                }
                                            }
                                            if (result.startsWith("OK")) {
                                                if (directOutputPath == null) {
                                                    progressStatus = "Writing to destination..."
                                                    copyLocalFileToUri(archiveOutputPath, destinationUri)
                                                    File(archiveOutputPath).delete()
                                                    temporaryArchiveOutput = null
                                                }
                                                progressStatus = "Completed"
                                                output = "$result\nSaved successfully to chosen destination."
                                            } else {
                                                progressStatus = "Failed"
                                                output = result
                                            }
                                        }.onFailure {
                                            progressStatus = "Failed"
                                            output = "ERROR: ${it.message}"
                                        }
                                        localInput?.let { deleteIfAppCachePath(it) }
                                        temporaryArchiveOutput?.let { File(it).delete() }
                                        archiveRunning = false
                                        }
                                    }
                                }
                                if (archiveMode == "compress") {
                                    requireKeysOrError(runAction)
                                } else {
                                    runAction()
                                }
                            },
                            enabled = !archiveRunning && archiveInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (archiveMode == "compress") "Run Compress" else "Run Decompress")
                        }
                    }
                }
                4 -> {
                    // Library Tab
                    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Library", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        }
                        Text(viewModel.outputDirectory, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        if (libraryFiles.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No output files found.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(libraryFiles) { file ->
                                    LibraryFileCard(
                                        file = file,
                                        formatSize = ::formatSize,
                                        formatModified = ::formatModified,
                                        onDeleteRequest = { path ->
                                            scope.launch {
                                                libraryRunning = true
                                                progressStatus = "Deleting..."
                                                deleteLibraryFile(path)
                                                progressStatus = "Idle"
                                                libraryRunning = false
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                5 -> {
                    // Settings Tab
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("App Configuration", style = MaterialTheme.typography.titleMedium)
                        
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Keys (prod.keys)", style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = viewModel.keysPath,
                                    onValueChange = { viewModel.updateKeysPath(it) },
                                    label = { Text("Local Path") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                Button(onClick = { pickKeys.launch(arrayOf("*/*")) }, enabled = !settingsRunning) {
                                    Text("Pick")
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Import Folder", style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = viewModel.lastScanPath,
                                    onValueChange = { viewModel.updateLastScanPath(it) },
                                    label = { Text("Directory Path") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                Button(onClick = { pickScanFolder.launch(null) }, enabled = !scanRunning) {
                                    Text("Pick")
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Game Library Folder", style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = viewModel.outputDirectory,
                                    onValueChange = { viewModel.updateOutputDirectory(it) },
                                    label = { Text("Directory Path") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                Button(onClick = { pickOutputFolder.launch(null) }, enabled = !libraryRunning) {
                                    Text("Pick")
                                }
                            }
                            Button(
                                onClick = {
                                    viewModel.updateOutputDirectory(context.getExternalFilesDir(null)?.absolutePath
                                        ?: context.filesDir.absolutePath)
                                },
                                enabled = !libraryRunning,
                            ) {
                                Text("Reset to App Dir")
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Library Merge", style = MaterialTheme.typography.labelMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = viewModel.deleteSourcesAfterMerge,
                                    onCheckedChange = { viewModel.updateDeleteSourcesAfterMerge(it) },
                                    enabled = !mergeRunning
                                )
                                Text("Delete source files after successful merge")
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = viewModel.ignoreXciInLibraryMerge,
                                    onCheckedChange = { viewModel.updateIgnoreXciInLibraryMerge(it) },
                                    enabled = !mergeRunning
                                )
                                Text("Ignore XCI/XCZ files")
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = viewModel.analyzePackageBeforeImport,
                                    onCheckedChange = { viewModel.updateAnalyzePackageBeforeImport(it) },
                                    enabled = !mergeRunning && !scanRunning && !libraryRunning
                                )
                                Text("Analyze package before import")
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("TitlesDB", style = MaterialTheme.typography.labelMedium)
                            Button(
                                onClick = {
                                    scope.launch {
                                        settingsRunning = true
                                        startLogPolling()
                                        progressStatus = "Updating TitlesDB..."
                                        val result = withContext(Dispatchers.IO) { NscbBridge.refreshTitleDb(titleDbCacheDir) }
                                        output = result
                                        progressStatus = "Idle"
                                        settingsRunning = false
                                    }
                                },
                                enabled = !settingsRunning
                            ) {
                                Text("Refresh TitlesDB")
                            }
                        }
                        
                        Text(
                            "Folder access uses SAF pickers. Keep the app on folders you selected in the picker.",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        // Collapsible Log Output
        var showOutput by remember { mutableStateOf(false) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Log Output", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Open Console", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { showConsoleModal = true })
                Text(if (showOutput) "Hide Inline" else "Show Inline", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { showOutput = !showOutput })
            }
        }
        if (showOutput) {
            Text(
                text = output,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .verticalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                    .padding(8.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(progressStatus, style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    mergeInputs = ""
                    mergeOutputName = "merged_output"
                    mergeOutputUri = null
                    singleFileInput = ""
                    scanResults.clear()
                    progressStatus = "Idle"
                    output = "Cleared"
                },
                enabled = !isRunning,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            ) {
                Text("Clear")
            }
        }
    }
}

@Composable
fun RemoteArtwork(url: String?, fallbackText: String) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(url) {
        bitmap = null
        if (!url.isNullOrBlank()) {
            bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    URL(url).openStream().use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                }.getOrNull()
            }
        }
    }

    Box(
        modifier = Modifier
            .size(56.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap
        if (image != null) {
            Image(bitmap = image.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Text(fallbackText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun LibraryFileCard(
    file: LibraryFile,
    formatSize: (Long) -> String,
    formatModified: (Long) -> String,
    onDeleteRequest: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RemoteArtwork(file.imageUrl, file.extension)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(file.filename, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text("${formatSize(file.size)} • ${formatModified(file.modified)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(file.titleSummary, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 3)
                if (file.versionSummary.isNotBlank()) {
                    val statusColor = when (file.versionStatus) {
                        "outdated" -> MaterialTheme.colorScheme.error
                        "current" -> Color(0xFF2E7D32)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(file.versionSummary, style = MaterialTheme.typography.labelSmall, color = statusColor, maxLines = 3)
                }
                if (file.imageUrl == null) {
                    Text("Artwork: no TitlesDB match", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = { onDeleteRequest(file.path) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun ScanGroupCard(
    group: ScanGroup,
    onMergeRequest: () -> Unit,
    onMergeNowRequest: () -> Unit,
    onDeleteRequest: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(group.titleName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (group.latestVersionDb > 0) {
                    val currentMax = group.items.maxOfOrNull { it.version } ?: 0
                    val isOutdated = group.latestVersionDb > currentMax
                    Text(
                        "DB: v${group.latestVersionDb / 65536}", 
                        style = MaterialTheme.typography.labelSmall, 
                        color = if (isOutdated) Color.Red else Color(0xFF2E7D32),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text("Base ID: ${group.baseId}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Group by full Title ID. Multiple entries here are likely duplicates or different versions.
                val groupedById = group.items.groupBy { it.titleId }
                
                groupedById.forEach { (tid, items) ->
                    // Sort items by version descending
                    val sortedItems = items.sortedByDescending { it.version }
                    val latest = sortedItems.first()
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            val typeLabel = when {
                                tid.endsWith("000") -> "Base"
                                tid.endsWith("800") -> "Update"
                                else -> "DLC"
                            }
                            Text("$typeLabel: v${latest.version / 65536}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            Text(tid.takeLast(4), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                        
                        sortedItems.forEachIndexed { index, item ->
                            val isOlderVersion = index > 0
                            val isExactDupe = sortedItems.any { it !== item && it.version == item.version }
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isExactDupe || isOlderVersion) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                                        else Color.Transparent,
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(start = 8.dp, top = 2.dp, bottom = 2.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.filename,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isExactDupe || isOlderVersion) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1
                                    )
                                    if (isOlderVersion) {
                                        Text("OLDER VERSION", style = MaterialTheme.typography.labelSmall, color = Color.Red, fontWeight = FontWeight.Bold)
                                    } else if (isExactDupe) {
                                        Text("DUPLICATE", style = MaterialTheme.typography.labelSmall, color = Color.Red, fontWeight = FontWeight.Bold)
                                    }
                                }
                                IconButton(onClick = { onDeleteRequest(item.path) }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
            
            if (group.items.isNotEmpty()) {
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onMergeRequest,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("Prepare")
                    }
                    Button(
                        onClick = onMergeNowRequest,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Text(if (group.items.size == 1) "Import/Repack" else "Merge Now")
                    }
                }
            }
        }
    }
}
