package de.photofinals.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

private const val GOOGLE_PHOTOS_PACKAGE = "com.google.android.apps.photos"

class MainActivity : ComponentActivity() {
    private lateinit var store: ProjectStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProjectStore(this)
        setContent {
            MaterialTheme {
                PhotoFinalsApp(store)
            }
        }
    }
}

data class ProjectState(
    val all: List<String>,
    val currentRound: List<String>,
    val kept: List<String>,
    val index: Int,
    val round: Int,
    val screen: String,
    val duplicateGroups: List<List<String>> = emptyList(),
    val duplicateSingles: List<String> = emptyList(),
    val duplicateKept: List<String> = emptyList(),
    val duplicateIndex: Int = 0,
    val likelyWhatsAppUris: List<String> = emptyList(),
    val possibleWhatsAppUris: List<String> = emptyList(),
    val lowerQualityCopies: List<String> = emptyList(),
    val seriesGroups: List<List<String>> = emptyList(),
    val seriesSingles: List<String> = emptyList(),
    val seriesKept: List<String> = emptyList(),
    val seriesIndex: Int = 0
)

class ProjectStore(private val activity: ComponentActivity) {
    private val prefs = activity.getSharedPreferences(
        "photo_finals",
        android.content.Context.MODE_PRIVATE
    )

    fun save(s: ProjectState) {
        val o = JSONObject()
        o.put("all", JSONArray(s.all))
        o.put("currentRound", JSONArray(s.currentRound))
        o.put("kept", JSONArray(s.kept))
        o.put("index", s.index)
        o.put("round", s.round)
        o.put("screen", s.screen)
        o.put("duplicateSingles", JSONArray(s.duplicateSingles))
        o.put("duplicateKept", JSONArray(s.duplicateKept))
        o.put("duplicateIndex", s.duplicateIndex)
        o.put("likelyWhatsAppUris", JSONArray(s.likelyWhatsAppUris))
        o.put("possibleWhatsAppUris", JSONArray(s.possibleWhatsAppUris))
        o.put("lowerQualityCopies", JSONArray(s.lowerQualityCopies))
        o.put("seriesSingles", JSONArray(s.seriesSingles))
        o.put("seriesKept", JSONArray(s.seriesKept))
        o.put("seriesIndex", s.seriesIndex)

        val duplicateGroups = JSONArray()
        s.duplicateGroups.forEach { group ->
            duplicateGroups.put(JSONArray(group))
        }
        o.put("duplicateGroups", duplicateGroups)

        val groups = JSONArray()
        s.seriesGroups.forEach { group ->
            groups.put(JSONArray(group))
        }
        o.put("seriesGroups", groups)

        prefs.edit().putString("project", o.toString()).apply()
    }

    fun load(): ProjectState? {
        val raw = prefs.getString("project", null) ?: return null
        return runCatching {
            val o = JSONObject(raw)

            fun arr(name: String): List<String> {
                val a = o.optJSONArray(name) ?: return emptyList()
                return List(a.length()) { i -> a.getString(i) }
            }

            fun nestedArr(name: String): List<List<String>> {
                val outer = o.optJSONArray(name) ?: return emptyList()
                return List(outer.length()) { groupIndex ->
                    val group = outer.optJSONArray(groupIndex) ?: JSONArray()
                    List(group.length()) { i -> group.getString(i) }
                }
            }

            ProjectState(
                all = arr("all"),
                currentRound = arr("currentRound"),
                kept = arr("kept"),
                index = o.optInt("index", 0),
                round = o.optInt("round", 1),
                screen = o.optString("screen", "home"),
                duplicateGroups = nestedArr("duplicateGroups"),
                duplicateSingles = arr("duplicateSingles"),
                duplicateKept = arr("duplicateKept"),
                duplicateIndex = o.optInt("duplicateIndex", 0),
                likelyWhatsAppUris = arr("likelyWhatsAppUris"),
                possibleWhatsAppUris = arr("possibleWhatsAppUris"),
                lowerQualityCopies = arr("lowerQualityCopies"),
                seriesGroups = nestedArr("seriesGroups"),
                seriesSingles = arr("seriesSingles"),
                seriesKept = arr("seriesKept"),
                seriesIndex = o.optInt("seriesIndex", 0)
            )
        }.getOrNull()
    }

    fun persistReadPermission(uri: Uri) {
        runCatching {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    fun clear() = prefs.edit().remove("project").apply()
}

@Composable
fun PhotoFinalsApp(store: ProjectStore) {
    var state by remember {
        mutableStateOf(
            store.load() ?: ProjectState(
                emptyList(), emptyList(), emptyList(), 0, 1, "home"
            )
        )
    }

    fun update(newState: ProjectState) {
        state = newState
        store.save(newState)
    }

    fun resetToHome() {
        store.clear()
        state = ProjectState(emptyList(), emptyList(), emptyList(), 0, 1, "home")
    }

    fun finishDuplicateGroup(selected: List<String>) {
        val newDuplicateKept = (state.duplicateKept + selected).distinct()
        val nextIndex = state.duplicateIndex + 1

        if (nextIndex >= state.duplicateGroups.size) {
            val allowed = (state.duplicateSingles + newDuplicateKept).toSet()
            val pool = state.all.filter { it in allowed }
            update(
                state.copy(
                    currentRound = pool,
                    kept = emptyList(),
                    index = 0,
                    round = 1,
                    screen = "seriesLoading",
                    duplicateKept = newDuplicateKept,
                    duplicateIndex = nextIndex,
                    seriesGroups = emptyList(),
                    seriesSingles = emptyList(),
                    seriesKept = emptyList(),
                    seriesIndex = 0
                )
            )
        } else {
            update(
                state.copy(
                    duplicateKept = newDuplicateKept,
                    duplicateIndex = nextIndex
                )
            )
        }
    }

    fun finishSeriesGroup(selected: List<String>) {
        val newSeriesKept = (state.seriesKept + selected).distinct()
        val nextIndex = state.seriesIndex + 1

        if (nextIndex >= state.seriesGroups.size) {
            val allowed = (state.seriesSingles + newSeriesKept).toSet()
            val pool = state.currentRound.filter { it in allowed }
            update(
                state.copy(
                    currentRound = pool,
                    kept = emptyList(),
                    index = 0,
                    round = 1,
                    screen = "selectionReview",
                    seriesKept = newSeriesKept,
                    seriesIndex = nextIndex
                )
            )
        } else {
            update(
                state.copy(
                    seriesKept = newSeriesKept,
                    seriesIndex = nextIndex
                )
            )
        }
    }

    fun acceptPickedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        uris.forEach(store::persistReadPermission)
        val strings = uris.map(Uri::toString)
        update(
            ProjectState(
                all = strings,
                currentRound = strings,
                kept = emptyList(),
                index = 0,
                round = 1,
                screen = "duplicateLoading"
            )
        )
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> acceptPickedUris(uris) }

    val googlePhotosPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val uris = buildList {
                data?.clipData?.let { clips ->
                    for (i in 0 until clips.itemCount) {
                        clips.getItemAt(i).uri?.let(::add)
                    }
                }
                if (isEmpty()) data?.data?.let(::add)
            }.distinct()
            acceptPickedUris(uris)
        }
    }

    fun launchPicker(defaultTab: ActivityResultContracts.PickVisualMedia.DefaultTab) {
        val request = PickVisualMediaRequest.Builder()
            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
            .setDefaultTab(defaultTab)
            .build()
        picker.launch(request)
    }

    fun launchGooglePhotos() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            setPackage(GOOGLE_PHOTOS_PACKAGE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { googlePhotosPicker.launch(intent) }
            .onFailure {
                launchPicker(ActivityResultContracts.PickVisualMedia.DefaultTab.AlbumsTab)
            }
    }

    when (state.screen) {
        "home" -> HomeScreen(
            hasSaved = state.all.isNotEmpty(),
            versionName = BuildConfig.VERSION_NAME,
            onPickAlbums = { launchGooglePhotos() },
            onPickPhotos = {
                launchPicker(ActivityResultContracts.PickVisualMedia.DefaultTab.PhotosTab)
            },
            onContinue = {
                update(
                    state.copy(
                        screen = when {
                            state.currentRound.isEmpty() -> "home"
                            state.index >= state.currentRound.size -> "roundEnd"
                            else -> "round"
                        }
                    )
                )
            }
        )

        "duplicateLoading" -> DuplicateLoadingScreen(
            uris = state.all,
            onComplete = { analysis ->
                update(
                    state.copy(
                        currentRound = state.all,
                        duplicateGroups = analysis.groups,
                        duplicateSingles = analysis.singles,
                        duplicateKept = emptyList(),
                        duplicateIndex = 0,
                        likelyWhatsAppUris = analysis.likelyWhatsAppUris,
                        possibleWhatsAppUris = analysis.possibleWhatsAppUris,
                        lowerQualityCopies = analysis.lowerQualityCopies,
                        screen = if (analysis.groups.isEmpty()) {
                            "seriesLoading"
                        } else {
                            "duplicateReview"
                        }
                    )
                )
            }
        )

        "duplicateReview" -> {
            val group = state.duplicateGroups.getOrNull(state.duplicateIndex)
            if (group == null) {
                LaunchedEffect(state.duplicateIndex) {
                    val allowed = (state.duplicateSingles + state.duplicateKept).toSet()
                    update(
                        state.copy(
                            currentRound = state.all.filter { it in allowed },
                            screen = "seriesLoading"
                        )
                    )
                }
            } else {
                DuplicateReviewScreen(
                    group = group,
                    groupIndex = state.duplicateIndex,
                    groupCount = state.duplicateGroups.size,
                    originalCount = state.all.size,
                    onKeepSelected = ::finishDuplicateGroup,
                    onKeepAll = { finishDuplicateGroup(group) },
                    onSkipAllDuplicates = {
                        update(
                            state.copy(
                                currentRound = state.all,
                                duplicateKept = emptyList(),
                                duplicateIndex = state.duplicateGroups.size,
                                screen = "seriesLoading"
                            )
                        )
                    }
                )
            }
        }

        "seriesLoading" -> SeriesLoadingScreen(
            uris = state.currentRound,
            onComplete = { analysis ->
                if (analysis.groups.isEmpty()) {
                    update(
                        state.copy(
                            currentRound = state.currentRound,
                            screen = "selectionReview",
                            seriesGroups = emptyList(),
                            seriesSingles = state.currentRound,
                            seriesKept = emptyList(),
                            seriesIndex = 0
                        )
                    )
                } else {
                    update(
                        state.copy(
                            seriesGroups = analysis.groups,
                            seriesSingles = analysis.singles,
                            seriesKept = emptyList(),
                            seriesIndex = 0,
                            screen = "seriesReview"
                        )
                    )
                }
            }
        )

        "seriesReview" -> {
            val group = state.seriesGroups.getOrNull(state.seriesIndex)
            if (group == null) {
                LaunchedEffect(state.seriesIndex) {
                    val allowed = (state.seriesSingles + state.seriesKept).toSet()
                    update(
                        state.copy(
                            currentRound = state.currentRound.filter { it in allowed },
                            screen = "selectionReview"
                        )
                    )
                }
            } else {
                SeriesReviewScreen(
                    group = group,
                    groupIndex = state.seriesIndex,
                    groupCount = state.seriesGroups.size,
                    originalCount = state.all.size,
                    onKeepSelected = ::finishSeriesGroup,
                    onKeepAll = { finishSeriesGroup(group) },
                    onSkipAllSeries = {
                        update(
                            state.copy(
                                currentRound = state.currentRound,
                                kept = emptyList(),
                                index = 0,
                                round = 1,
                                screen = "selectionReview"
                            )
                        )
                    }
                )
            }
        }

        "selectionReview" -> SelectionReviewScreen(
            uris = state.currentRound,
            originalCount = state.all.size,
            seriesCount = state.seriesGroups.size,
            duplicateCount = state.duplicateGroups.size,
            onStart = { update(state.copy(screen = "round")) },
            onChooseAgain = { launchGooglePhotos() },
            onCancel = ::resetToHome
        )

        "round" -> RoundScreen(
            state = state,
            onDecision = { keep ->
                val kept =
                    if (keep) state.kept + state.currentRound[state.index] else state.kept
                val next = state.index + 1
                update(
                    state.copy(
                        kept = kept,
                        index = next,
                        screen = if (next >= state.currentRound.size) "roundEnd" else "round"
                    )
                )
            },
            onUndo = {
                if (state.index > 0) {
                    val previousIndex = state.index - 1
                    val previousUri = state.currentRound[previousIndex]
                    val keptAfterUndo =
                        if (state.kept.lastOrNull() == previousUri) state.kept.dropLast(1)
                        else state.kept
                    update(
                        state.copy(
                            index = previousIndex,
                            kept = keptAfterUndo
                        )
                    )
                }
            }
        )

        "roundEnd" -> RoundEndScreen(
            state = state,
            onUndo = {
                val previousIndex = (state.index - 1).coerceAtLeast(0)
                val previousUri = state.currentRound.getOrNull(previousIndex)
                if (previousUri != null) {
                    val keptAfterUndo =
                        if (state.kept.lastOrNull() == previousUri) state.kept.dropLast(1)
                        else state.kept
                    update(
                        state.copy(
                            index = previousIndex,
                            kept = keptAfterUndo,
                            screen = "round"
                        )
                    )
                }
            },
            onAnother = {
                if (state.kept.isNotEmpty()) {
                    update(
                        state.copy(
                            currentRound = state.kept,
                            kept = emptyList(),
                            index = 0,
                            round = state.round + 1,
                            screen = "round"
                        )
                    )
                }
            },
            onFinish = { update(state.copy(screen = "final")) }
        )

        "final" -> FinalScreen(
            uris = state.kept,
            round = state.round,
            originalCount = state.all.size,
            versionName = BuildConfig.VERSION_NAME,
            onBack = { update(state.copy(screen = "roundEnd")) },
            onNew = ::resetToHome
        )

        else -> resetToHome()
    }
}

@Composable
fun HomeScreen(
    hasSaved: Boolean,
    versionName: String,
    onPickAlbums: () -> Unit,
    onPickPhotos: () -> Unit,
    onContinue: () -> Unit
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Foto Finals", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            Text(
                "Serien vergleichen und Fotos Runde für Runde auf deine Favoriten reduzieren.",
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))

            Button(onClick = onPickAlbums, modifier = Modifier.fillMaxWidth()) {
                Text("Google-Fotos-Album / Fotos auswählen")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Nach der Auswahl sucht Foto Finals zuerst nach Kopien/WhatsApp-Versionen und danach nach ähnlichen Aufnahmeserien.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = onPickPhotos, modifier = Modifier.fillMaxWidth()) {
                Text("Fotoübersicht öffnen")
            }

            if (hasSaved) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                    Text("Gespeicherte Auswahl fortsetzen")
                }
            }

            Spacer(Modifier.height(28.dp))
            Text("Version $versionName", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun DuplicateLoadingScreen(
    uris: List<String>,
    onComplete: (DuplicateAnalysis) -> Unit
) {
    val context = LocalContext.current
    var failed by remember(uris) { mutableStateOf(false) }

    LaunchedEffect(uris) {
        runCatching { detectDuplicatePhotos(context, uris) }
            .onSuccess(onComplete)
            .onFailure {
                failed = true
                onComplete(
                    DuplicateAnalysis(
                        groups = emptyList(),
                        singles = uris,
                        likelyWhatsAppUris = emptyList(),
                        possibleWhatsAppUris = emptyList(),
                        lowerQualityCopies = emptyList()
                    )
                )
            }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(20.dp))
            Text(
                if (failed) "Kopienanalyse wird übersprungen."
                else "Kopien und WhatsApp-Versionen werden erkannt …",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Die Bilder werden nur lokal verglichen. Foto Finals löscht oder verändert keine Originale.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun DuplicateReviewScreen(
    group: List<String>,
    groupIndex: Int,
    groupCount: Int,
    originalCount: Int,
    onKeepSelected: (List<String>) -> Unit,
    onKeepAll: () -> Unit,
    onSkipAllDuplicates: () -> Unit
) {
    var selected by remember(groupIndex) {
        mutableStateOf<Set<String>>(group.firstOrNull()?.let(::setOf) ?: emptySet())
    }
    var zoomUri by remember(groupIndex) { mutableStateOf<String?>(null) }

    zoomUri?.let { uri ->
        ZoomPhotoDialog(
            uri = uri,
            onDismiss = { zoomUri = null }
        )
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Mögliche Kopie ${groupIndex + 1} von $groupCount",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(4.dp))
            Text("${group.size} nahezu identische Bilder · insgesamt $originalCount importiert")
            Text(
                "Die technisch hochwertigste Version steht zuerst. Tippe ein Bild zum Zoomen an.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(6.dp)
        ) {
            items(group) { uri ->
                val isSelected = uri in selected
                val isPreferred = uri == group.firstOrNull()

                Card(
                    border = if (isSelected) {
                        BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        null
                    },
                    modifier = Modifier.padding(4.dp)
                ) {
                    Column {
                        Box {
                            AsyncImage(
                                model = uri,
                                contentDescription = "Foto vergrößern",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { zoomUri = uri }
                            )
                            if (isPreferred) {
                                Surface(
                                    tonalElevation = 4.dp,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(6.dp)
                                ) {
                                    Text(
                                        "Beste Qualität?",
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }

                        PhotoDetailsLine(uri = uri)

                        TextButton(
                            onClick = {
                                selected =
                                    if (isSelected) selected - uri else selected + uri
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isSelected) "✓ Behalten" else "Mit behalten")
                        }
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Button(
                onClick = { onKeepSelected(selected.toList()) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (selected.size == 1) "Ausgewählte Version behalten"
                    else "${selected.size} Versionen behalten"
                )
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onKeepAll,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Keine Kopie – alle weitergeben")
            }

            TextButton(
                onClick = onSkipAllDuplicates,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Kopienprüfung komplett überspringen")
            }
        }
    }
}

@Composable
private fun PhotoDetailsLine(uri: String) {
    val context = LocalContext.current
    val details by produceState<PhotoDetails?>(initialValue = null, uri) {
        value = runCatching { loadPhotoDetails(context, uri) }.getOrNull()
    }

    val text = details?.let(::formatPhotoDetails) ?: "Bilddaten werden gelesen …"

    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center
    )
}

private fun formatPhotoDetails(details: PhotoDetails): String {
    val dimensions = if (details.width != null && details.height != null) {
        "${details.width} × ${details.height}"
    } else {
        "Auflösung unbekannt"
    }

    val size = details.sizeBytes?.let(::formatBytes) ?: "Größe unbekannt"
    val metadata = if (details.hasCameraMetadata) "Aufnahmedaten ✓" else "keine Aufnahmedaten"
    val origin = when (details.whatsAppHint) {
        WhatsAppHint.LIKELY -> " · WA"
        WhatsAppHint.POSSIBLE -> " · WA?"
        WhatsAppHint.NONE -> ""
    }

    return "$dimensions · $size · $metadata$origin"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000L -> String.format(java.util.Locale.GERMANY, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

@Composable
private fun PhotoOriginBadges(
    uri: String,
    likelyWhatsAppUris: List<String>,
    possibleWhatsAppUris: List<String>,
    lowerQualityCopies: List<String>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val knownHint = when {
        uri in likelyWhatsAppUris -> WhatsAppHint.LIKELY
        uri in possibleWhatsAppUris -> WhatsAppHint.POSSIBLE
        else -> null
    }

    val details by produceState<PhotoDetails?>(initialValue = null, uri, knownHint) {
        value = if (knownHint == null) {
            runCatching { loadPhotoDetails(context, uri) }.getOrNull()
        } else {
            null
        }
    }

    val hint = knownHint ?: details?.whatsAppHint ?: WhatsAppHint.NONE
    val lowerQuality = uri in lowerQualityCopies

    if (hint == WhatsAppHint.NONE && !lowerQuality) return

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (lowerQuality) OriginBadge("Kopie ↓")
        when (hint) {
            WhatsAppHint.LIKELY -> OriginBadge("WA")
            WhatsAppHint.POSSIBLE -> OriginBadge("WA?")
            WhatsAppHint.NONE -> Unit
        }
    }
}

@Composable
private fun OriginBadge(label: String) {
    Surface(
        tonalElevation = 4.dp,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun SeriesLoadingScreen(
    uris: List<String>,
    onComplete: (SeriesAnalysis) -> Unit
) {
    val context = LocalContext.current
    var failed by remember(uris) { mutableStateOf(false) }

    LaunchedEffect(uris) {
        runCatching { detectPhotoSeries(context, uris) }
            .onSuccess(onComplete)
            .onFailure {
                failed = true
                onComplete(SeriesAnalysis(groups = emptyList(), singles = uris))
            }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(20.dp))
            Text(
                if (failed) "Serienanalyse wird übersprungen."
                else "Ähnliche Aufnahmeserien werden erkannt …",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Die Analyse läuft auf dem Gerät und verändert keine Originalfotos.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun SeriesReviewScreen(
    group: List<String>,
    groupIndex: Int,
    groupCount: Int,
    originalCount: Int,
    onKeepSelected: (List<String>) -> Unit,
    onKeepAll: () -> Unit,
    onSkipAllSeries: () -> Unit
) {
    var selected by remember(groupIndex) { mutableStateOf<Set<String>>(emptySet()) }
    var zoomUri by remember(groupIndex) { mutableStateOf<String?>(null) }

    zoomUri?.let { uri ->
        ZoomPhotoDialog(
            uri = uri,
            onDismiss = { zoomUri = null }
        )
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Serie ${groupIndex + 1} von $groupCount",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(4.dp))
            Text("${group.size} ähnliche Fotos · insgesamt $originalCount importiert")
            Text(
                "Markiere das beste Foto – oder mehrere, wenn du dich noch nicht entscheiden willst.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(6.dp)
        ) {
            items(group) { uri ->
                val isSelected = uri in selected
                Card(
                    border = if (isSelected) {
                        BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        null
                    },
                    modifier = Modifier.padding(4.dp)
                ) {
                    Column {
                        AsyncImage(
                            model = uri,
                            contentDescription = "Foto vergrößern",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { zoomUri = uri }
                        )
                        TextButton(
                            onClick = {
                                selected =
                                    if (isSelected) selected - uri else selected + uri
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isSelected) "✓ Behalten" else "Zum Behalten markieren")
                        }
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Button(
                onClick = { onKeepSelected(selected.toList()) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (selected.size == 1) "Ausgewähltes Foto behalten"
                    else "${selected.size} ausgewählte Fotos behalten"
                )
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onKeepAll,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Keine Serie – alle behalten")
            }

            TextButton(
                onClick = onSkipAllSeries,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Serienerkennung komplett überspringen")
            }
        }
    }
}

@Composable
fun ZoomPhotoDialog(
    uri: String,
    onDismiss: () -> Unit
) {
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offsetX by remember(uri) { mutableFloatStateOf(0f) }
    var offsetY by remember(uri) { mutableFloatStateOf(0f) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Schließen") }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clipToBounds()
                        .pointerInput(uri) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    scale = (scale * zoom).coerceIn(1f, 6f)
                                    if (scale > 1.02f) {
                                        offsetX += pan.x
                                        offsetY += pan.y
                                    } else {
                                        scale = 1f
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                    event.changes.forEach { if (it.pressed) it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = uri,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        }
                    )
                }
                Text(
                    "Mit zwei Fingern zoomen und den Bildausschnitt verschieben.",
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun SelectionReviewScreen(
    uris: List<String>,
    originalCount: Int,
    seriesCount: Int,
    duplicateCount: Int,
    onStart: () -> Unit,
    onChooseAgain: () -> Unit,
    onCancel: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(20.dp)) {
            Text("Auswahl vorbereitet", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text("${uris.size} Fotos werden in Runde 1 geprüft.")
            if (duplicateCount > 0) {
                Text(
                    "$duplicateCount mögliche Kopien wurden vorab geprüft.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (seriesCount > 0) {
                Text(
                    "$seriesCount Serien wurden anschließend verglichen; ursprünglich waren es $originalCount Fotos.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(6.dp)
        ) {
            items(uris) { uri ->
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.aspectRatio(1f).padding(2.dp)
                )
            }
        }

        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text("Runde 1 starten")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onChooseAgain, modifier = Modifier.fillMaxWidth()) {
                Text("Andere Auswahl")
            }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Abbrechen")
            }
        }
    }
}

@Composable
fun RoundScreen(
    state: ProjectState,
    onDecision: (Boolean) -> Unit,
    onUndo: () -> Unit
) {
    val uri = state.currentRound[state.index]
    var dragX by remember(uri) { mutableFloatStateOf(0f) }
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offsetX by remember(uri) { mutableFloatStateOf(0f) }
    var offsetY by remember(uri) { mutableFloatStateOf(0f) }
    val swipeThreshold = with(LocalDensity.current) { 88.dp.toPx() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Runde ${state.round}")
            Text("${state.index + 1} / ${state.currentRound.size}")
            TextButton(onClick = onUndo, enabled = state.index > 0) {
                Text("Undo")
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .pointerInput(uri) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var hadMultiTouch = false
                        dragX = 0f

                        do {
                            val event = awaitPointerEvent()
                            val pressedCount = event.changes.count { it.pressed }

                            if (pressedCount >= 2) {
                                if (!hadMultiTouch) dragX = 0f
                                hadMultiTouch = true
                            }

                            if ((hadMultiTouch || scale > 1.02f) && pressedCount > 0) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()

                                scale = (scale * zoom).coerceIn(1f, 5f)
                                if (scale > 1.02f) {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                } else {
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                }

                                event.changes.forEach { change ->
                                    if (change.pressed) change.consume()
                                }
                            } else if (!hadMultiTouch && pressedCount == 1 && scale <= 1.02f) {
                                val change = event.changes.first { it.pressed }
                                dragX += change.position.x - change.previousPosition.x
                                change.consume()
                            }
                        } while (event.changes.any { it.pressed })

                        if (
                            !hadMultiTouch &&
                            scale <= 1.02f &&
                            abs(dragX) >= swipeThreshold
                        ) {
                            onDecision(dragX > 0f)
                        }
                        dragX = 0f
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    translationX = if (scale <= 1.02f) dragX else offsetX
                    translationY = offsetY
                    scaleX = scale
                    scaleY = scale
                    rotationZ = 0f
                    alpha = 1f
                }
            )

            PhotoOriginBadges(
                uri = uri,
                likelyWhatsAppUris = state.likelyWhatsAppUris,
                possibleWhatsAppUris = state.possibleWhatsAppUris,
                lowerQualityCopies = state.lowerQualityCopies,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
            )

            if (scale <= 1.02f && abs(dragX) > 24f) {
                Surface(
                    tonalElevation = 4.dp,
                    modifier = Modifier
                        .align(if (dragX > 0) Alignment.TopStart else Alignment.TopEnd)
                        .padding(20.dp)
                ) {
                    Text(
                        if (dragX > 0) "BEHALTEN →" else "← RAUS",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { onDecision(false) },
                modifier = Modifier.weight(1f)
            ) {
                Text("← Raus")
            }
            Button(
                onClick = { onDecision(true) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Behalten →")
            }
        }
    }
}

@Composable
fun RoundEndScreen(
    state: ProjectState,
    onUndo: () -> Unit,
    onAnother: () -> Unit,
    onFinish: () -> Unit
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Runde ${state.round} abgeschlossen",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(18.dp))
            Text("${state.currentRound.size} Fotos angesehen")
            Text(
                "${state.kept.size} Fotos ausgewählt",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(32.dp))

            OutlinedButton(
                onClick = onUndo,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Letzte Entscheidung zurücknehmen")
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onFinish,
                enabled = state.kept.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Auswahl abschließen")
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onAnother,
                enabled = state.kept.size > 1,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Weitere Runde mit ${state.kept.size} Fotos")
            }

            if (state.kept.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Es ist kein Foto übrig. Nimm die letzte Entscheidung zurück, um fortzufahren.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun FinalScreen(
    uris: List<String>,
    round: Int,
    originalCount: Int,
    versionName: String,
    onBack: () -> Unit,
    onNew: () -> Unit
) {
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            Text("Deine Finalisten", style = MaterialTheme.typography.headlineMedium)
            Text(
                "${uris.size} von $originalCount Fotos · nach $round Runde${if (round == 1) "" else "n"}"
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(4.dp)
        ) {
            items(uris) { uri ->
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .padding(2.dp)
                        .clickable { openSingleImage(context, uri) }
                )
            }
        }

        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Button(
                onClick = { sendToGooglePhotos(context, uris) },
                enabled = uris.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("An Google Fotos übergeben")
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { shareSelection(context, uris) },
                enabled = uris.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Finalisten teilen")
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Google Fotos erhält die Auswahl über Android-Teilen. Ein neues Zielalbum wird dort manuell gewählt; Originale werden von Foto Finals nicht gelöscht.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text("Zurück")
                }
                OutlinedButton(onClick = onNew, modifier = Modifier.weight(1f)) {
                    Text("Neue Auswahl")
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Foto Finals $versionName",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

private fun openSingleImage(context: Context, uriString: String) {
    val uri = Uri.parse(uriString)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Foto öffnen"))
    }.onFailure {
        Toast.makeText(
            context,
            "Das Foto konnte nicht geöffnet werden.",
            Toast.LENGTH_SHORT
        ).show()
    }
}

private fun shareSelection(context: Context, uriStrings: List<String>) {
    if (uriStrings.isEmpty()) return
    val intent = createShareIntent(context, uriStrings)
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Finalisten teilen"))
    }.onFailure {
        Toast.makeText(
            context,
            "Die Auswahl konnte nicht geteilt werden.",
            Toast.LENGTH_SHORT
        ).show()
    }
}

private fun sendToGooglePhotos(context: Context, uriStrings: List<String>) {
    if (uriStrings.isEmpty()) return
    val intent = createShareIntent(context, uriStrings).apply {
        setPackage(GOOGLE_PHOTOS_PACKAGE)
    }

    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(
            context,
            "Google Fotos ist nicht als Ziel verfügbar. Öffne stattdessen die Teilen-Auswahl.",
            Toast.LENGTH_LONG
        ).show()
        shareSelection(context, uriStrings)
    } catch (_: SecurityException) {
        Toast.makeText(
            context,
            "Google Fotos konnte nicht direkt geöffnet werden. Öffne stattdessen die Teilen-Auswahl.",
            Toast.LENGTH_LONG
        ).show()
        shareSelection(context, uriStrings)
    }
}

private fun createShareIntent(context: Context, uriStrings: List<String>): Intent {
    val uris = ArrayList(uriStrings.map(Uri::parse))
    val clipData = ClipData.newUri(
        context.contentResolver,
        "Foto Finals",
        uris.first()
    )
    uris.drop(1).forEach { uri ->
        clipData.addItem(ClipData.Item(uri))
    }

    return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "image/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        this.clipData = clipData
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}