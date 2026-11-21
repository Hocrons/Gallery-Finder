package com.galeriaia.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

fun openAppSettings(ctx: Context) {
    ctx.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onPermission(currentPermission(ctx))
    }

    // Recheca permissão e fotos novas sempre que o app volta para a tela
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) vm.onPermission(currentPermission(ctx))
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // Mantém a tela ligada enquanto indexa (se a tela apagar, o Android pode pausar o app)
    val indexing = state.indexProgress != null
    DisposableEffect(indexing) {
        val window = (ctx as? Activity)?.window
        if (indexing) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
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

    BackHandler(enabled = state.screen == Screen.RESULTS) { vm.back() }

    Box(Modifier.fillMaxSize()) {
        when (state.screen) {
            Screen.HOME -> HomeScreen(
                state = state,
                vm = vm,
                snackbar = { SnackbarHost(snackbar) },
                onRequestPermission = { permLauncher.launch(permissionsToRequest()) },
                onOpenSettings = { openAppSettings(ctx) },
            )
            Screen.RESULTS -> ResultsScreen(state = state, vm = vm, snackbar = { SnackbarHost(snackbar) })
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
