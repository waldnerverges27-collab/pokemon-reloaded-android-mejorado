package com.ahjin.pr.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ahjin.pr.GameActivity
import com.ahjin.pr.GameRepository
import com.ahjin.pr.ZipIntake
import com.ahjin.pr.ZipIntake.Kind
import com.ahjin.pr.ZipIntake.Work
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the dialog is waiting on before the import itself starts. */
private sealed interface Step {
    data object Checking : Step
    data class Ask(val kind: Kind, val installed: GameRepository.Status.Ready?) : Step
    data class Failed(val message: String) : Step
}

/**
 * The dialog shown when a zip is opened from a file manager. It looks inside the zip, says what it
 * found, and asks before touching anything: nothing is replaced until the player confirms.
 *
 * The import itself runs in [ZipIntake], so this only draws it. If the screen is recreated halfway
 * (the phone is turned), the dialog comes back showing the progress of the import still running.
 */
@Composable
fun IncomingZipDialog(uri: Uri, repo: GameRepository, onClose: () -> Unit) {
    val context = LocalContext.current
    val work by ZipIntake.work.collectAsState()
    val doc = remember(uri) { ZipIntake.describe(context, uri) }
    var step by remember(uri) { mutableStateOf<Step>(Step.Checking) }

    LaunchedEffect(uri) {
        if (ZipIntake.work.value !is Work.Idle) return@LaunchedEffect
        step = ZipIntake.classify(context, uri).fold(
            onSuccess = { kind ->
                val status = withContext(Dispatchers.IO) { repo.status() }
                Step.Ask(kind, status as? GameRepository.Status.Ready)
            },
            onFailure = { Step.Failed(it.message ?: "No pude leer ese archivo.") },
        )
    }

    val title = doc.name ?: "Archivo zip"
    val size = if (doc.size > 0) " (${formatBytes(doc.size)})" else ""

    when (val w = work) {
        is Work.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text(w.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("No cierres la app hasta que termine.")
                    if (w.percent > 0) {
                        LinearProgressIndicator(progress = { w.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("${w.percent}%")
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                }
            },
            confirmButton = {},
        )
        is Work.Finished -> AlertDialog(
            onDismissRequest = {
                ZipIntake.dismiss()
                onClose()
            },
            title = { Text(if (w.ok) "Listo" else "No se pudo importar") },
            text = { Text(w.message) },
            confirmButton = {
                TextButton(onClick = {
                    ZipIntake.dismiss()
                    onClose()
                }) { Text("Aceptar") }
            },
        )
        Work.Idle -> when (val s = step) {
            Step.Checking -> AlertDialog(
                onDismissRequest = onClose,
                title = { Text("Abriendo el zip") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(title + size)
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                },
                confirmButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
            )
            is Step.Failed -> Notice("No puedo abrir ese zip", s.message, onClose)
            is Step.Ask -> Ask(s, title + size, repo, uri, onClose)
        }
    }
}

@Composable
private fun Notice(title: String, message: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onClose) { Text("Aceptar") } },
    )
}

@Composable
private fun Ask(step: Step.Ask, label: String, repo: GameRepository, uri: Uri, onClose: () -> Unit) {
    val installed = step.installed
    when (val kind = step.kind) {
        Kind.Unknown -> Notice(
            "No es ni el juego ni partidas",
            "$label no trae el juego (el .exe y la carpeta data) ni partidas, equipos o personajes. No lo he importado.",
            onClose,
        )
        Kind.Game -> {
            if (GameActivity.isRunning) {
                Notice("El juego esta abierto", "Cierralo del todo y vuelve a abrir el zip. Reemplazar el juego con el abierto lo corromperia.", onClose)
                return
            }
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text(if (installed == null) "Instalar el juego" else "Reemplazar el juego") },
                text = {
                    Text(
                        "$label parece ser Pokemon Reloaded." +
                            if (installed == null) {
                                "\n\nSe descomprime en la app; tarda un rato."
                            } else {
                                "\n\nYa hay un juego instalado${installed.version?.let { " (version $it)" } ?: ""}. " +
                                    "Se reemplaza, y con el sus partidas. Antes se guarda una copia en Ajustes > Copias de seguridad."
                            },
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        ZipIntake.start("Instalando el juego") { progress -> repo.import(uri, progress).map { "Juego instalado." } }
                    }) { Text(if (installed == null) "Instalar" else "Reemplazar") }
                },
                dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
            )
        }
        Kind.Saves -> {
            if (installed == null) {
                Notice("Primero instala el juego", "$label trae partidas, pero todavia no hay juego instalado donde ponerlas.", onClose)
                return
            }
            if (GameActivity.isRunning) {
                Notice("El juego esta abierto", "Cierralo del todo y vuelve a abrir el zip.", onClose)
                return
            }
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("Restaurar partidas") },
                text = {
                    Text(
                        "$label trae partidas, equipos y personajes. Se ponen en el juego y reemplazan los que tengan el mismo nombre. " +
                            "Antes se guarda una copia de lo que tienes ahora.",
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        ZipIntake.start("Restaurando partidas") { _ ->
                            repo.importSaves(uri).map { n ->
                                when (n) {
                                    0 -> "Se restauraron los equipos y personajes del zip."
                                    1 -> "Se restauro 1 partida."
                                    else -> "Se restauraron $n partidas."
                                }
                            }
                        }
                    }) { Text("Restaurar") }
                },
                dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
            )
        }
        is Kind.Slot -> {
            if (installed == null) {
                Notice("Primero instala el juego", "$label trae una partida, pero todavia no hay juego instalado donde ponerla.", onClose)
                return
            }
            if (GameActivity.isRunning) {
                Notice("El juego esta abierto", "Cierralo del todo y vuelve a abrir el zip.", onClose)
                return
            }
            val slots = GameRepository.Slots.COUNT
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("Importar una partida") },
                text = {
                    Column {
                        Text("$label es una sola partida (era del Slot ${kind.number}). Elige donde ponerla; si el slot ya tiene una, se reemplaza y antes se guarda una copia.")
                        Spacer(Modifier.height(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (slot in 1..slots) {
                                val button: @Composable () -> Unit = {
                                    Text(if (slot == kind.number) "Slot $slot (el original)" else "Slot $slot")
                                }
                                val go = {
                                    ZipIntake.start("Importando la partida") { _ ->
                                        repo.importSlot(uri, slot).map { "Partida importada en el Slot $slot." }
                                    }
                                }
                                if (slot == kind.number) {
                                    Button(onClick = go, modifier = Modifier.fillMaxWidth()) { button() }
                                } else {
                                    FilledTonalButton(onClick = go, modifier = Modifier.fillMaxWidth()) { button() }
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
            )
        }
    }
}
