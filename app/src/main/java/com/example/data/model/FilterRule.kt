package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MatchType {
    EXACT,       // Exactly matches phone number e.g. "+14155552671" or "12345"
    PREFIX,      // Starts with country code or prefix e.g. "+44" or "91"
    CONTAINS,    // Contains substring e.g. "BANK" or "VERIFY"
    REGEX        // Custom regular expression pattern
}

@Entity(tableName = "filter_rules")
data class FilterRule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val senderPattern: String,
    val matchType: MatchType = MatchType.EXACT,
    val label: String = "",
    val keywordFilter: String = "", // Optional: only forward if SMS text also contains this keyword
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun matches(sender: String, messageBody: String): Boolean {
        if (!isEnabled) return false

        val cleanSender = sender.trim()
        val cleanPattern = senderPattern.trim()

        val senderMatched = when (matchType) {
            MatchType.EXACT -> {
                val normalizedSender = cleanSender.replace("[^0-9+]".toRegex(), "")
                val normalizedPattern = cleanPattern.replace("[^0-9+]".toRegex(), "")
                if (normalizedSender.isNotEmpty() && normalizedPattern.isNotEmpty()) {
                    normalizedSender.equals(normalizedPattern, ignoreCase = true) ||
                            cleanSender.equals(cleanPattern, ignoreCase = true)
                } else {
                    cleanSender.equals(cleanPattern, ignoreCase = true)
                }
            }
            MatchType.PREFIX -> {
                val normalizedSender = cleanSender.replace("[^0-9+]".toRegex(), "")
                val normalizedPattern = cleanPattern.replace("[^0-9+]".toRegex(), "")
                if (normalizedSender.isNotEmpty() && normalizedPattern.isNotEmpty()) {
                    normalizedSender.startsWith(normalizedPattern) || cleanSender.startsWith(cleanPattern, ignoreCase = true)
                } else {
                    cleanSender.startsWith(cleanPattern, ignoreCase = true)
                }
            }
            MatchType.CONTAINS -> {
                cleanSender.contains(cleanPattern, ignoreCase = true)
            }
            MatchType.REGEX -> {
                try {
                    Regex(cleanPattern, RegexOption.IGNORE_CASE).containsMatchIn(cleanSender)
                } catch (e: Exception) {
                    false
                }
            }
        }

        if (!senderMatched) return false

        if (keywordFilter.isNotBlank()) {
            val keywords = keywordFilter.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (keywords.isNotEmpty()) {
                val hasKeyword = keywords.any { messageBody.contains(it, ignoreCase = true) }
                if (!hasKeyword) return false
            }
        }

        return true
    }
}
