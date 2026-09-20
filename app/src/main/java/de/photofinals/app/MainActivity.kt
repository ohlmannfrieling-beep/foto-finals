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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.ui.text.style.TextAlign
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
    val screen: String
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
        prefs.edit().putString("project", o.toString()).apply()
    }

    fun load(): ProjectState? {
        val raw = prefs.getString("project", null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            fun arr(name: String) = o.getJSONArray(name).let { a ->
                List(a.length()) { i -> a.getString(i) }
            }
            ProjectState(
                all = arr("all"),
                currentRound = arr("currentRound"),
                kept = arr("kept"),
                index = o.getInt("index"),
                round = o.getInt("round"),
                screen = o.getString("screen")
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

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach(store::persistReadPermission)
            val strings = uris.map(Uri::toString)
            update(
                ProjectState(
                    all = strings,
                    currentRound = strings,
                    kept = emptyList(),
                    index = 0,
                    round = 1,
                    screen = "selectionReview"
                )
            )
        }
    }

    fun launchPicker(defaultTab: ActivityResultContracts.PickVisualMedia.DefaultTab) {
        val request = PickVisualMediaRequest.Builder()
            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
            .setDefaultTab(defaultTab)
            .build()
        picker.launch(request)
    }

    when (state.screen) {
        "home" -> HomeScreen(
            hasSaved = state.all.isNotEmpty(),
            versionName = BuildConfig.VERSION_NAME,
            onPickAlbums = {
                launchPicker(ActivityResultContracts.PickVisualMedia.DefaultTab.AlbumsTab)
            },
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

        "selectionReview" -> SelectionReviewScreen(
            uris = state.all,
            onStart = { update(state.copy(screen = "round")) },
            onChooseAgain = {
                launchPicker(ActivityResultContracts.PickVisualMedia.DefaultTab.AlbumsTab)
            },
            onCancel = {
                store.clear()
                state = ProjectState(emptyList(), emptyList(), emptyList(), 0, 1, "home")
            }
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
                        ProjectState(
                            all = state.all,
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
            onNew = {
                store.clear()
                state = ProjectState(emptyList(), emptyList(), emptyList(), 0, 1, "home")
            }
        )

        else -> {
            store.clear()
            state = ProjectState(emptyList(), emptyList(), emptyList(), 0, 1, "home")
        }
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
                "Fotos Runde für Runde auf deine Favoriten reduzieren.",
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))

            Button(onClick = onPickAlbums, modifier = Modifier.fillMaxWidth()) {
                Text("Album / Fotos auswählen")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Öffnet direkt die Albumansicht. Bei Google-Fotos-Cloud-Alben ist weiterhin Mehrfachauswahl nötig.",
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
fun SelectionReviewScreen(
    uris: List<String>,
    onStart: () -> Unit,
    onChooseAgain: () -> Unit,
    onCancel: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(20.dp)) {
            Text("Auswahl vorbereitet", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text("${uris.size} Fotos werden in Runde 1 geprüft.")
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

    val transformState = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        if (scale > 1f) {
            offsetX += pan.x
            offsetY += pan.y
        } else {
            offsetX = 0f
            offsetY = 0f
        }
    }

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
                .pointerInput(uri, scale) {
                    if (scale <= 1.02f) {
                        detectDragGestures(
                            onDragEnd = {
                                if (abs(dragX) > 140f) {
                                    onDecision(dragX > 0)
                                }
                                dragX = 0f
                            }
                        ) { change, amount ->
                            change.consume()
                            dragX += amount.x
                        }
                    }
                }
                .transformable(transformState),
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
                    rotationZ = if (scale <= 1.02f) dragX / 80f else 0f
                }
            )
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
