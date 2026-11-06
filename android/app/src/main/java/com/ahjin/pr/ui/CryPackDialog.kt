package com.ahjin.pr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ahjin.pr.ANIME_CRY_PACK
import com.ahjin.pr.CryPackStatus
import com.ahjin.pr.CryPacks

@Composable
fun CryPackDialog(
    status: CryPackStatus,
    work: CryPacks.Work,
    onInstall: () -> Unit,
    onApply: () -> Unit,
    onRestore: () -> Unit,
    onCancel: () -> Unit,
    onOpenPage: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (work) {
        is CryPacks.Work.Running -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(ANIME_CRY_PACK.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(work.stage)
                    LinearProgressIndicator(
                        progress = { (work.percent.coerceIn(0, 100) / 100f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${work.percent.coerceIn(0, 100)} %")
                    if (work.total > 0) {
                        Text("${if (work.written > 0) formatBytes(work.written) else "0 MB"} de ${formatBytes(work.total)}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "Puedes ocultar esta ventana; la tarea continua. No abras el juego hasta que termine.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = onCancel) { Text("Cancelar") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Ocultar") } },
        )
        is CryPacks.Work.Finished -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (work.ok) "Listo" else "No se pudo completar") },
            text = { Text(work.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        )
        CryPacks.Work.Idle -> {
            val title = when {
                status.active -> "Gritos del anime activados"
                status.needsReapply -> "El juego ha cambiado"
                else -> ANIME_CRY_PACK.title
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title) },
                text = {
                    Column(
                        Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        when {
                            !status.gameInstalled -> Text("Instala el juego antes de cambiar los gritos.")
                            status.active -> Text(
                                "El paquete esta aplicado. Se conservaron los gritos originales que no venian en el ZIP y tienes una copia completa para restaurarlos."
                            )
                            status.needsReapply && status.downloaded -> Text(
                                "Los gritos del anime estaban activados, pero el juego se reinstalo o actualizo. Puedes reaplicar el paquete sin descargarlo, o mantener los gritos originales de esta instalacion."
                            )
                            status.needsReapply -> Text(
                                "Los gritos del anime estaban activados, pero el paquete guardado ya no esta disponible. Descargalo otra vez o manten los gritos originales de esta instalacion."
                            )
                            status.downloaded -> Text(
                                "El paquete ya esta descargado y verificado (${formatBytes(status.packBytes)}). Puedes aplicarlo sin usar datos."
                            )
                            else -> Text(
                                "Descarga opcional de ${formatBytes(ANIME_CRY_PACK.archiveBytes)}; se recomienda Wi-Fi. Contiene ${ANIME_CRY_PACK.wavCount} WAV de terceros, no oficiales."
                            )
                        }
                        if (!status.active) {
                            Text(
                                "La instalacion comprueba tamano, SHA-256, rutas, CRC y WAV; guarda los originales y fusiona el paquete para no borrar los gritos que faltan en el. No cambia tus partidas.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            "Aplica o restaura con el juego cerrado y reinicialo despues. El paquete se conserva fuera del juego para poder reaplicarlo.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onOpenPage) { Text("Ver procedencia, hash y release publica") }
                    }
                },
                confirmButton = {
                    when {
                        !status.gameInstalled -> TextButton(onClick = {}, enabled = false) { Text("Sin juego") }
                        status.active -> TextButton(onClick = onRestore) { Text("Restaurar originales") }
                        status.needsReapply && status.downloaded -> TextButton(onClick = onApply) { Text("Reaplicar") }
                        status.downloaded -> TextButton(onClick = onApply) { Text("Aplicar") }
                        else -> TextButton(onClick = onInstall) { Text("Descargar y aplicar") }
                    }
                },
                dismissButton = {
                    if (status.needsReapply) {
                        TextButton(onClick = onRestore) { Text("Mantener originales") }
                    } else {
                        TextButton(onClick = onDismiss) { Text("Cerrar") }
                    }
                },
            )
        }
    }
}
