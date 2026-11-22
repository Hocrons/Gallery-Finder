package com.galeriaia.app.ui.files

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.galeriaia.app.storage.FileCategory
import com.galeriaia.app.storage.FileEntry
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private fun fmtDate(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DATE)

private fun requestAllFiles(ctx: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
    runCatching { ctx.startActivity(intent) }.onFailure {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}

/** Abre o arquivo no app padrão (visualizar antes de apagar). */
private fun openFile(ctx: Context, path: String) {
    runCatching {
        val f = File(path)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
fun FilesRoot(vm: FilesViewModel, state: FilesUi) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onVideoTrashResult(it.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(state.pendingVideoTrash) {
        state.pendingVideoTrash?.let { trashLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }
    val host: @Composable () -> Unit = { SnackbarHost(snackbar) }
    when (state.screen) {
        FilesScreen.HOME -> FilesHome(vm, state, host)
        FilesScreen.LIST -> FileListScreen(vm, state, host)
        FilesScreen.DUPLICATES -> DuplicateFilesScreen(vm, state, host)
        FilesScreen.TRASH -> TrashScreen(vm, state, host)
        FilesScreen.VIDEOS -> VideosScreen(vm, state, host)
    }
    state.busy?.let {
        AlertDialog(onDismissRequest = {}, confirmButton = {}, icon = { CircularProgressIndicator() }, text = { Text(it) })
    }
}

// ======================= Início =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilesHome(vm: FilesViewModel, state: FilesUi, snackbar: @Composable () -> Unit) {
    val ctx = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text("Arquivos") }) }, snackbarHost = snackbar) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StorageCard(state)

            if (!state.hasAccess) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Acesso a todos os arquivos", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Para analisar documentos, downloads, APKs e vídeos, o Android pede uma permissão especial. " +
                                "Na tela que abrir, ative \"Permitir acesso para gerenciar todos os arquivos\" e volte."
                        )
                        Button(onClick = { requestAllFiles(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Permitir") }
                    }
                }
                VideosCard(vm, state)
                return@Column
            }

            if (!state.scanned) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Analisar armazenamento", style = MaterialTheme.typography.titleMedium)
                        Text("Lê a lista de arquivos (leva alguns segundos). Nada é apagado sem você confirmar.")
                        if (state.scanning) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("${state.scanCount} arquivos lidos…", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Button(onClick = vm::scan, modifier = Modifier.fillMaxWidth()) { Text("Analisar agora") }
                        }
                    }
                }
            } else {
                CategoriesCard(state, vm)
                ActionCard(
                    title = "Arquivos grandes",
                    subtitle = "${state.largeCount} arquivos acima de 50 MB somam ${formatSize(state.largeTotal)}",
                    button = "Ver do maior para o menor",
                    onClick = vm::openLarge,
                )
                ActionCard(
                    title = "Arquivos duplicados",
                    subtitle = when {
                        state.dupGroups != null -> "${state.dupGroups.size} grupos · ${formatSize(state.dupWaste)} em cópias"
                        else -> "Arquivos idênticos, mesmo com nomes diferentes"
                    },
                    button = if (state.dupGroups != null) "Ver duplicados" else "Procurar duplicados",
                    onClick = if (state.dupGroups != null) vm::openDuplicates else vm::findDuplicates,
                )
                CleanupCard(state, vm)
                OutlinedButton(onClick = vm::scan, enabled = !state.scanning, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.scanning) "Analisando… ${state.scanCount}" else "Analisar de novo")
                }
            }
            VideosCard(vm, state)
            TrashCard(state, vm)
        }
    }
}

@Composable
private fun StorageCard(state: FilesUi) {
    if (state.deviceTotal <= 0) return
    val used = state.deviceTotal - state.deviceFree
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Armazenamento", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { used.toFloat() / state.deviceTotal },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
            Text("${formatSize(used)} usados · ${formatSize(state.deviceFree)} livres de ${formatSize(state.deviceTotal)}")
        }
    }
}

@Composable
private fun CategoriesCard(state: FilesUi, vm: FilesViewModel) {
    val max = state.byCategory.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("O que ocupa espaço", style = MaterialTheme.typography.titleMedium)
            Text(
                "Toque numa categoria para ver os maiores arquivos dela.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.byCategory.forEach { (cat, size, count) ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable { vm.setLargeCategory(cat); vm.openLarge() }.padding(vertical = 4.dp),
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(cat.label, Modifier.weight(1f))
                        Text("${formatSize(size)} · $count", style = MaterialTheme.typography.bodySmall)
                    }
                    LinearProgressIndicator(progress = { size.toFloat() / max }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ActionCard(title: String, subtitle: String, button: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(button) }
        }
    }
}

@Composable
private fun CleanupCard(state: FilesUi, vm: FilesViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Limpeza sugerida", style = MaterialTheme.typography.titleMedium)
            if (state.cleanup.isEmpty()) Text("Nada para limpar por aqui ✔")
            state.cleanup.forEach { g ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { vm.openCleanup(g) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(g.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (g.dirs.isNotEmpty()) "${g.dirs.size} pastas" else "${g.items.size} arquivos",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (g.dirs.isEmpty()) Text(formatSize(g.totalSize), fontWeight = FontWeight.Bold)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun VideosCard(vm: FilesViewModel, state: FilesUi) {
    ActionCard(
        title = "Buscar vídeos por descrição",
        subtitle = "Igual à busca de fotos: \"vídeo do show\", \"meu cachorro correndo\"…",
        button = "Abrir busca de vídeos",
        onClick = vm::openVideos,
    )
}

@Composable
private fun TrashCard(state: FilesUi, vm: FilesViewModel) {
    if (!state.hasAccess) return
    Card(
        Modifier.fillMaxWidth(),
        colors = if (state.trash.isNotEmpty()) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Lixeira do app", style = MaterialTheme.typography.titleMedium)
            Text(
                if (state.trash.isEmpty()) "Vazia."
                else "${state.trash.size} arquivo(s) · ${formatSize(state.trashSize)}. " +
                    "Ficam 30 dias e depois são apagados. Esvazie para liberar o espaço agora."
            )
            if (state.trash.isNotEmpty()) Button(onClick = vm::openTrash, modifier = Modifier.fillMaxWidth()) {
                Text("Ver lixeira")
            }
        }
    }
}

// ======================= Lista de arquivos =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileListScreen(vm: FilesViewModel, state: FilesUi, snackbar: @Composable () -> Unit) {
    val list = state.list ?: return
    val ctx = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
                title = {
                    Column {
                        Text(list.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (list.dirs.isEmpty()) Text(
                            "${list.selected.size} marcados · ${formatSize(list.selectedSize)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                actions = {
                    if (list.dirs.isEmpty()) {
                        if (list.selected.size == list.items.size && list.items.isNotEmpty()) TextButton(onClick = vm::selectNone) { Text("Nenhum") }
                        else TextButton(onClick = vm::selectAll) { Text("Todos") }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                if (list.dirs.isNotEmpty()) {
                    Button(onClick = vm::deleteEmptyDirs, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
                        Text("Apagar ${list.dirs.size} pastas vazias")
                    }
                } else {
                    Button(
                        onClick = vm::trashSelectedFromList,
                        enabled = list.selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                    ) {
                        Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Lixeira do app (${list.selected.size} · ${formatSize(list.selectedSize)})")
                    }
                }
            }
        },
        snackbarHost = snackbar,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    list.description + if (list.dirs.isEmpty()) " Toque no ícone para abrir o arquivo." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (list.key == "large") item { LargeFilters(state, vm) }
            if (list.dirs.isNotEmpty()) {
                items(list.dirs) { d ->
                    Text(d.removePrefix("/storage/emulated/0/"), Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
            } else {
                if (list.items.isEmpty()) item { Text("Nada aqui.", Modifier.padding(32.dp)) }
                items(list.items, key = { it.path }) { f ->
                    FileRow(f, selected = f.path in list.selected, onToggle = { vm.toggle(f.path) }, onOpen = { openFile(ctx, f.path) })
                }
            }
        }
    }
}

@Composable
private fun LargeFilters(state: FilesUi, vm: FilesViewModel) {
    Column(Modifier.padding(bottom = 4.dp)) {
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { FilterChip(selected = state.largeCategory == null, onClick = { vm.setLargeCategory(null) }, label = { Text("Todos") }) }
            items(FileCategory.entries.toList()) { c ->
                FilterChip(selected = state.largeCategory == c, onClick = { vm.setLargeCategory(c) }, label = { Text(c.label) })
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(AgeFilter.entries.toList()) { a ->
                FilterChip(selected = state.largeAge == a, onClick = { vm.setLargeAge(a) }, label = { Text(a.label) })
            }
        }
    }
}

@Composable
private fun FileThumb(f: FileEntry, onOpen: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        if (f.category == FileCategory.IMAGE || f.category == FileCategory.VIDEO) {
            AsyncImage(model = File(f.path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (f.category == FileCategory.VIDEO) Icon(Icons.Default.PlayArrow, null, tint = Color.White)
        } else {
            Text(f.name.substringAfterLast('.', "?").take(4).uppercase(), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun FileRow(f: FileEntry, selected: Boolean, onToggle: () -> Unit, onOpen: () -> Unit, badge: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumb(f, onOpen)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (badge != null) Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(end = 6.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
                Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                f.folder.removePrefix("/storage/emulated/0").ifEmpty { "/" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("${formatSize(f.size)} · ${fmtDate(f.modified)}", style = MaterialTheme.typography.bodySmall)
        }
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
    }
}

// ======================= Duplicados =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DuplicateFilesScreen(vm: FilesViewModel, state: FilesUi, snackbar: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val groups = state.dupGroups
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
                title = {
                    Column {
                        Text(if (state.dupComputing) "Procurando duplicados…" else "${groups?.size ?: 0} grupos de duplicados")
                        if (!state.dupComputing) Text(
                            "${state.dupSelected.size} marcados · ${formatSize(state.dupSelectedSize)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (!state.dupComputing) Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = vm::trashDuplicates,
                    enabled = state.dupSelected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                ) {
                    Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Lixeira do app (${state.dupSelected.size} · ${formatSize(state.dupSelectedSize)})")
                }
            }
        },
        snackbarHost = snackbar,
    ) { padding ->
        if (state.dupComputing) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LinearProgressIndicator(progress = { state.dupProgress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text("Comparando o conteúdo dos arquivos… ${(state.dupProgress * 100).toInt()}%")
                Text("Pode demorar alguns minutos com muitos vídeos.", style = MaterialTheme.typography.bodySmall)
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Text(
                    "Em cada grupo, o arquivo com selo MANTER (o original mais provável) fica; as cópias já vêm marcadas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (groups.isNullOrEmpty()) item { Text("Nenhum arquivo duplicado ✔", Modifier.padding(32.dp)) }
            itemsIndexed(groups.orEmpty(), key = { _, g -> g.first().path }) { i, g ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(
                        "Grupo ${i + 1} · ${g.size} cópias de ${formatSize(g.first().size)}",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 12.dp, top = 10.dp),
                    )
                    g.forEachIndexed { j, f ->
                        FileRow(
                            f,
                            selected = f.path in state.dupSelected,
                            onToggle = { vm.toggleDup(f.path) },
                            onOpen = { openFile(ctx, f.path) },
                            badge = if (j == 0) "MANTER" else null,
                        )
                    }
                }
            }
        }
    }
}

// ======================= Lixeira do app =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashScreen(vm: FilesViewModel, state: FilesUi, snackbar: @Composable () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
                title = {
                    Column {
                        Text("Lixeira do app")
                        Text("${state.trash.size} arquivos · ${formatSize(state.trashSize)}", style = MaterialTheme.typography.bodySmall)
                    }
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
                        onClick = vm::restoreSelected,
                        enabled = state.trashSelected.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Restaurar (${state.trashSelected.size})") }
                    Button(
                        onClick = { confirm = true },
                        enabled = state.trash.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Esvaziar") }
                }
            }
        },
        snackbarHost = snackbar,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "Marque arquivos para restaurar ao lugar de origem. Itens são apagados de vez após 30 dias.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(state.trash.sortedByDescending { it.deletedAt }, key = { it.trashName }) { t ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.toggleTrash(t.trashName) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${formatSize(t.size)} · apagado em ${fmtDate(t.deletedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Checkbox(checked = t.trashName in state.trashSelected, onCheckedChange = { vm.toggleTrash(t.trashName) })
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Esvaziar a lixeira?") },
            text = { Text("${state.trash.size} arquivos (${formatSize(state.trashSize)}) serão apagados de vez. Não dá para desfazer.") },
            confirmButton = { TextButton(onClick = { confirm = false; vm.emptyTrash() }) { Text("Apagar de vez") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
        )
    }
}

// ======================= Vídeos =======================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun VideosScreen(vm: FilesViewModel, state: FilesUi, snackbar: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refreshAccess(); vm.refreshVideoCounts()
    }
    val result = state.videoResult
    val visible = state.visibleVideos
    val selected = state.selectedVideos
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
                title = {
                    Column {
                        Text("Vídeos")
                        if (result != null) Text("${visible.size} encontrados · ${selected.size} marcados", style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    if (result != null && visible.isNotEmpty()) {
                        if (selected.size == visible.size) TextButton(onClick = vm::selectNoVideos) { Text("Nenhum") }
                        else TextButton(onClick = vm::selectAllVideos) { Text("Todos") }
                    }
                },
            )
        },
        bottomBar = {
            if (result != null && visible.isNotEmpty()) Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = vm::trashVideos,
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                ) {
                    Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Lixeira (${selected.size} · ${formatSize(selected.sumOf { it.size })})")
                }
            }
        },
        snackbarHost = snackbar,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.hasVideoPermission) {
                    Text("O app precisa de permissão para ver seus vídeos.")
                    Button(onClick = { permLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO) }) { Text("Permitir vídeos") }
                } else if (!state.modelsReady) {
                    Text("Baixe a IA na aba Fotos primeiro.")
                } else {
                    val missing = state.videoTotal - state.videoIndexed
                    Text(
                        "${state.videoIndexed} de ${state.videoTotal} vídeos analisados pela IA",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (missing > 0) OutlinedButton(onClick = vm::startVideoIndexing, modifier = Modifier.fillMaxWidth()) {
                        Text("Analisar $missing vídeos (segundo plano)")
                    }
                    OutlinedTextField(
                        value = state.videoQuery,
                        onValueChange = vm::setVideoQuery,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Descreva o vídeo") },
                        placeholder = { Text("ex.: show à noite em 2024") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.searchVideos() }),
                    )
                    Button(onClick = vm::searchVideos, enabled = !state.videoSearching, modifier = Modifier.fillMaxWidth()) {
                        if (state.videoSearching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else {
                            Icon(Icons.Default.Search, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Buscar vídeos")
                        }
                    }
                }
            }
            if (result != null) {
                if (result.english.isNotBlank()) {
                    Text(
                        "IA buscou: \"${result.english}\"" + (result.dateLabel?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    Slider(
                        value = state.videoThreshold,
                        onValueChange = vm::setVideoThreshold,
                        valueRange = 0.15f..0.35f,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("◀ mais resultados", style = MaterialTheme.typography.labelSmall)
                        Text("mais precisos ▶", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(
                    "Toque para marcar/desmarcar · segure para assistir",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    contentPadding = PaddingValues(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    gridItems(visible, key = { it.id }) { v ->
                        val sel = v.id !in state.videoExcluded
                        val primary = MaterialTheme.colorScheme.primary
                        Box(
                            Modifier.padding(2.dp).aspectRatio(1f).clip(RoundedCornerShape(6.dp))
                                .then(if (sel) Modifier.border(3.dp, primary, RoundedCornerShape(6.dp)) else Modifier)
                                .combinedClickable(
                                    onClick = { vm.toggleVideo(v.id) },
                                    onLongClick = {
                                        runCatching {
                                            ctx.startActivity(
                                                Intent(Intent.ACTION_VIEW).setDataAndType(v.uri, "video/*")
                                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            )
                                        }
                                    },
                                ),
                        ) {
                            AsyncImage(
                                model = v.uri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().alpha(if (sel) 1f else 0.35f),
                            )
                            Text(
                                "%d:%02d".format(v.durationMs / 60000, (v.durationMs / 1000) % 60) + " · " + formatSize(v.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp),
                            )
                            if (sel) Icon(
                                Icons.Default.CheckCircle, null, tint = primary,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).background(Color.White, CircleShape),
                            )
                        }
                    }
                }
            }
        }
    }
}
