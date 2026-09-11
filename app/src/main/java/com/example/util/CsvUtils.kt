package com.example.util

object CsvUtils {
    fun cell(value: String): String {
        // Escape RFC 4180 quotes and prevent spreadsheet formula execution / NIP rounding.
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') ||
            value.startsWith('\t') || value.startsWith('\r') || value.startsWith('\n') ||
            value.matches(Regex("[0-9]{15,}"))) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    fun row(values: List<String>) = values.joinToString(",") { cell(it) } + "\r\n"
}
