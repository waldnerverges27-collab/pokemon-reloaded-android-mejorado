package com.ahjin.pr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ahjin.pr.Update

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateDialog(
    release: Update.Release,
    busy: Boolean,
    verifying: Boolean,
    written: Long,
    total: Long,
    resumable: Boolean,
    readyToInstall: Boolean,
    problem: String?,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit,
    onOpenReleases: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null) },
        title = {
            Text(
                when {
                    busy && verifying -> "Comprobando actualizacion"
                    busy -> "Descargando actualizacion"
                    readyToInstall -> "Actualizacion descargada"
                    else -> "Hay una version nueva"
                },
            )
        },
        text = {
            Column {
                if (!busy) {
                    Text("Pokemon Reloaded ${release.version} ya esta publicado.")
                    if (release.notes.isNotEmpty()) {
                        Text(
                            text = release.notes.take(700),
                            modifier = Modifier.padding(top = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 10,
                        )
                    }
                    if (resumable) {
                        Text(
                            "Hay ${inMegabytes(written)} descargados. Continuara desde ese punto, incluso despues de cerrar la app.",
                            modifier = Modifier.padding(top = 12.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (readyToInstall) {
                        Text(
                            "El APK esta guardado. Se volvera a comprobar antes de instalar, sin descargarlo otra vez.",
                            modifier = Modifier.padding(top = 12.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (!readyToInstall && !resumable) {
                        Text(
                            "Solo se descarga el APK. Tus partidas no se tocan y se hace una copia antes de abrir el instalador.",
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else if (verifying) {
                    Text("Comprobando tamano, SHA-256, paquete, version y certificado.")
                } else {
                    val percent = if (total > 0) (written * 100 / total).toInt().coerceIn(0, 100) else 0
                    LinearWavyProgressIndicator(
                        progress = { if (total > 0) (written.toFloat() / total).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            if (total > 0) "${inMegabytes(written)} de ${inMegabytes(total)}"
                            else "${inMegabytes(written)} descargados",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("$percent%", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "Puedes girar la pantalla. Si cierras la app, al volver podras continuar.",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                problem?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(top = 12.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!busy) {
                    TextButton(onClick = onOpenReleases, modifier = Modifier.fillMaxWidth()) {
                        Text("No confio en el autoactualizador")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDownload, enabled = !busy) {
                Text(
                    when {
                        busy && verifying -> "Comprobando..."
                        busy -> "Descargando..."
                        readyToInstall -> "Instalar"
                        resumable -> "Continuar"
                        else -> "Descargar"
                    },
                )
            }
        },
        dismissButton = {
            if (busy) {
                TextButton(onClick = onCancelDownload, enabled = !verifying) {
                    Text("Cancelar descarga")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Ahora no") }
            }
        },
    )
}

private fun inMegabytes(bytes: Long) = "%.1f MB".format(bytes / 1024.0 / 1024.0)
