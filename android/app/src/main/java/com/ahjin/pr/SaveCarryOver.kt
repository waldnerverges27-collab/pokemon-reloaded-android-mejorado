package com.ahjin.pr

import java.io.File
import java.io.IOException

/**
 * What survives when the game is installed over another one.
 *
 * Installing a game zip used to replace the whole game folder, `data` included, and the saves lived in
 * it: they were only recoverable from the safety copy. Now the saves of the game being replaced are put
 * into the new folder *before* the swap, so the player's slots, teams and characters are simply still
 * there afterwards (the safety copy is made anyway).
 */
object SaveCarryOver {

    /**
     * Copies the files of [folders] from [oldData] into [newData]. A file the player already has wins
     * over one of the same name in the zip: it is their progress, and the zip's is only the author's
     * default. Files only the zip has (a new custom character, say) stay. Returns how many save slots
     * were carried over (a slot is a `.savc`; its backup, `_bk`, does not count as another).
     *
     * Throws if a copy is not complete, which makes the caller leave the old install alone.
     */
    fun merge(oldData: File, newData: File, folders: List<String>): Int {
        var slots = 0
        for (folder in folders) {
            val from = File(oldData, folder)
            if (!from.isDirectory) continue
            val to = File(newData, folder)
            to.mkdirs()
            for (file in from.walkTopDown().filter { it.isFile }) {
                val relative = file.relativeTo(from)
                val target = File(to, relative.path)
                target.parentFile?.mkdirs()
                file.copyTo(target, overwrite = true)
                if (target.length() != file.length()) throw IOException("No pude conservar $folder/${relative.path}.")
                if (folder == "partidas" && file.name.endsWith(".savc", ignoreCase = true) && !file.name.contains("_bk", ignoreCase = true)) {
                    slots++
                }
            }
        }
        return slots
    }
}
