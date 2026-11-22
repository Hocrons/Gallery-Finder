package com.galeriaia.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneOffset

private val EXAMPLES = listOf(
    "prints de conversa",
    "cachorro na praia",
    "comida em 2024",
    "memes",
    "documentos e papéis",
    "fotos do mês passado",
    "pôr do sol",
    "selfies de 2022 a 2023",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    vm: MainViewModel,
    snackbar: @Composable () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartIndexing: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Galeria IA") }) },
        snackbarHost = snackbar,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                state.permission == PermissionState.DENIED ->
                    PermissionCard(onRequestPermission, onOpenSettings)

                state.modelsReady == false -> SetupCard(state.download, vm::downloadModels)

                state.modelsReady == null || state.permission == PermissionState.UNKNOWN ->
                    Box16 { CircularProgressIndicator() }

                else -> {
                    state.update?.let { UpdateCard(it, vm::downloadUpdate) }
                    if (state.permission == PermissionState.PARTIAL) PartialAccessCard(onOpenSettings)
                    IndexCard(state, vm, onStartIndexing)
                    SearchArea(state, vm)
                    DuplicatesCard(state, vm)
                }
            }
            if (state.permission != PermissionState.UNKNOWN) VersionFooter(vm)
        }
    }
}

@Composable
private fun Box16(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { content() }
}

@Composable
private fun PermissionCard(onRequest: () -> Unit, onSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Acesso às fotos", style = MaterialTheme.typography.titleMedium)
            Text(
                "O app precisa ler sua galeria para encontrar as fotos. Tudo é analisado no próprio " +
                    "celular; nenhuma foto é enviada para a internet."
            )
            Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) { Text("Permitir acesso") }
            TextButton(onClick = onSettings) { Text("Já neguei antes? Abrir configurações") }
        }
    }
}

@Composable
private fun PartialAccessCard(onSettings: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Acesso só a algumas fotos. Para buscar na galeria toda, escolha \"Permitir tudo\".")
                TextButton(onClick = onSettings) { Text("Abrir configurações") }
            }
        }
    }
}

@Composable
private fun SetupCard(download: DownloadState?, onDownload: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Baixar a IA (só uma vez)", style = MaterialTheme.typography.titleMedium)
            Text(
                "O modelo CLIP e o tradutor português→inglês ocupam cerca de " +
                    "${com.galeriaia.app.ml.ModelFiles.totalBytes / 1_000_000 + 30} MB. Use Wi-Fi. " +
                    "Depois disso o app funciona offline."
            )
            when {
                download == null -> Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Baixar agora") }
                download.error != null -> {
                    Text("Erro: ${download.error}", color = MaterialTheme.colorScheme.error)
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Tentar de novo") }
                }
                else -> {
                    val p = (download.done.toFloat() / download.total).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${download.done / 1_000_000} de ${download.total / 1_000_000} MB · ${download.file}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateCard(update: UpdateUi, onUpdate: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nova versão disponível (build ${update.release.build})", style = MaterialTheme.typography.titleMedium)
            if (update.release.notes.isNotBlank()) {
                Text(update.release.notes.take(400), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Suas fotos analisadas e o modelo de IA são mantidos.",
                style = MaterialTheme.typography.bodySmall,
            )
            update.error?.let { Text("Erro: $it", color = MaterialTheme.colorScheme.error) }
            val p = update.progress
            if (p != null) {
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                Text("Baixando… ${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            } else {
                Button(onClick = onUpdate, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when {
                            update.readyFile != null -> "Instalar"
                            update.release.sizeBytes > 0 -> "Atualizar (${update.release.sizeBytes / 1_000_000} MB)"
                            else -> "Atualizar"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DuplicatesCard(state: UiState, vm: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fotos repetidas e parecidas", style = MaterialTheme.typography.titleMedium)
            Text(
                "Agrupa fotos idênticas ou quase iguais (rajadas, prints repetidos, fotos salvas duas vezes) " +
                    "e já sugere qual manter em cada grupo.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = vm::findDuplicates,
                enabled = state.indexedImages > 0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Procurar repetidas") }
            if (state.dup != null && !state.dup.computing) {
                TextButton(onClick = vm::openDuplicatesAgain) { Text("Ver resultado anterior") }
            }
        }
    }
}

@Composable
private fun VersionFooter(vm: MainViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Versão: build ${com.galeriaia.app.update.Updater.currentBuild}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { vm.checkUpdate(manual = true) }) { Text("Verificar atualização") }
    }
}

@Composable
private fun IndexCard(state: UiState, vm: MainViewModel, onStartIndexing: () -> Unit) {
    val total = state.totalImages
    val indexed = state.indexedImages.coerceAtMost(total)
    val missing = total - indexed
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Índice da galeria", style = MaterialTheme.typography.titleMedium)
            Text("$indexed de $total fotos analisadas pela IA")
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else indexed.toFloat() / total },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Modo rápido", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (state.fastMode) "Usa mais o processador e não pausa por temperatura. O celular esquenta mais."
                        else "Desligado: mais lento, mas pausa sozinho se esquentar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = state.fastMode, onCheckedChange = vm::setFastMode)
            }
            val prog = state.indexProgress
            when {
                prog != null -> {
                    val elapsed = System.currentTimeMillis() - prog.startedAt
                    val eta = if (prog.done > 0) elapsed / prog.done * (prog.total - prog.done) / 1000 else -1
                    if (prog.priorityLabel != null) Text(
                        "Analisando primeiro: ${prog.priorityLabel}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (indexed > 0) Text(
                        "Já dá para buscar! ${vm.coverageText(state)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        when {
                            prog.total == 0 -> "Iniciando…"
                            prog.coolingTemp != null -> "Pausado para esfriar (%.0f °C) · ${prog.done}/${prog.total}".format(prog.coolingTemp)
                            else -> (if (prog.videos) "Fotos prontas ✔ Analisando vídeos " else "Analisando ") +
                                "${prog.done}/${prog.total}" + if (eta >= 0) " · ~${formatEta(eta)} restantes" else ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Roda em segundo plano: pode usar o celular ou apagar a tela." +
                            if (state.fastMode) "" else " Se a bateria esquentar, ele pausa sozinho até esfriar.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = vm::cancelIndexing) { Text("Pausar") }
                }
                missing > 0 -> {
                    Button(onClick = onStartIndexing, modifier = Modifier.fillMaxWidth()) {
                        Text(if (indexed == 0) "Indexar galeria ($total fotos)" else "Indexar $missing fotos novas")
                    }
                    Text(
                        "Roda em segundo plano. Fotos novas também são analisadas sozinhas quando o celular está carregando.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                else -> Text("Tudo pronto ✔", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60} min"
    else -> "${seconds / 3600}h${"%02d".format((seconds % 3600) / 60)}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchArea(state: UiState, vm: MainViewModel) {
    var showPicker by remember { mutableStateOf(false) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setExample(uri)
    }
    val negativePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.addNegative(uri)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::setQuery,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Descreva as fotos") },
            placeholder = { Text("ex.: prints de conversa de 2024") },
            minLines = 2,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search() }),
            trailingIcon = {
                if (state.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) {
                    Icon(Icons.Default.Close, "Limpar")
                }
            },
        )

        // Foto de exemplo
        if (state.exampleUri != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = state.exampleUri,
                    contentDescription = "Foto de exemplo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Buscar fotos parecidas com esta", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "A descrição acima é usada só para datas (ex.: \"em 2024\").",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.setExample(null) }) { Icon(Icons.Default.Close, "Remover foto") }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.exampleUri == null) item {
                AssistChip(
                    onClick = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    label = { Text("Usar foto de exemplo") },
                    leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) },
                )
            }
            if (state.negatives.size < 5) item {
                AssistChip(
                    onClick = {
                        negativePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    label = { Text("Foto do que NÃO quero") },
                    leadingIcon = { Icon(Icons.Default.Close, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                )
            }
        }

        // Fotos do que não quero
        if (state.negatives.isNotEmpty()) {
            Text(
                "A IA vai descartar fotos parecidas com estas:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.negatives) { n ->
                    Box(Modifier.size(64.dp)) {
                        AsyncImage(
                            model = n.uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                                .border(2.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(10.dp)),
                        )
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remover",
                            tint = Color.White,
                            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                .clickable { vm.removeNegative(n) },
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            AssistChip(
                onClick = { showPicker = true },
                label = { Text(state.manualRange?.let { "Período: ${it.label}" } ?: "Escolher período (opcional)") },
                leadingIcon = { Icon(Icons.Default.DateRange, null, Modifier.size(18.dp)) },
            )
            if (state.manualRange != null) IconButton(onClick = { vm.setManualRange(null, null) }) {
                Icon(Icons.Default.Close, "Remover período")
            }
        }

        Button(
            onClick = vm::search,
            enabled = !state.searching,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (state.searching) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else {
                Icon(Icons.Default.Search, null)
                Spacer(Modifier.width(8.dp))
                Text("Buscar")
            }
        }

        Text("Exemplos", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
            items(EXAMPLES) { ex ->
                SuggestionChip(onClick = { vm.setQuery(ex) }, label = { Text(ex) })
            }
        }
        Text(
            "Dica: você pode escrever datas na própria descrição (\"em março de 2025\", \"ano passado\", " +
                "\"últimos 30 dias\") ou usar o botão de período.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showPicker) {
        val pickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val toDate = { ms: Long -> Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate() }
                    val s = pickerState.selectedStartDateMillis?.let(toDate)
                    val e = pickerState.selectedEndDateMillis?.let(toDate)
                    vm.setManualRange(s, e)
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancelar") } },
        ) {
            DateRangePicker(state = pickerState, modifier = Modifier.weight(1f))
        }
    }
}
