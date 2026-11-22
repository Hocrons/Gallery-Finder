package com.galeriaia.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.galeriaia.app.ui.files.FilesRoot
import com.galeriaia.app.ui.files.FilesScreen
import com.galeriaia.app.ui.files.FilesViewModel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.galeriaia.app.update.Updater

fun currentPermission(ctx: Context): PermissionState {
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> PermissionState.GRANTED
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PermissionState.PARTIAL
        Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> PermissionState.GRANTED
        else -> PermissionState.DENIED
    }
}

fun permissionsToRequest(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.POST_NOTIFICATIONS,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

fun openAppSettings(ctx: Context) {
    ctx.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

enum class Tab { PHOTOS, FILES }

@Composable
fun App(vm: MainViewModel = viewModel(), filesVm: FilesViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val filesState by filesVm.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.PHOTOS) }
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onPermission(currentPermission(ctx))
    }
    // Pede para mostrar notificação (progresso da indexação) e então inicia
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.startIndexing()
    }
    val startIndexing = {
        val needsNotif = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotif) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.startIndexing()
    }

    // Recheca permissão e fotos novas sempre que o app volta para a tela
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                vm.onPermission(currentPermission(ctx))
                filesVm.refreshAccess()
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // Atualização baixada: abre o instalador (ou pede a liberação "instalar apps desconhecidos")
    LaunchedEffect(state.installRequest) {
        val apk = state.installRequest ?: return@LaunchedEffect
        if (Updater.canInstall(ctx)) {
            Updater.install(ctx, apk)
        } else {
            Updater.openInstallPermissionSettings(ctx)
            snackbar.showSnackbar("Ative \"Permitir desta fonte\", volte e toque em Instalar de novo.")
        }
        vm.installHandled()
    }

    // Confirmações do sistema (Lixeira / permissão para mover)
    val systemLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onSystemConfirmation(it.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(state.pending) {
        state.pending?.let { systemLauncher.launch(IntentSenderRequest.Builder(it.sender).build()) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    BackHandler(enabled = tab == Tab.PHOTOS && state.screen != Screen.HOME) { vm.back() }
    BackHandler(enabled = tab == Tab.FILES && filesState.screen != FilesScreen.HOME) { filesVm.back() }
    BackHandler(enabled = tab == Tab.FILES && filesState.screen == FilesScreen.HOME) { tab = Tab.PHOTOS }

    val showBar = (tab == Tab.PHOTOS && state.screen == Screen.HOME) ||
        (tab == Tab.FILES && filesState.screen == FilesScreen.HOME)

    Column(Modifier.fillMaxSize()) {
    Box(
        Modifier.weight(1f).fillMaxWidth()
            .then(if (showBar) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)
    ) {
        if (tab == Tab.FILES) FilesRoot(filesVm, filesState)
        else when (state.screen) {
            Screen.HOME -> HomeScreen(
                state = state,
                vm = vm,
                snackbar = { SnackbarHost(snackbar) },
                onRequestPermission = { permLauncher.launch(permissionsToRequest()) },
                onOpenSettings = { openAppSettings(ctx) },
                onStartIndexing = startIndexing,
            )
            Screen.RESULTS -> ResultsScreen(state = state, vm = vm, snackbar = { SnackbarHost(snackbar) })
            Screen.DUPLICATES -> DuplicatesScreen(state = state, vm = vm, snackbar = { SnackbarHost(snackbar) })
        }
    }
    if (showBar) {
        NavigationBar {
            NavigationBarItem(
                selected = tab == Tab.PHOTOS,
                onClick = { tab = Tab.PHOTOS },
                icon = { Icon(Icons.Default.Search, null) },
                label = { Text("Fotos") },
            )
            NavigationBarItem(
                selected = tab == Tab.FILES,
                onClick = { tab = Tab.FILES },
                icon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                label = { Text("Arquivos") },
            )
        }
    }
    }

    state.busy?.let { text ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            icon = { CircularProgressIndicator() },
            text = { Text(text) },
        )
    }
}
