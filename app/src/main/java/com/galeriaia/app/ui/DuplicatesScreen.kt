package com.galeriaia.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(state: UiState, vm: MainViewModel, snackbar: @Composable () -> Unit) {
    val dup = state.dup ?: return
    var preview by remember { mutableStateOf<Hit?>(null) }
    var slider by remember { mutableFloatStateOf(dup.threshold) }
    LaunchedEffect(dup.threshold) { slider = dup.threshold }
    val selectedCount = dup.selected.size

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                },
                title = {
                    Column {
                        Text(if (dup.computing) "Procurando…" else "${dup.groups.size} grupos")
                        if (!dup.computing) Text(
                            "$selectedCount marcadas para a Lixeira",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (!dup.computing) Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = vm::trashDuplicates,
                    enabled = selectedCount > 0,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                ) {
                    Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Mandar $selectedCount para a Lixeira")
                }
            }
        },
        snackbarHost = snackbar,
    ) { padding ->
        if (dup.computing) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Comparando suas fotos…")
                Text("Leva alguns segundos.", style = MaterialTheme.typography.bodySmall)
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text("Semelhança", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = slider,
                        onValueChange = { slider = it },
                        onValueChangeFinished = { vm.setDupThreshold(slider) },
                        valueRange = MIN_DUP_THRESHOLD..0.995f,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("◀ parecidas", style = MaterialTheme.typography.labelSmall)
                        Text("idênticas ▶", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Em cada grupo, a foto com selo MANTER (maior resolução) fica; as marcadas em vermelho vão para a Lixeira. " +
                            "Toque para marcar/desmarcar · segure para ver grande.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (dup.partialIndex) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Galeria ainda não toda indexada: resultados parciais.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (dup.groups.isEmpty()) item {
                Text(
                    "Nenhuma foto repetida com essa semelhança. Arraste a barra para a esquerda para incluir fotos parecidas.",
                    modifier = Modifier.padding(32.dp),
                )
            }
            itemsIndexed(dup.groups, key = { _, g -> g.first().id }) { i, group ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(
                        "Grupo ${i + 1} · ${group.size} fotos",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 12.dp, top = 10.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(group, key = { it.id }) { hit ->
                            Box(Modifier.size(116.dp)) {
                                Thumb(
                                    hit = hit,
                                    selected = hit.id in dup.selected,
                                    onClick = { vm.toggleDup(hit.id) },
                                    onLongClick = { preview = hit },
                                    selectedColor = MaterialTheme.colorScheme.error, // vermelho = vai para a Lixeira
                                    dimUnselected = false,
                                )
                                if (hit.id in dup.keep) {
                                    Text(
                                        "MANTER",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(6.dp)
                                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    preview?.let { hit ->
        PreviewDialog(hit, selected = hit.id in dup.selected, onToggle = { vm.toggleDup(hit.id) }) { preview = null }
    }
}
