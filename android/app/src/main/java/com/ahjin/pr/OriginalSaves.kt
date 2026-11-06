package com.ahjin.pr

import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * The modification time of a real original save, or null when only descriptive files are present.
 * A stray `.nfo` or `.data` must not make Inicio offer a partida that the game cannot load.
 */
fun originalSaveTimestamp(folder: File, index: Int): Long? =
    File(folder, "reloaded$index.savc").takeIf { it.isFile }
        ?.lastModified()
        ?.takeIf { it > 0L }

/** The most recently written original save. Metadata-only and empty slots are not continuable. */
fun latestOriginalSave(slots: List<GameRepository.SaveSlot>): GameRepository.SaveSlot? =
    slots.filter { it.savedAt != null }.maxWithOrNull(
        compareBy<GameRepository.SaveSlot> { it.savedAt ?: Long.MIN_VALUE }.thenBy { it.index },
    )

/** A local, human-readable date for the save summary in Inicio. */
fun originalSaveDate(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
