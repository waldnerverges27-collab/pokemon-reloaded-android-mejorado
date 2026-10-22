package com.ahjin.pr.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CatchingPokemon
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Phishing
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Upload
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The picture the editor shows for each of the engine's button icons. The engine draws its own
 * (they are strokes, in `touch_style.rs`); these are the closest Material ones, so the editor can
 * show what a button will look like without the engine running.
 */
fun iconVector(name: String): ImageVector = when (name) {
    "act" -> Icons.Filled.PriorityHigh
    "run" -> Icons.Filled.DirectionsRun
    "hook" -> Icons.Filled.Phishing
    "bike" -> Icons.Filled.DirectionsBike
    "ball" -> Icons.Filled.CatchingPokemon
    "team" -> Icons.Filled.Groups
    "bag" -> Icons.Filled.Backpack
    "map" -> Icons.Filled.Map
    "star" -> Icons.Filled.Star
    "exit" -> Icons.AutoMirrored.Filled.ExitToApp
    "swap" -> Icons.Filled.SwapHoriz
    "bolt" -> Icons.Filled.Bolt
    "box" -> Icons.Filled.Inventory2
    "person" -> Icons.Filled.Person
    "magnifier" -> Icons.Filled.Search
    "chat" -> Icons.Filled.ChatBubble
    "smile" -> Icons.Filled.SentimentSatisfied
    "clock" -> Icons.Filled.Schedule
    "heart" -> Icons.Filled.Favorite
    "check" -> Icons.Filled.Check
    "plus" -> Icons.Filled.Add
    "menu" -> Icons.Filled.Menu
    "pause" -> Icons.Filled.Pause
    "back", "left" -> Icons.AutoMirrored.Filled.ArrowBack
    "save" -> Icons.Filled.Save
    "load" -> Icons.Filled.Upload
    "forward" -> Icons.Filled.FastForward
    "chart" -> Icons.Filled.BarChart
    "up" -> Icons.Filled.ArrowUpward
    "down" -> Icons.Filled.ArrowDownward
    "right" -> Icons.AutoMirrored.Filled.ArrowForward
    else -> Icons.Filled.Star
}
