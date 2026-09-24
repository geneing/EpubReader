package com.geneing.epubreader.data

enum class BookFormat(
    val label: String,
    val mimeType: String,
    private val extensions: Set<String>,
) {
    EPUB("EPUB", "application/epub+zip", setOf("epub")),
    PDF("PDF", "application/pdf", setOf("pdf"));

    companion object {
        fun from(name: String?, mimeType: String?): BookFormat? {
            val extension = name
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.lowercase()

            return entries.firstOrNull { format ->
                extension in format.extensions || mimeType.equals(format.mimeType, ignoreCase = true)
            }
        }
    }
}
