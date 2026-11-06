package com.ahjin.pr

import java.text.Normalizer
import java.util.Locale

/**
 * Accent/case-insensitive, order-independent text matching shared by Settings and the Guide.
 * Every word in the query must appear; punctuation acts as a word separator, so "f5/f6" works too.
 */
fun matchesSearch(query: String, vararg texts: String): Boolean {
    val tokens = tokens(query)
    if (tokens.isEmpty()) return true
    val haystack = normalize(texts.joinToString(" "))
    return tokens.all { haystack.contains(it) }
}

/**
 * Searches one titled document. Null means no match; a blank query keeps the whole document.
 * Exact paragraph matches keep just those paragraphs, while a title-only or cross-paragraph match
 * keeps the whole document so no necessary context is lost.
 */
fun searchTextDocument(query: String, title: String, paragraphs: List<String>): List<String>? {
    if (query.isBlank()) return paragraphs
    if (!matchesSearch(query, title, paragraphs.joinToString(" "))) return null
    return paragraphs.filter { matchesSearch(query, it) }.ifEmpty { paragraphs }
}

private fun tokens(value: String): List<String> {
    val normalized = normalize(value)
    return if (normalized.isEmpty()) emptyList() else normalized.split(Regex("\\s+")).distinct()
}

private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .lowercase(Locale.ROOT)
    .replace(Regex("\\p{M}+"), "")
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()
