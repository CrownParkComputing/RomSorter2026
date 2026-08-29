package com.simplikfiwed.librarymanager

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
            NscbAppTheme {
                val viewModel: NscbViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T {
                            @Suppress("UNCHECKED_CAST")
                            return NscbViewModel(applicationContext) as T
                        }
                    }
                )
                var showSplash by remember { mutableStateOf(true) }
                if (showSplash) {
                    SplashScreen(onDismiss = { showSplash = false })
                } else {
                    AndroidNscbScreen(viewModel)
                }
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
    val titleId: String? = null,
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
fun NscbAppTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = Color(0xFF8B5CF6),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF31215F),
        onPrimaryContainer = Color(0xFFE9DDFF),
        secondary = Color(0xFF2DD4BF),
        onSecondary = Color(0xFF042B27),
        secondaryContainer = Color(0xFF0E3A37),
        onSecondaryContainer = Color(0xFFBDF8F0),
        tertiary = Color(0xFFFFB74D),
        onTertiary = Color(0xFF2F1B00),
        tertiaryContainer = Color(0xFF5B3A00),
        onTertiaryContainer = Color(0xFFFFD9A6),
        background = Color(0xFF09101C),
        onBackground = Color(0xFFE6ECF8),
        surface = Color(0xFF111827),
        onSurface = Color(0xFFE6ECF8),
        surfaceVariant = Color(0xFF1A2438),
        onSurfaceVariant = Color(0xFFB8C4DD),
        outline = Color(0xFF5A6782),
        error = Color(0xFFFF6B81),
        onError = Color.White,
        errorContainer = Color(0xFF5A1122),
        onErrorContainer = Color(0xFFFFD9DF)
    )

    MaterialTheme(colorScheme = colorScheme, typography = MaterialTheme.typography, content = content)
}

@Composable
fun SplashScreen(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp))
                )
                Text(
                    text = "RomSorter2026",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Nintendo Switch ROM Manager",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Dark, compact operator layout. Tabs stay focused on setup while the console modal handles the long-running detail.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Workspace")
                }
            }
        }
    }
}

@Composable
fun ScreenIntroCard(title: String, subtitle: String, accent: Color = MaterialTheme.colorScheme.primary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(accent, RoundedCornerShape(999.dp))
            )
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    description: String,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(accent, RoundedCornerShape(999.dp))
            )
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
fun TwoColumnLayout(
    modifier: Modifier = Modifier,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (maxWidth >= 900.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), content = left)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), content = right)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = left)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = right)
            }
        }
    }
}

@Composable
fun FieldHint(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun LabeledValue(
    label: String,
    value: String,
    explanation: String,
    monospace: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (value.isBlank()) "Not set" else value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else valueColor,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
        )
        Text(explanation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun OptionCard(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

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
    var titleDbReady by remember { mutableStateOf(false) }
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
    val titleDbCacheDir = remember { File(context.filesDir, "titledb").absolutePath }

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

    fun librarySnapshotFile() = File(context.filesDir, "library_snapshot_cache.json")

    fun serializeLibraryTitleDetail(detail: LibraryTitleDetail): JSONObject {
        val obj = JSONObject()
        obj.put("titleId", detail.titleId)
        obj.put("titleName", detail.titleName)
        obj.put("localVersion", detail.localVersion)
        obj.put("latestVersion", detail.latestVersion ?: JSONObject.NULL)
        obj.put("releaseDate", detail.releaseDate ?: JSONObject.NULL)
        obj.put("publisher", detail.publisher ?: JSONObject.NULL)
        obj.put("description", detail.description ?: JSONObject.NULL)
        obj.put("imageUrl", detail.imageUrl ?: JSONObject.NULL)
        obj.put("status", detail.status)
        val languages = JSONArray()
        detail.languages.forEach { languages.put(it) }
        obj.put("languages", languages)
        val screenshots = JSONArray()
        detail.screenshotUrls.forEach { screenshots.put(it) }
        obj.put("screenshotUrls", screenshots)
        return obj
    }

    fun deserializeStringList(array: JSONArray?): List<String> {
        val out = mutableListOf<String>()
        if (array != null) {
            for (i in 0 until array.length()) out.add(array.optString(i))
        }
        return out
    }

    fun normalizeLibraryTitleId(titleId: String?): String? {
        val normalized = titleId?.trim()?.uppercase()?.takeIf { it.length == 16 } ?: return null
        if (normalized.endsWith("000")) return normalized
        if (normalized.endsWith("800")) {
            return normalized.dropLast(3) + "000"
        }
        val chars = normalized.toCharArray()
        val nibble = chars[12].digitToIntOrNull(16) ?: return normalized
        chars[12] = ((nibble - 1).coerceAtLeast(0)).toString(16).uppercase()[0]
        chars[13] = '0'
        chars[14] = '0'
        chars[15] = '0'
        return String(chars)
    }

    fun deserializeLibraryTitleDetail(obj: JSONObject): LibraryTitleDetail {
        return LibraryTitleDetail(
            titleId = obj.optString("titleId"),
            titleName = obj.optString("titleName", "Unknown"),
            localVersion = obj.optLong("localVersion"),
            latestVersion = if (obj.isNull("latestVersion")) null else obj.optLong("latestVersion"),
            releaseDate = if (obj.isNull("releaseDate")) null else obj.optString("releaseDate"),
            publisher = if (obj.isNull("publisher")) null else obj.optString("publisher"),
            languages = deserializeStringList(obj.optJSONArray("languages")),
            description = if (obj.isNull("description")) null else obj.optString("description"),
            imageUrl = if (obj.isNull("imageUrl")) null else obj.optString("imageUrl"),
            screenshotUrls = deserializeStringList(obj.optJSONArray("screenshotUrls")),
            status = obj.optString("status", "unknown")
        )
    }

    fun serializeLibraryFile(file: LibraryFile): JSONObject {
        val obj = JSONObject()
        obj.put("path", file.path)
        obj.put("filename", file.filename)
        obj.put("size", file.size)
        obj.put("modified", file.modified)
        obj.put("extension", file.extension)
        obj.put("titleId", file.titleId ?: JSONObject.NULL)
        obj.put("titleSummary", file.titleSummary)
        obj.put("versionSummary", file.versionSummary)
        obj.put("versionStatus", file.versionStatus)
        obj.put("imageUrl", file.imageUrl ?: JSONObject.NULL)
        val details = JSONArray()
        file.details.forEach { details.put(serializeLibraryTitleDetail(it)) }
        obj.put("details", details)
        return obj
    }

    fun deserializeLibraryFile(obj: JSONObject): LibraryFile {
        val details = mutableListOf<LibraryTitleDetail>()
        val arr = obj.optJSONArray("details")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                details.add(deserializeLibraryTitleDetail(arr.getJSONObject(i)))
            }
        }
        return LibraryFile(
            path = obj.optString("path"),
            filename = obj.optString("filename"),
            size = obj.optLong("size"),
            modified = obj.optLong("modified"),
            extension = obj.optString(
                "extension",
                obj.optString("filename").substringBefore('?').substringAfterLast('/', obj.optString("filename")).substringAfterLast('.', "").uppercase()
            ),
            titleId = normalizeLibraryTitleId(if (obj.isNull("titleId")) null else obj.optString("titleId")),
            titleSummary = obj.optString("titleSummary", "Not checked"),
            versionSummary = obj.optString("versionSummary"),
            versionStatus = obj.optString("versionStatus", "unknown"),
            imageUrl = if (obj.isNull("imageUrl")) null else obj.optString("imageUrl"),
            details = details
        )
    }

    fun currentLibrarySourceKey(): String {
        return if (viewModel.outputFolderUri.isNotBlank()) {
            "saf:${viewModel.outputFolderUri}"
        } else {
            "fs:${viewModel.outputDirectory}"
        }
    }

    fun loadLibrarySnapshot(): List<LibraryFile> {
        val file = librarySnapshotFile()
        if (!file.exists()) return emptyList()
        return try {
            val obj = JSONObject(file.readText())
            if (obj.optString("sourceKey") != currentLibrarySourceKey()) return emptyList()
            val arr = obj.optJSONArray("items") ?: return emptyList()
            val out = mutableListOf<LibraryFile>()
            for (i in 0 until arr.length()) out.add(deserializeLibraryFile(arr.getJSONObject(i)))
            out
        } catch (_: Exception) { emptyList() }
    }

    fun saveLibrarySnapshot(items: List<LibraryFile>) {
        try {
            val arr = JSONArray()
            items.forEach { arr.put(serializeLibraryFile(it)) }
            librarySnapshotFile().writeText(
                JSONObject()
                    .put("version", 1)
                    .put("sourceKey", currentLibrarySourceKey())
                    .put("savedAt", System.currentTimeMillis())
                    .put("items", arr)
                    .toString()
            )
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
        val titleRawJson = entry.optString("titleRawJson", entry.optString("rawJson", ""))
        val versionRawJson = entry.optString("versionRawJson", "")
        if (entry.optBoolean("versionHasError", false)) {
            return base.copy(
                titleSummary = entry.optString("titleSummary", base.titleSummary),
                versionSummary = entry.optString("versionSummary", base.versionSummary),
                versionStatus = "error"
            )
        }
        if (versionRawJson.isNotBlank()) {
            return parseLibraryStatusJson(versionRawJson, base)
        }
        if (entry.optBoolean("titleHasError", false) || entry.optBoolean("hasError", false)) {
            return base.copy(titleSummary = entry.optString("titleSummary", "Cache error"), versionStatus = base.versionStatus)
        }
        if (titleRawJson.isBlank()) return null
        return parseLibraryStatusJson(titleRawJson, base)
    }

    fun extractTitleIdFromFilename(fileName: String): String? {
        val re = Regex("[\\[-]([0-9A-Fa-f]{16})[\\]-]")
        val stem = File(fileName).nameWithoutExtension
        return normalizeLibraryTitleId(re.find(stem)?.groupValues?.get(1))
    }

    fun extractLocalVersionFromFilename(fileName: String): Long {
        val stem = File(fileName).nameWithoutExtension
        val bracket = Regex("\\[v(\\d+)\\]").find(stem)
        if (bracket != null) return bracket.groupValues[1].toLongOrNull() ?: 0
        val dash = Regex("--v(\\d+)-").find(stem)
        if (dash != null) return dash.groupValues[1].toLongOrNull() ?: 0
        return 0
    }

    fun lookupLibraryTitlesBatch(files: List<LibraryFile>): Map<String, LibraryTitleDetail> {
        val ids = files.mapNotNull { normalizeLibraryTitleId(it.titleId) ?: extractTitleIdFromFilename(it.filename) }.distinct().filter { it.length == 16 }
        if (ids.isEmpty()) return emptyMap()
        val raw = try {
            NscbBridge.titleDbLookupBatch(ids.joinToString("\n"), titleDbCacheDir)
        } catch (_: Exception) { "" }
        if (raw.isBlank() || raw.startsWith("ERROR")) {
            Log.i(NSCB_LOG_TAG, "TitlesDB batch lookup returned no data for ${ids.size} title id(s): ${raw.ifBlank { "blank response" }}")
            return emptyMap()
        }
        val out = mutableMapOf<String, LibraryTitleDetail>()
        try {
            val json = JSONObject(raw)
            val arr = json.getJSONArray("results")
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val tid = normalizeLibraryTitleId(item.getString("title_id")) ?: continue
                val name = item.optString("title_name", "Unknown")
                val latest = if (item.isNull("latest_version")) null else item.getLong("latest_version")
                val releaseDate = if (item.isNull("release_date")) null else item.getString("release_date")
                val publisher = if (item.isNull("publisher")) null else item.getString("publisher")
                val description = if (item.isNull("description")) null else item.getString("description")
                val imageUrl = if (item.isNull("image_url")) null else item.getString("image_url")
                val langs = mutableListOf<String>()
                if (!item.isNull("languages")) {
                    val la = item.getJSONArray("languages")
                    for (j in 0 until la.length()) langs.add(la.getString(j))
                }
                val screenshots = mutableListOf<String>()
                if (!item.isNull("screenshot_urls")) {
                    val sc = item.getJSONArray("screenshot_urls")
                    for (j in 0 until sc.length()) screenshots.add(sc.getString(j))
                }
                out[tid] = LibraryTitleDetail(
                    titleId = tid,
                    titleName = name,
                    localVersion = 0,
                    latestVersion = latest,
                    releaseDate = releaseDate,
                    publisher = publisher,
                    languages = langs,
                    description = description,
                    imageUrl = imageUrl,
                    screenshotUrls = screenshots,
                    status = if (latest == null) "unknown" else "current"
                )
            }
        } catch (_: Exception) { }
        return out
    }

    fun buildLibraryFileFromBatch(base: LibraryFile, fromFilenameTid: String?, dbMap: Map<String, LibraryTitleDetail>): LibraryFile {
        val tid = normalizeLibraryTitleId(base.titleId) ?: normalizeLibraryTitleId(fromFilenameTid) ?: return base
        val db = dbMap[tid] ?: return base
        val localVersion = extractLocalVersionFromFilename(base.filename)
        val status = when {
            db.latestVersion != null && db.latestVersion > localVersion -> "outdated"
            db.latestVersion != null -> "current"
            else -> "unknown"
        }
        return base.copy(
            titleId = tid,
            titleSummary = "${db.titleName} [$tid]",
            versionSummary = if (db.latestVersion != null) "local v${localVersion / 65536} / latest v${db.latestVersion / 65536}" else "local v${localVersion / 65536} / latest unknown",
            versionStatus = status,
            imageUrl = db.imageUrl,
            details = listOf(db.copy(localVersion = localVersion, status = status))
        )
    }

    fun buildSyntheticLibraryStatusJson(file: LibraryFile): String {
        val detail = file.details.firstOrNull() ?: return "{}"
        val jo = JSONObject()
        val arr = JSONArray()
        val item = JSONObject()
        item.put("title_id", detail.titleId)
        item.put("title_name", detail.titleName)
        item.put("local_version", detail.localVersion)
        item.put("latest_version", detail.latestVersion ?: JSONObject.NULL)
        item.put("release_date", detail.releaseDate ?: JSONObject.NULL)
        item.put("publisher", detail.publisher ?: JSONObject.NULL)
        item.put("description", detail.description ?: JSONObject.NULL)
        item.put("image_url", detail.imageUrl ?: JSONObject.NULL)
        item.put("status", detail.status)
        val langs = JSONArray()
        detail.languages.forEach { langs.put(it) }
        item.put("languages", langs)
        val shots = JSONArray()
        detail.screenshotUrls.forEach { shots.put(it) }
        item.put("screenshot_urls", shots)
        arr.put(item)
        jo.put("titles", arr)
        return jo.toString()
    }

    fun putLibraryCacheEntry(
        path: String,
        size: Long,
        modified: Long,
        raw: String?,
        hasError: Boolean = false,
        titleSummary: String = "",
        versionSummary: String = "",
        cacheKind: String = if (hasError) "version_error" else "version"
    ) {
        val entry = libraryCache[path] ?: JSONObject()
        entry.put("size", size)
        entry.put("modified", modified)
        when (cacheKind) {
            "title" -> {
                entry.remove("titleHasError")
                entry.put("titleSummary", titleSummary)
                if (raw != null) entry.put("titleRawJson", raw)
            }
            "title_error" -> {
                entry.put("titleHasError", true)
                entry.put("titleSummary", titleSummary)
                if (raw != null) entry.put("titleRawJson", raw)
            }
            "version_error" -> {
                entry.put("versionHasError", true)
                entry.put("titleSummary", titleSummary)
                entry.put("versionSummary", versionSummary)
            }
            else -> {
                entry.remove("versionHasError")
                if (raw != null) entry.put("versionRawJson", raw)
                entry.put("titleSummary", titleSummary)
                entry.put("versionSummary", versionSummary)
            }
        }
        if (entry.has("rawJson") && !entry.has("titleRawJson")) {
            entry.put("titleRawJson", entry.optString("rawJson"))
            entry.remove("rawJson")
        }
        entry.remove("hasError")
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

    suspend fun ensureTitlesDbReady(reason: String, forceRefresh: Boolean = false): Boolean {
        if (titleDbReady && !forceRefresh) return true
        val result = withContext(Dispatchers.IO) {
            runCatching { NscbBridge.refreshTitleDb(titleDbCacheDir) }
                .getOrElse { "ERROR: ${it.message}" }
        }
        flushRustLogs()
        return if (result.startsWith("ERROR")) {
            appendLog("TitlesDB unavailable during $reason: $result")
            false
        } else {
            titleDbReady = true
            appendLog("TitlesDB ready during $reason: $result")
            true
        }
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
        File(titleDbCacheDir).mkdirs()
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

    val pickSingleInput = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            singleFileInput = uri.toString()
            output = "File selected"
        }
    }

    val pickArchiveInput = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
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
        val snapshotByPath = loadLibrarySnapshot().associateBy { it.path }
        withContext(Dispatchers.IO) {
            val mapped = mutableListOf<LibraryFile>()
            if (usesSafOutputFolder()) {
                val root = outputTreeDocument()
                val docs = if (root != null) collectSupportedLibraryDocumentFiles(root) else emptyList()
                withContext(Dispatchers.Main) {
                    setProgress("Loading library from ${viewModel.outputDirectory} (${docs.size} file(s))")
                }
                for (doc in docs) {
                    val path = doc.uri.toString()
                    val name = doc.name ?: doc.uri.lastPathSegment ?: "file"
                    val size = doc.length()
                    val modified = doc.lastModified()
                    val currentBase = existingByPath[path]?.takeIf { it.size == size && it.modified == modified }
                    val snapshot = snapshotByPath[path]?.takeIf { it.size == size && it.modified == modified }
                    val base = currentBase ?: snapshot ?: LibraryFile(
                        path = path,
                        filename = name,
                        size = size,
                        modified = modified,
                        extension = extensionForPath(name).uppercase(),
                        titleId = extractTitleIdFromFilename(name)
                    )
                    val cached = buildLibraryFileFromCache(base)
                    mapped.add(cached ?: base.copy(
                        filename = name,
                        size = size,
                        modified = modified,
                        extension = extensionForPath(name).uppercase(),
                        titleId = base.titleId ?: extractTitleIdFromFilename(name)
                    ))
                }
            } else {
                val dir = File(viewModel.outputDirectory)
                val files = dir.listFiles()
                    ?.filter { file -> file.isFile && extensionForPath(file.name) in setOf("nsp", "nsz", "xci", "xcz", "nca", "ncz") }
                    ?.sortedByDescending { it.lastModified() } ?: emptyList()
                withContext(Dispatchers.Main) {
                    setProgress("Loading library from ${viewModel.outputDirectory} (${files.size} file(s))")
                }
                for (file in files) {
                    val path = file.absolutePath
                    val size = file.length()
                    val modified = file.lastModified()
                    val currentBase = existingByPath[path]?.takeIf { it.size == size && it.modified == modified }
                    val snapshot = snapshotByPath[path]?.takeIf { it.size == size && it.modified == modified }
                    val base = currentBase ?: snapshot ?: LibraryFile(
                        path = path,
                        filename = file.name,
                        size = size,
                        modified = modified,
                        extension = extensionForPath(file.name).uppercase(),
                        titleId = extractTitleIdFromFilename(file.name)
                    )
                    val cached = buildLibraryFileFromCache(base)
                    mapped.add(cached ?: base.copy(
                        filename = file.name,
                        size = size,
                        modified = modified,
                        extension = extensionForPath(file.name).uppercase(),
                        titleId = normalizeLibraryTitleId(base.titleId) ?: extractTitleIdFromFilename(file.name)
                    ))
                }
            }

            val withoutMeta = mapped.filter { it.titleId != null && (it.titleSummary == "Not checked" || it.titleSummary == "No title metadata found") }
            if (withoutMeta.isNotEmpty()) {
                withContext(Dispatchers.Main) { setProgress("Resolving ${withoutMeta.size} title(s) from filename...") }
                val dbMap = lookupLibraryTitlesBatch(withoutMeta)
                for (i in mapped.indices) {
                    val file = mapped[i]
                    val normalizedTid = normalizeLibraryTitleId(file.titleId)
                    if (normalizedTid != null && (file.titleSummary == "Not checked" || file.titleSummary == "No title metadata found")) {
                        val enriched = buildLibraryFileFromBatch(file, normalizedTid, dbMap)
                        if (enriched.imageUrl != null || enriched.titleSummary != file.titleSummary) {
                            putLibraryCacheEntry(
                                file.path,
                                file.size,
                                file.modified,
                                buildSyntheticLibraryStatusJson(enriched),
                                false,
                                enriched.titleSummary,
                                enriched.versionSummary,
                                cacheKind = "title"
                            )
                        }
                        mapped[i] = enriched
                    }
                }
                saveLibraryCache()
            }

            withContext(Dispatchers.Main) {
                libraryFiles.clear()
                libraryFiles.addAll(mapped)
                setProgress("Library loaded: ${mapped.size} file(s)")
            }
            val activePaths = mapped.map { it.path }.toSet()
            val stalePaths = libraryCache.keys.filter { it !in activePaths }
            if (stalePaths.isNotEmpty()) {
                stalePaths.forEach { libraryCache.remove(it) }
                saveLibraryCache()
            }
            saveLibrarySnapshot(mapped)
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
        libraryCache.remove(path)
        saveLibraryCache()
        saveLibrarySnapshot(libraryFiles.toList())
        appendLog("Deleted library file: ${File(path).name}")
    }

    suspend fun checkLibraryVersions() {
        if (viewModel.keysPath.isBlank()) {
            appendLog("Skipping deep library version verification because prod.keys is not configured. Cached filename and TitlesDB matches remain available.")
            return
        }
        // Phase 1: restore from cache
        val fromCache = libraryFiles.map { buildLibraryFileFromCache(it) }
        if (fromCache.all { it != null }) {
            libraryFiles.clear()
            libraryFiles.addAll(fromCache.filterNotNull())
            output = "Library versions verified from cache."
            return
        }

        // Phase 2: fast filename → TitlesDB batch lookup for files without valid cache
        val uncached = libraryFiles.mapIndexedNotNull { idx, file ->
            if (fromCache[idx] == null) idx to file else null
        }
        if (uncached.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                val filesToResolve = uncached.map { it.second }
                val dbMap = lookupLibraryTitlesBatch(filesToResolve)
                for ((idx, file) in uncached) {
                    val tid = normalizeLibraryTitleId(file.titleId) ?: extractTitleIdFromFilename(file.filename)
                    if (tid != null && dbMap[tid] != null) {
                        val enriched = buildLibraryFileFromBatch(file, tid, dbMap)
                        putLibraryCacheEntry(
                            file.path,
                            file.size,
                            file.modified,
                            buildSyntheticLibraryStatusJson(enriched),
                            false,
                            enriched.titleSummary,
                            enriched.versionSummary,
                            cacheKind = "title"
                        )
                        libraryFiles[idx] = enriched
                    }
                }
                saveLibraryCache()
            }
        }

        // Phase 3: Rust file-parsing fallback for any files still unresolved
        val stillUnresolved = libraryFiles.mapIndexedNotNull { idx, file ->
            if (file.titleSummary == "Not checked" || file.titleSummary == "No title metadata found") idx else null
        }
        if (stillUnresolved.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                for (idx in stillUnresolved) {
                    val file = libraryFiles[idx]
                    val raw = NscbBridge.libraryStatus(file.path, viewModel.keysPath, titleDbCacheDir)
                    val updated = if (raw.startsWith("ERROR")) {
                        putLibraryCacheEntry(
                            file.path,
                            file.size,
                            file.modified,
                            null,
                            true,
                            file.titleSummary,
                            raw,
                            cacheKind = "version_error"
                        )
                        file.copy(titleSummary = file.titleSummary, versionSummary = raw, versionStatus = "error")
                    } else {
                        val parsed = parseLibraryStatusJson(raw, file)
                        putLibraryCacheEntry(
                            file.path,
                            file.size,
                            file.modified,
                            raw,
                            false,
                            parsed.titleSummary,
                            parsed.versionSummary,
                            cacheKind = "version"
                        )
                        parsed
                    }
                    libraryFiles[idx] = updated
                }
            }
            saveLibraryCache()
        }
        output = "Library versions checked. ${libraryFiles.count { it.versionStatus != "unknown" && it.versionStatus != "error" }} enriched."
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

            val rawOutput = viewModel.outputDirectory
            val rawReadable = rawOutput.isNotBlank() && File(rawOutput).isDirectory && File(rawOutput).canRead()
            if (usesSafOutputFolder() && !rawReadable) {
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
            setProgress("Loading TitlesDB...")
            ensureTitlesDbReady("startup")
            refreshLibrary()
            progressStatus = "Idle"
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
                ensureTitlesDbReady("library load")
                refreshLibrary()
                if (libraryFiles.isNotEmpty()) {
                    if (viewModel.keysPath.isNotBlank()) {
                        checkLibraryVersions()
                    } else {
                        appendLog("Skipping deep library version verification because prod.keys is not configured. Showing filename + TitlesDB matches only.")
                    }
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
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TabRow(selectedTabIndex = selectedTabIndex) {
            Tab(selected = selectedTabIndex == 0, enabled = !isRunning, onClick = { selectedTabIndex = 0 }) {
                Text("Merge", modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Tab(selected = selectedTabIndex == 1, enabled = !isRunning, onClick = { selectedTabIndex = 1 }) {
                Text("Info", modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Tab(selected = selectedTabIndex == 2, enabled = !isRunning, onClick = { selectedTabIndex = 2 }) {
                Text("Scan", modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Tab(selected = selectedTabIndex == 3, enabled = !isRunning, onClick = { selectedTabIndex = 3 }) {
                Text("NSZ/XCZ", modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Tab(selected = selectedTabIndex == 4, enabled = !isRunning, onClick = { selectedTabIndex = 4 }) {
                Text("Library", modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Tab(selected = selectedTabIndex == 5, enabled = !isRunning, onClick = { selectedTabIndex = 5 }) {
                Text("Settings", modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (selectedTabIndex) {
                0 -> {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ScreenIntroCard(
                            title = "Merge + Import",
                            subtitle = "Compact setup here, long-running detail in the console modal. Use one pane for sources and one for output controls.",
                            accent = MaterialTheme.colorScheme.primary
                        )
                        TwoColumnLayout(
                            left = {
                                SectionCard(
                                    title = "Input selection",
                                    description = "Pick package files directly or pull everything from the configured import folder. Each line in the field below is treated as one source.",
                                    accent = MaterialTheme.colorScheme.primary
                                ) {
                                    LabeledValue(
                                        label = "Import folder",
                                        value = viewModel.lastScanPath,
                                        explanation = "This folder is reused by Load Import Folder and Scan + Bulk Import.",
                                        monospace = true
                                    )
                                    OutlinedTextField(
                                        value = mergeInputs,
                                        onValueChange = { mergeInputs = it },
                                        label = { Text("Package list (one URI or path per line)") },
                                        modifier = Modifier.fillMaxWidth().height(170.dp),
                                    )
                                    FieldHint("Paste or stage multiple items here. The merge page stays lean while the console modal shows parsing and merge progress.")
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { pickMergeInputs.launch(arrayOf("*/*")) }, enabled = !mergeRunning, modifier = Modifier.weight(1f)) {
                                            Text("Pick package files")
                                        }
                                        Button(
                                            onClick = { loadImportFolderIntoMergeInputs() },
                                            enabled = !mergeRunning && viewModel.lastScanUri.isNotBlank(),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Load import folder")
                                        }
                                    }
                                    FieldHint("Pick package files adds manual sources. Load import folder converts the currently selected SAF folder into a ready-to-run source list.")
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
                                    FieldHint("Bulk import scans the import folder, filters faults and duplicates, then writes directly into the output library.")
                                }
                            },
                            right = {
                                SectionCard(
                                    title = "Output controls",
                                    description = "Keep the final packaging choices here. Settings owns the actual library folder; this page only decides naming and container type.",
                                    accent = MaterialTheme.colorScheme.secondary
                                ) {
                                    LabeledValue(
                                        label = "Output library",
                                        value = viewModel.outputDirectory,
                                        explanation = "Configured in Settings. Merge/import actions write into this folder.",
                                        monospace = true
                                    )
                                    OutlinedTextField(
                                        value = mergeOutputName,
                                        onValueChange = { mergeOutputName = it },
                                        label = { Text("Output file name / stem") },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    FieldHint("For one input package, metadata can still promote a cleaner suggested name. For multi-file merges, this is the visible stem.")
                                    Text("Output container", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Button(onClick = { mergeType = "nsp" }, enabled = !mergeRunning, modifier = Modifier.weight(1f)) { Text("NSP") }
                                        Button(onClick = { mergeType = "xci" }, enabled = !mergeRunning, modifier = Modifier.weight(1f)) { Text("XCI") }
                                    }
                                    FieldHint("NSP is the usual packaged output. XCI is the cart-style container when you explicitly want that format.")
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
                                        Text(if (mergeInputCount <= 1) "Import / Repack to Output Library" else "Run Merge to Output Library")
                                    }
                                    FieldHint("Run merge uses the staged package list above and writes straight to the configured output library. Watch the console modal for filename decisions and progress.")
                                }
                            }
                        )
                    }
                }
                1 -> {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ScreenIntroCard(
                            title = "Inspect Package",
                            subtitle = "Use this page when you want a quick metadata or content read without leaving the main operator flow.",
                            accent = MaterialTheme.colorScheme.tertiary
                        )
                        TwoColumnLayout(
                            left = {
                                SectionCard(
                                    title = "Target file",
                                    description = "Point at one package URI or one local path. This page is intentionally compact because the console modal holds the verbose output.",
                                    accent = MaterialTheme.colorScheme.tertiary
                                ) {
                                    OutlinedTextField(
                                        value = singleFileInput,
                                        onValueChange = { singleFileInput = it },
                                        label = { Text("Input file URI or local path") },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    FieldHint("Pick from SAF or paste a local path. Use this for one-off inspection instead of building a full merge list.")
                                    Button(onClick = { pickSingleInput.launch("*/*") }, enabled = !infoRunning, modifier = Modifier.fillMaxWidth()) {
                                        Text("Pick target file")
                                    }
                                    FieldHint("The picker writes the selected URI back into the field so you can rerun either action without re-picking.")
                                }
                            },
                            right = {
                                SectionCard(
                                    title = "Read actions",
                                    description = "Choose whether you want high-level package metadata or the inner content listing. Both results stream into the console view below.",
                                    accent = MaterialTheme.colorScheme.primary
                                ) {
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
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("Show package info")
                                    }
                                    FieldHint("Show package info returns the top-level metadata summary you usually want before import or merge.")
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
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("Show package content")
                                    }
                                    FieldHint("Show package content lists the inner members and is the better choice when you are checking exactly what sits inside a file.")
                                }
                            }
                        )
                    }
                }
                2 -> {
                    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ScreenIntroCard(
                            title = "Scanner + Library QA",
                            subtitle = "Use this page to verify the output library, surface duplicates and faults, then queue or run merges with as little scrolling as possible.",
                            accent = MaterialTheme.colorScheme.secondary
                        )
                        TwoColumnLayout(
                            left = {
                                SectionCard(
                                    title = "Library actions",
                                    description = "The scanner works against the configured output library. Use it as your cleanup and batch-merge control surface.",
                                    accent = MaterialTheme.colorScheme.secondary
                                ) {
                                    LabeledValue(
                                        label = "Game library",
                                        value = viewModel.outputDirectory,
                                        explanation = "This is the folder being verified and renamed.",
                                        monospace = true
                                    )
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
                                    FieldHint("This scans the configured output library, validates packages, and normalizes names where possible.")
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
                                    FieldHint("Bulk merge walks every mergeable scan group. Use the console modal for per-group output and cancellation state.")
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
                                        FieldHint("Cancel After Current is safe-mode cancellation. It lets the current group finish cleanly before stopping the batch.")
                                    }
                                }
                            },
                            right = {
                                SectionCard(
                                    title = "Findings summary",
                                    description = "The scanner highlights duplicates and faulty files here first so you can clean them up before running more work.",
                                    accent = MaterialTheme.colorScheme.error
                                ) {
                                    if (duplicateLibraryPaths.isEmpty() && faultyFiles.isEmpty()) {
                                        Text("No duplicates or faulty files are currently flagged.", style = MaterialTheme.typography.bodyMedium)
                                        FieldHint("Run Verify + Rename or refresh the library scan to repopulate this summary.")
                                    }
                                    if (duplicateLibraryPaths.isNotEmpty()) {
                                        Text("Duplicate copies found: ${duplicateLibraryPaths.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                        duplicateLibraryPaths.take(6).forEach { path ->
                                            Text(displayNameForRef(path), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                                        }
                                        if (duplicateLibraryPaths.size > 6) {
                                            FieldHint("Showing first 6 duplicates. The full list remains visible in the scanner result flow.")
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
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Delete duplicates")
                                            }
                                            Button(
                                                onClick = { duplicateLibraryPaths.clear() },
                                                enabled = !scanRunning,
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Ignore")
                                            }
                                        }
                                        FieldHint("Delete duplicates removes the extra library copies listed above. Ignore just clears the warning state.")
                                    }
                                    if (faultyFiles.isNotEmpty()) {
                                        Text("Faulty files found: ${faultyFiles.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                        faultyFiles.take(6).forEach { file ->
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(file.filename, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                                                Text(file.reason, style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                        if (faultyFiles.size > 6) {
                                            FieldHint("Showing first 6 faulty files. Use the scan list and console output if you need the full detail.")
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
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Delete faulty")
                                            }
                                            Button(
                                                onClick = { faultyFiles.clear() },
                                                enabled = !scanRunning,
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Ignore")
                                            }
                                        }
                                        FieldHint("Delete faulty removes the flagged bad sources. Ignore keeps the files but clears the summary warning.")
                                    }
                                }
                            }
                        )

                        if (scanResults.isNotEmpty()) {
                            Text("Scanned groups", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Each card groups files by base title. Older versions and exact duplicates are tinted so you can delete or merge with less hunting.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(scanResults) { group ->
                                    ScanGroupCard(
                                        group,
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
                            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text("No results yet. Configure the output library in Settings, then run Verify + Rename Game Library.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                3 -> {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ScreenIntroCard(
                            title = "NSZ / XCZ",
                            subtitle = "Compression and decompression are split into input and output panes so you can see the full path without losing the action controls.",
                            accent = MaterialTheme.colorScheme.tertiary
                        )
                        TwoColumnLayout(
                            left = {
                                SectionCard(
                                    title = "Input + mode",
                                    description = "Choose whether you are compressing or decompressing first, then point to the single source file to process.",
                                    accent = MaterialTheme.colorScheme.tertiary
                                ) {
                                    Text("Mode", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Button(onClick = { archiveMode = "compress"; archiveOutputUri = null }, enabled = !archiveRunning, modifier = Modifier.weight(1f)) {
                                            Text("Compress")
                                        }
                                        Button(onClick = { archiveMode = "decompress"; archiveOutputUri = null }, enabled = !archiveRunning, modifier = Modifier.weight(1f)) {
                                            Text("Decompress")
                                        }
                                    }
                                    FieldHint("Compress produces NSZ/XCZ outputs. Decompress expands them back to the usual source container.")
                                    OutlinedTextField(
                                        value = archiveInput,
                                        onValueChange = { archiveInput = it },
                                        label = { Text("Input file URI or local path") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                    FieldHint("Use a SAF-picked URI or a local path. This page is for one file at a time so the result stays predictable.")
                                    Button(onClick = { pickArchiveInput.launch("*/*") }, enabled = !archiveRunning, modifier = Modifier.fillMaxWidth()) {
                                        Text("Pick source file")
                                    }
                                }
                            },
                            right = {
                                SectionCard(
                                    title = "Output setup",
                                    description = "Name the output, optionally tune compression level, and choose the destination file before running.",
                                    accent = MaterialTheme.colorScheme.primary
                                ) {
                                    OutlinedTextField(
                                        value = archiveOutputName,
                                        onValueChange = {
                                            archiveOutputName = it
                                            archiveOutputUri = null
                                        },
                                        label = { Text("Output file name / stem") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                    FieldHint("Changing the output name clears the current destination so you cannot accidentally write a mismatched filename.")
                                    if (archiveMode == "compress") {
                                        OutlinedTextField(
                                            value = archiveLevel,
                                            onValueChange = { archiveLevel = it.filter { ch -> ch.isDigit() }.take(2) },
                                            label = { Text("Compression level (1-22)") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                        FieldHint("Lower values are faster. Higher values chase smaller files at the cost of more CPU time.")
                                    }
                                    LabeledValue(
                                        label = "Destination status",
                                        value = if (archiveOutputUri != null) "Ready" else "Not selected",
                                        explanation = "Pick output destination chooses where the converted file is written.",
                                        valueColor = if (archiveOutputUri != null) Color(0xFF2DD4BF) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Button(
                                        onClick = {
                                            val ext = archiveOutputExt(archiveInput, archiveMode)
                                            pickArchiveOutput.launch(outputNameWithExt(archiveOutputName, ext))
                                        },
                                        enabled = !archiveRunning && archiveInput.isNotBlank(),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Pick output destination")
                                    }
                                    FieldHint("The save picker is explicit on purpose so you always see where the converted file will land.")
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
                                    FieldHint("The console modal shows every stage: import, conversion, destination copy, and final success/failure state.")
                                }
                            }
                        )
                    }
                }
                4 -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Game Library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    Text(
                                        "${libraryFiles.size} loaded game(s)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (viewModel.outputDirectory.isNotBlank()) {
                                    Text(
                                        text = viewModel.outputDirectory,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = 12.dp)
                                    )
                                }
                            }
                        }
                        if (libraryFiles.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "No output files found in the configured output library.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            items(
                                items = libraryFiles.chunked(2),
                                key = { row -> row.joinToString("|") { it.path } }
                            ) { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    row.forEach { file ->
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
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (row.size < 2) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
                5 -> {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ScreenIntroCard(
                            title = "Settings",
                            subtitle = "Every field is labeled and explained so the page acts like a compact control panel instead of a raw preference dump.",
                            accent = MaterialTheme.colorScheme.secondary
                        )
                        TwoColumnLayout(
                            left = {
                                SectionCard(
                                    title = "Keys + folders",
                                    description = "These paths drive every file workflow in the app. Keep them valid and persistent whenever possible.",
                                    accent = MaterialTheme.colorScheme.secondary
                                ) {
                                    OutlinedTextField(
                                        value = viewModel.keysPath,
                                        onValueChange = { viewModel.updateKeysPath(it) },
                                        label = { Text("prod.keys local path") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                    FieldHint("Point this at a readable prod.keys file. Most metadata and merge actions require it.")
                                    Button(onClick = { pickKeys.launch(arrayOf("*/*")) }, enabled = !settingsRunning, modifier = Modifier.fillMaxWidth()) {
                                        Text("Pick prod.keys")
                                    }

                                    OutlinedTextField(
                                        value = viewModel.lastScanPath,
                                        onValueChange = { viewModel.updateLastScanPath(it) },
                                        label = { Text("Import folder path") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                    FieldHint("This is the folder where new packages come from when you bulk import or load the import folder into Merge.")
                                    Button(onClick = { pickScanFolder.launch(null) }, enabled = !scanRunning, modifier = Modifier.fillMaxWidth()) {
                                        Text("Pick import folder")
                                    }

                                    OutlinedTextField(
                                        value = viewModel.outputDirectory,
                                        onValueChange = { viewModel.updateOutputDirectory(it) },
                                        label = { Text("Output library path") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                    FieldHint("This folder receives merged output, feeds the scanner page, and populates the library viewer.")
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { pickOutputFolder.launch(null) }, enabled = !libraryRunning, modifier = Modifier.weight(1f)) {
                                            Text("Pick output folder")
                                        }
                                        Button(
                                            onClick = {
                                                viewModel.updateOutputDirectory(context.getExternalFilesDir(null)?.absolutePath
                                                    ?: context.filesDir.absolutePath)
                                            },
                                            enabled = !libraryRunning,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Reset to App Dir")
                                        }
                                    }
                                    FieldHint("Reset to App Dir restores the app-private fallback path if you want a known-good writable location.")
                                }
                            },
                            right = {
                                SectionCard(
                                    title = "Automation rules",
                                    description = "These toggles change how imports, merges, and cleanup behave. Each one explains the trade-off so you can keep the page self-documenting.",
                                    accent = MaterialTheme.colorScheme.primary
                                ) {
                                    OptionCard(
                                        title = "Delete source files after successful merge",
                                        description = "Enable this when the import folder should be treated as a staging queue that gets cleaned automatically after a good merge.",
                                        checked = viewModel.deleteSourcesAfterMerge,
                                        enabled = !mergeRunning,
                                        onCheckedChange = { viewModel.updateDeleteSourcesAfterMerge(it) }
                                    )
                                    OptionCard(
                                        title = "Ignore XCI / XCZ files during library merge",
                                        description = "Enable this when your library cleanup should focus on NSP-style content and skip cart-style containers.",
                                        checked = viewModel.ignoreXciInLibraryMerge,
                                        enabled = !mergeRunning,
                                        onCheckedChange = { viewModel.updateIgnoreXciInLibraryMerge(it) }
                                    )
                                    OptionCard(
                                        title = "Analyze package before import",
                                        description = "Keep this on when you want the app to inspect structure, detect faults, and derive a better output name before moving a file into the library.",
                                        checked = viewModel.analyzePackageBeforeImport,
                                        enabled = !mergeRunning && !scanRunning && !libraryRunning,
                                        onCheckedChange = { viewModel.updateAnalyzePackageBeforeImport(it) }
                                    )
                                }
                                SectionCard(
                                    title = "Maintenance",
                                    description = "This section keeps the local metadata cache fresh and reminds you how SAF-backed folder access behaves on Android.",
                                    accent = MaterialTheme.colorScheme.tertiary
                                ) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                settingsRunning = true
                                                startLogPolling()
                                                progressStatus = "Updating TitlesDB..."
                                                val ok = ensureTitlesDbReady("manual refresh", forceRefresh = true)
                                                output = if (ok) "TitlesDB refreshed." else output
                                                progressStatus = "Idle"
                                                settingsRunning = false
                                            }
                                        },
                                        enabled = !settingsRunning,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Refresh TitlesDB")
                                    }
                                    FieldHint("Refresh TitlesDB updates the cached metadata used for artwork, title names, publishers, release dates, and version checks.")
                                    Text(
                                        "Folder access uses SAF pickers. Internal storage usually persists across restarts, while USB or SD card access often needs to be re-picked each session.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
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
fun LibraryHeroArtwork(file: LibraryFile) {
    val heroUrl = file.imageUrl ?: file.details.firstOrNull()?.imageUrl ?: file.details.firstOrNull()?.screenshotUrls?.firstOrNull()
    var bitmap by remember(heroUrl) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(heroUrl) {
        bitmap = null
        if (!heroUrl.isNullOrBlank()) {
            bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    URL(heroUrl).openStream().use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                }.getOrNull()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(12.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = libraryDisplayTitle(file),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (file.imageUrl == null) "No artwork yet" else "Loading artwork…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x33000000))
        )
    }
}

@Composable
fun StatusPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(text = text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

fun libraryDisplayTitle(file: LibraryFile): String {
    return file.details.firstOrNull()?.titleName
        ?.takeIf { it.isNotBlank() }
        ?: file.titleSummary.lineSequence().firstOrNull()?.substringBefore(" [")?.takeIf { it.isNotBlank() }
        ?: file.filename.substringBeforeLast('.')
}

fun libraryVersionDisplay(file: LibraryFile): String {
    val detail = file.details.firstOrNull()
    val localVersion = detail?.localVersion ?: run {
        val stem = File(file.filename).nameWithoutExtension
        val bracket = Regex("\\[v(\\d+)\\]").find(stem)
        when {
            bracket != null -> bracket.groupValues[1].toLongOrNull() ?: 0
            else -> Regex("--v(\\d+)-").find(stem)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        }
    }
    val latestVersion = detail?.latestVersion
    val localLabel = "v${localVersion / 65536}"
    return if (latestVersion != null) {
        val latestLabel = "v${latestVersion / 65536}"
        if (latestVersion > localVersion) "$localLabel / latest $latestLabel" else "$localLabel / up to date"
    } else {
        localLabel
    }
}

fun libraryCardTitleId(file: LibraryFile): String? {
    return file.titleId
        ?.takeIf { it.isNotBlank() }
        ?: file.details.firstOrNull()?.titleId?.takeIf { it.isNotBlank() }
        ?: run {
            val re = Regex("[\\[-]([0-9A-Fa-f]{16})[\\]-]")
            val stem = File(file.filename).nameWithoutExtension
            val normalized = re.find(stem)?.groupValues?.get(1)?.trim()?.uppercase()?.takeIf { it.length == 16 }
            when {
                normalized == null -> null
                normalized.endsWith("000") -> normalized
                normalized.endsWith("800") -> normalized.dropLast(3) + "000"
                else -> {
                    val chars = normalized.toCharArray()
                    val nibble = chars[12].digitToIntOrNull(16) ?: return@run normalized
                    val adjusted = if (nibble > 0) nibble - 1 else 0
                    chars[12] = adjusted.toString(16).uppercase()[0]
                    chars[13] = '0'
                    chars[14] = '0'
                    chars[15] = '0'
                    String(chars)
                }
            }
        }
}

@Composable
fun LibraryFileCard(
    file: LibraryFile,
    formatSize: (Long) -> String,
    formatModified: (Long) -> String,
    onDeleteRequest: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val title = libraryDisplayTitle(file)
    val titleId = libraryCardTitleId(file)
    val versionText = libraryVersionDisplay(file)
    val versionColor = when (file.versionStatus) {
        "outdated" -> MaterialTheme.colorScheme.error
        "current" -> Color(0xFF2DD4BF)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LibraryHeroArtwork(file)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!titleId.isNullOrBlank()) {
                    Text(
                        text = titleId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = versionText,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = versionColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatSize(file.size),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
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
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(group.titleName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    LabeledValue(
                        label = "Base title id",
                        value = group.baseId,
                        explanation = "All files in this card roll up into the same base title group.",
                        monospace = true
                    )
                }
                if (group.latestVersionDb > 0) {
                    val currentMax = group.items.maxOfOrNull { it.version } ?: 0
                    val isOutdated = group.latestVersionDb > currentMax
                    LabeledValue(
                        label = "Latest DB version",
                        value = "v${group.latestVersionDb / 65536}",
                        explanation = if (isOutdated) "A newer version exists than the newest file currently present in this group." else "The newest file in this group already matches the TitlesDB version.",
                        valueColor = if (isOutdated) MaterialTheme.colorScheme.error else Color(0xFF2DD4BF)
                    )
                }
            }

            FieldHint("Rows are grouped by full title ID. Older versions and exact duplicates are highlighted so you can remove clutter before merging.")

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val groupedById = group.items.groupBy { it.titleId }

                groupedById.forEach { (tid, items) ->
                    val sortedItems = items.sortedByDescending { it.version }
                    val latest = sortedItems.first()

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val typeLabel = when {
                                tid.endsWith("000") -> "Base"
                                tid.endsWith("800") -> "Update"
                                else -> "DLC"
                            }
                            LabeledValue(
                                label = "$typeLabel title id",
                                value = "$tid • latest local v${latest.version / 65536}",
                                explanation = "This subsection groups files that share the exact same full title ID.",
                                monospace = true
                            )

                            sortedItems.forEachIndexed { index, item ->
                                val isOlderVersion = index > 0
                                val isExactDupe = sortedItems.any { it !== item && it.version == item.version }
                                val badge = when {
                                    isOlderVersion -> "OLDER VERSION"
                                    isExactDupe -> "DUPLICATE"
                                    else -> "KEEP"
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isExactDupe || isOlderVersion) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.22f)
                                            else MaterialTheme.colorScheme.surface,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = item.filename,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isExactDupe || isOlderVersion) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "$typeLabel • version ${item.version / 65536} • $badge",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isExactDupe || isOlderVersion) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        FieldHint("Delete removes only this one package entry from the scan group.")
                                    }
                                    IconButton(onClick = { onDeleteRequest(item.path) }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                    }
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
