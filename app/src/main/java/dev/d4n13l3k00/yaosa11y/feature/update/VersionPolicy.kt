package dev.d4n13l3k00.yaosa11y.feature.update

object VersionPolicy {
    fun isNewer(candidate: String, installed: String): Boolean =
        compare(candidate, installed) > 0

    fun compare(left: String, right: String): Int {
        val leftVersion = parse(left)
        val rightVersion = parse(right)
        val maxSize = maxOf(leftVersion.numbers.size, rightVersion.numbers.size)
        repeat(maxSize) { index ->
            val comparison = leftVersion.numbers.getOrElse(index) { 0 }
                .compareTo(rightVersion.numbers.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return when {
            leftVersion.preRelease == null && rightVersion.preRelease != null -> 1
            leftVersion.preRelease != null && rightVersion.preRelease == null -> -1
            else -> comparePreRelease(leftVersion.preRelease, rightVersion.preRelease)
        }
    }

    private fun parse(raw: String): ParsedVersion {
        val normalized = raw.trim().removePrefix("v").substringBefore('+')
        val parts = normalized.split('-', limit = 2)
        val numbers = parts.first()
            .split('.')
            .map { component ->
                component.toIntOrNull()
                    ?: error("Некорректная версия: $raw")
            }
        check(numbers.isNotEmpty()) { "Некорректная версия: $raw" }
        return ParsedVersion(numbers, parts.getOrNull(1))
    }

    private fun comparePreRelease(left: String?, right: String?): Int {
        if (left == null && right == null) return 0
        val leftParts = left.orEmpty().split('.')
        val rightParts = right.orEmpty().split('.')
        repeat(maxOf(leftParts.size, rightParts.size)) { index ->
            val leftPart = leftParts.getOrNull(index) ?: return -1
            val rightPart = rightParts.getOrNull(index) ?: return 1
            val leftNumber = leftPart.toIntOrNull()
            val rightNumber = rightPart.toIntOrNull()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> leftPart.compareTo(rightPart)
            }
            if (comparison != 0) return comparison
        }
        return 0
    }

    private data class ParsedVersion(
        val numbers: List<Int>,
        val preRelease: String?,
    )
}
