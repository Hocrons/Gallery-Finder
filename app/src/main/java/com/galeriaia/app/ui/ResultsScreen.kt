package com.galeriaia.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(state: UiState, vm: MainViewModel, snackbar: @Composable () -> Unit) {
    val result = state.result ?: return
    val visible = state.visibleHits
    val selected = state.selectedHits
    var preview by remember { mutableStateOf<Hit?>(null) }
    var showMove by remember { mutableStateOf(false) }

    if (state.searching) {
        AlertDialog(onDismissRequest = {}, confirmButton = {}, text = { Text("Buscando parecidas…") })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                },
                title = {
                    Column {
                        Text("${visible.size} encontradas")
                        Text(
                            "${selected.size} selecionadas",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (selected.size == visible.size) TextButton(onClick = vm::selectNone) { Text("Nenhuma") }
                    else TextButton(onClick = vm::selectAll) { Text("Todas") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { showMove = true },
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Mover p/ pasta")
                    }
                    Button(
                        onClick = vm::trashSelected,
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Lixeira (${selected.size})")
                    }
                }
            }
        },
        snackbarHost = snackbar,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchInfo(result, vm::prioritize)

            if (result.semantic) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Slider(
                        value = state.threshold,
                        onValueChange = vm::setThreshold,
                        valueRange = result.sliderRange,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("◀ mais resultados", style = MaterialTheme.typography.labelSmall)
                        Text("mais precisos ▶", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (result.semantic) "Nada com essa precisão. Arraste a barra para a esquerda."
                        else "Nenhuma foto nesse período.",
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                Text(
                    "Toque para marcar/desmarcar · segure para ver a foto",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(100.dp),
                    contentPadding = PaddingValues(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(visible, key = { it.id }) { hit ->
                        Thumb(
                            hit = hit,
                            selected = hit.id !in state.excluded,
                            onClick = { vm.toggle(hit.id) },
                            onLongClick = { preview = hit },
                        )
                    }
                }
            }
        }
    }

    preview?.let { hit ->
        PreviewDialog(
            hit,
            selected = hit.id !in state.excluded,
            onToggle = { vm.toggle(hit.id) },
            onFindSimilar = { vm.searchSimilarTo(hit) },
        ) { preview = null }
    }

    if (showMove) {
        MoveDialog(
            albums = state.albums,
            count = selected.size,
            onDismiss = { showMove = false },
            onConfirm = { path ->
                showMove = false
                vm.moveSelected(path)
            },
        )
    }
}

@Composable
private fun SearchInfo(result: SearchResult, onPrioritize: (com.galeriaia.app.search.DateFilter) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        if (result.exampleUri != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = result.exampleUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Text("Fotos parecidas com esta", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (result.englishText.isNotBlank()) {
            Text("IA buscou: \"${result.englishText}\"", style = MaterialTheme.typography.bodyMedium)
        }
        result.dateLabel?.let { Text("Período: $it", style = MaterialTheme.typography.bodyMedium) }
        result.coverage?.let { cov ->
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 6.dp)) {
                Icon(Icons.Default.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
                Text(
                    "Resultados parciais. $cov",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        val pf = result.pendingFilter
        if (pf != null && result.pendingInRange > 0) {
            Text(
                "${result.pendingInRange} fotos de ${pf.label} ainda não foram analisadas.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = { onPrioritize(pf) }) { Text("Analisar ${pf.label} agora") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Thumb(
    hit: Hit,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selectedColor: Color = MaterialTheme.colorScheme.primary,
    dimUnselected: Boolean = true,
) {
    val primary = selectedColor
    Box(
        Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .then(if (selected) Modifier.border(3.dp, primary, RoundedCornerShape(6.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = hit.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(if (selected || !dimUnselected) 1f else 0.35f),
        )
        if (selected) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Selecionada",
                tint = primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
                    .background(Color.White, CircleShape),
            )
        }
    }
}

@Composable
internal fun PreviewDialog(
    hit: Hit,
    selected: Boolean,
    onToggle: () -> Unit,
    onFindSimilar: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(16.dp)) {
            Column {
                AsyncImage(
                    model = hit.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).clickable(onClick = onDismiss),
                )
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        Instant.ofEpochMilli(hit.dateTaken).atZone(ZoneId.systemDefault()).format(DATE_FMT),
                        modifier = Modifier.weight(1f),
                    )
                    if (onFindSimilar != null) TextButton(onClick = { onDismiss(); onFindSimilar() }) { Text("Parecidas") }
                    TextButton(onClick = onToggle) { Text(if (selected) "Desmarcar" else "Marcar") }
                    TextButton(onClick = onDismiss) { Text("Fechar") }
                }
            }
        }
    }
}

@Composable
private fun MoveDialog(
    albums: List<com.galeriaia.app.data.Album>,
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var chosen by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    val cleanName = newName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "")
    val target = if (cleanName.isNotEmpty()) "Pictures/$cleanName" else chosen

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mover $count foto(s)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it; if (it.isNotBlank()) chosen = null },
                    label = { Text("Nova pasta") },
                    placeholder = { Text("ex.: Prints antigos") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (albums.isNotEmpty()) {
                    Text("ou escolha uma existente:", style = MaterialTheme.typography.labelLarge)
                    HorizontalDivider()
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(albums, key = { it.relativePath }) { album ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { chosen = album.relativePath; newName = "" }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = chosen == album.relativePath && cleanName.isEmpty(), onClick = {
                                    chosen = album.relativePath; newName = ""
                                })
                                Column(Modifier.weight(1f)) {
                                    Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${album.relativePath} · ${album.count}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { target?.let(onConfirm) }, enabled = target != null) { Text("Mover") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
