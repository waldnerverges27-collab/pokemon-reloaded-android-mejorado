package com.ahjin.pr.ui

import android.os.Build
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ahjin.pr.Diagnostics
import com.ahjin.pr.GameActivity
import com.ahjin.pr.GameRepository
import com.ahjin.pr.InstallationDoctor
import com.ahjin.pr.Preferences
import com.ahjin.pr.ZipIntake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Guided installation check shared by the first-run setup and Settings > Help. */
@Composable
fun InstallationDoctorDialog(
    repo: GameRepository,
    prefs: Preferences,
    onDismiss: () -> Unit,
    onBackups: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val work by ZipIntake.work.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    var report by remember { mutableStateOf<InstallationDoctor.Report?>(null) }
    var repairing by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh) {
        report = runCatching {
            withContext(Dispatchers.IO) {
                val status = repo.status() as? GameRepository.Status.Ready
                InstallationDoctor.inspect(
                    filesDir = context.filesDir,
                    installed = status?.let { InstallationDoctor.Installed(it.exe, it.version) },
                    supportedAbis = Build.SUPPORTED_ABIS.toList(),
                    availableBytes = StatFs(context.filesDir.path).availableBytes,
                )
            }
        }.getOrElse { error ->
            InstallationDoctor.Report(
                listOf(
                    InstallationDoctor.Check(
                        InstallationDoctor.CheckId.GAME,
                        InstallationDoctor.Level.ERROR,
                        "No se pudo completar la revisión",
                        "${error.javaClass.simpleName}: ${error.message ?: "error desconocido"}. Comparte un informe de diagnóstico.",
                    ),
                ),
            )
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            ZipIntake.start("Instalando el juego") { progress ->
                repo.import(uri, progress).map { kept ->
                    when (kept) {
                        0 -> "Juego instalado. La comprobación se actualizará a continuación."
                        1 -> "Juego instalado y 1 partida conservada. La comprobación se actualizará a continuación."
                        else -> "Juego instalado y $kept partidas conservadas. La comprobación se actualizará a continuación."
                    }
                }
            }
        }
    }

    when (val current = work) {
        is ZipIntake.Work.Running -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(current.title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("No cierres la app. Tus partidas se conservan y se guarda una copia antes de reemplazar el juego.")
                        if (current.percent > 0) {
                            LinearProgressIndicator(progress = { current.percent / 100f }, modifier = Modifier.fillMaxWidth())
                            Text("${current.percent} %")
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 3.dp)
                        }
                    }
                },
                confirmButton = {},
            )
            return
        }
        is ZipIntake.Work.Finished -> {
            AlertDialog(
                onDismissRequest = {
                    ZipIntake.dismiss()
                    onDismiss()
                },
                title = { Text(if (current.ok) "Instalación terminada" else "No se pudo importar") },
                text = { Text(current.message) },
                confirmButton = {
                    TextButton(onClick = {
                        ZipIntake.dismiss()
                        refresh++
                    }) { Text("Ver comprobación") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        ZipIntake.dismiss()
                        onDismiss()
                    }) { Text("Cerrar") }
                },
            )
            return
        }
        ZipIntake.Work.Idle -> Unit
    }

    AlertDialog(
        onDismissRequest = { if (!repairing) onDismiss() },
        title = { Text("Asistente de instalación") },
        text = {
            val result = report
            if (result == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(16.dp))
                    Text("Comprobando la instalación…")
                }
            } else {
                Column(
                    modifier = Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        when {
                            result.errors == 1 -> "Encontré 1 problema importante y la acción recomendada para resolverlo."
                            result.errors > 1 -> "Encontré ${result.errors} problemas importantes y las acciones recomendadas."
                            result.warnings == 1 -> "La instalación puede continuar, pero hay 1 aviso que conviene revisar."
                            result.warnings > 1 -> "La instalación puede continuar, pero hay ${result.warnings} avisos que conviene revisar."
                            else -> "No encontré problemas en estas comprobaciones."
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    result.checks.forEach { DoctorCheck(it) }
                    if (GameActivity.isRunning && (result.needsImport || result.canRepair)) {
                        Text(
                            "Cierra el juego antes de reparar o reinstalar.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (result.needsImport) {
                        Button(
                            onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                            enabled = !repairing && !sharing && !GameActivity.isRunning,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Importar o reinstalar el ZIP")
                        }
                        Text(
                            "La reinstalación conserva las partidas y crea una copia de seguridad antes del cambio.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (result.canRepair) {
                        FilledTonalButton(
                            onClick = {
                                repairing = true
                                message = null
                                scope.launch {
                                    val repair = runCatching { withContext(Dispatchers.IO) { repo.prepare() } }
                                    repairing = false
                                    message = repair.fold(
                                        { "Reparación finalizada; revisa el resultado actualizado." },
                                        { "No se pudo reparar: ${it.message}" },
                                    )
                                    refresh++
                                }
                            },
                            enabled = !repairing && !sharing && !GameActivity.isRunning,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Build, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (repairing) "Reparando…" else "Reparar archivos auxiliares")
                        }
                    }
                    TextButton(
                        onClick = {
                            sharing = true
                            message = null
                            scope.launch {
                                val text = runCatching { withContext(Dispatchers.IO) { Diagnostics.report(context, repo, prefs) } }
                                text.onSuccess {
                                    runCatching { Diagnostics.share(context, it) }
                                        .onFailure { error -> message = "No se pudo compartir: ${error.message}" }
                                }.onFailure { message = "No se pudo preparar el informe: ${it.message}" }
                                sharing = false
                            }
                        },
                        enabled = !sharing && !repairing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (sharing) "Preparando informe…" else "Compartir diagnóstico completo")
                    }
                    if (onBackups != null) {
                        TextButton(onClick = onBackups, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Backup, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Abrir copias de seguridad")
                        }
                    }
                    message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !repairing) { Text("Cerrar") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    message = null
                    refresh++
                },
                enabled = report != null && !repairing && !sharing,
            ) { Text("Volver a comprobar") }
        },
    )
}

@Composable
private fun DoctorCheck(check: InstallationDoctor.Check) {
    val (icon, tint) = when (check.level) {
        InstallationDoctor.Level.OK -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
        InstallationDoctor.Level.WARNING -> Icons.Filled.Warning to MaterialTheme.colorScheme.tertiary
        InstallationDoctor.Level.ERROR -> Icons.Filled.Error to MaterialTheme.colorScheme.error
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(check.title, style = MaterialTheme.typography.titleSmall)
                Text(check.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
