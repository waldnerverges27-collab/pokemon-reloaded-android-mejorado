package com.ahjin.pr.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ahjin.pr.GameRepository
import com.ahjin.pr.Preferences
import com.ahjin.pr.SaveBackups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** What a copy was made for, in the player's words. */
fun backupReason(reason: String): String = when (reason) {
    SaveBackups.Reason.MANUAL -> "Hecha por ti"
    SaveBackups.Reason.PLAY -> "Al abrir el juego"
    SaveBackups.Reason.BEFORE_IMPORT -> "Antes de importar partidas"
    SaveBackups.Reason.BEFORE_DELETE -> "Antes de borrar"
    SaveBackups.Reason.BEFORE_INSTALL -> "Antes de reinstalar el juego"
    SaveBackups.Reason.BEFORE_RESTORE -> "Antes de restaurar otra copia"
    SaveBackups.Reason.BEFORE_UPDATE -> "Antes de actualizar la app"
    else -> "Copia"
}

private fun describe(backup: SaveBackups.Backup): String {
    val saves = when (backup.saves) {
        0 -> "sin partidas del juego"
        1 -> "1 partida"
        else -> "${backup.saves} partidas"
    }
    return "${backupReason(backup.reason)} · $saves · ${formatBytes(backup.sizeBytes)}"
}

/**
 * The safety copies of the saves: make one, share one, put one back or throw one away.
 *
 * Restoring is never silent. It asks first, says what it replaces, and the saves that were there
 * are copied before anything is touched, so a restore can be undone from this same list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupsScreen(repo: GameRepository, prefs: Preferences, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var auto by remember { mutableStateOf(prefs.autoBackup) }
    var copies by remember { mutableStateOf<List<SaveBackups.Backup>?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<SaveBackups.Backup?>(null) }
    var askRestore by remember { mutableStateOf<SaveBackups.Backup?>(null) }
    var askDelete by remember { mutableStateOf<SaveBackups.Backup?>(null) }
    val installed = repo.status() is GameRepository.Status.Ready

    LaunchedEffect(revision) {
        copies = withContext(Dispatchers.IO) { repo.backups.list() }
    }

    fun say(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun createNow() {
        if (busy) return
        busy = true
        scope.launch {
            val result = repo.backup(SaveBackups.Reason.MANUAL, force = true)
            busy = false
            result.fold(
                onSuccess = { say(if (it == null) "No hay partidas que copiar todavia." else "Copia hecha: ${it.saves} partidas.") },
                onFailure = { say("No pude hacer la copia: ${it.message}") },
            )
            revision++
        }
    }

    fun share(backup: SaveBackups.Backup) {
        scope.launch {
            val sent = runCatching {
                withContext(Dispatchers.IO) {
                    val out = File(File(context.cacheDir, "copias").apply { mkdirs() }, "partidas-${backup.file.nameWithoutExtension.removePrefix("copia-")}.zip")
                    backup.file.copyTo(out, overwrite = true)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", out)
                    Intent(Intent.ACTION_SEND)
                        .setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            sent.fold(
                onSuccess = { context.startActivity(Intent.createChooser(it, "Compartir copia")) },
                onFailure = { say("No pude preparar la copia para compartir: ${it.message}") },
            )
        }
    }

    selected?.let { backup ->
        AlertDialog(
            onDismissRequest = { selected = null },
            icon = { Icon(Icons.Filled.Archive, contentDescription = null) },
            title = { Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(backup.createdAt))) },
            text = {
                Column {
                    Text(describe(backup), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { askRestore = backup; selected = null },
                        enabled = installed,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Restore, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        Text(if (installed) "Restaurar esta copia" else "Restaurar (instala el juego antes)", modifier = Modifier.padding(start = 0.dp))
                    }
                    TextButton(onClick = { share(backup); selected = null }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        Text("Compartir o guardar fuera", modifier = Modifier.padding(start = 0.dp))
                    }
                    TextButton(onClick = { askDelete = backup; selected = null }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        Text("Borrar esta copia", modifier = Modifier.padding(start = 0.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Cerrar") } },
        )
    }

    askRestore?.let { backup ->
        AlertDialog(
            onDismissRequest = { askRestore = null },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("Restaurar la copia") },
            text = {
                Text(
                    "Tus partidas actuales se sustituyen por las de esta copia (${describe(backup)}). " +
                        "Antes de hacerlo guardo una copia de lo que tienes ahora, asi que puedes deshacerlo desde esta misma lista.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askRestore = null
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { repo.backups.restore(backup) }
                        busy = false
                        result.fold(
                            onSuccess = { say("Copia restaurada: $it partidas.") },
                            onFailure = { say(it.message ?: "No pude restaurar la copia.") },
                        )
                        revision++
                    }
                }) { Text("Restaurar") }
            },
            dismissButton = { TextButton(onClick = { askRestore = null }) { Text("Cancelar") } },
        )
    }

    askDelete?.let { backup ->
        AlertDialog(
            onDismissRequest = { askDelete = null },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text("Borrar la copia") },
            text = { Text("Se borra solo esta copia. Tus partidas del juego no se tocan.") },
            confirmButton = {
                TextButton(onClick = {
                    askDelete = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { repo.backups.delete(backup) }
                        say(if (ok) "Copia borrada." else "No pude borrar la copia.")
                        revision++
                    }
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { askDelete = null }) { Text("Cancelar") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Copias de seguridad") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SettingsGroup("Automaticas") {
                SwitchRow(
                    icon = Icons.Filled.History,
                    title = "Copia al abrir el juego",
                    subtitle = "Si tus partidas han cambiado desde la ultima copia, guarda otra antes de jugar. Se conservan las 5 ultimas.",
                    checked = auto,
                    items = 2,
                    index = 0,
                    onCheckedChange = {
                        auto = it
                        prefs.autoBackup = it
                    },
                )
                InfoRow(
                    icon = Icons.Filled.Info,
                    title = "Ademas, siempre",
                    value = "Antes de importar partidas, borrar una partida o el juego, reinstalarlo o actualizar la app se hace una copia, " +
                        "aunque esta opcion este apagada. Se guardan en la app, aparte del juego: borrar o reinstalar el juego no las toca.",
                    items = 2,
                    index = 1,
                )
            }

            SettingsGroup("Ahora") {
                NavRow(
                    icon = Icons.Filled.Backup,
                    title = if (busy) "Trabajando..." else "Hacer una copia ahora",
                    subtitle = if (installed) "Guarda tus partidas, equipos y personajes tal como estan" else "El juego no esta instalado",
                    items = 1,
                    index = 0,
                    enabled = installed && !busy,
                    onClick = { createNow() },
                )
            }

            val list = copies
            SettingsGroup(
                if (list == null || list.isEmpty()) "Copias guardadas"
                else "Copias guardadas (${list.size}, ${formatBytes(list.sumOf { it.sizeBytes })})",
            ) {
                when {
                    list == null -> InfoRow(Icons.Filled.History, "Cargando...", "", items = 1, index = 0)
                    list.isEmpty() -> InfoRow(
                        Icons.Filled.Archive,
                        "Todavia no hay copias",
                        "Aparecen aqui cuando juegas, o cuando pulsas Hacer una copia ahora.",
                        items = 1,
                        index = 0,
                    )
                    else -> list.forEachIndexed { i, backup ->
                        NavRow(
                            icon = if (backup.manual) Icons.Filled.Backup else Icons.Filled.History,
                            title = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(backup.createdAt)),
                            subtitle = describe(backup),
                            items = list.size,
                            index = i,
                            onClick = { selected = backup },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
