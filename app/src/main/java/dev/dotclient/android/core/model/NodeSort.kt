package dev.dotclient.android.core.model

enum class NodeSortMode {
    ORIGIN,
    DELAY,
    NAME,
}

object NodeSorter {
    fun sort(
        profiles: List<VlessProfile>,
        mode: NodeSortMode,
        latenciesMs: Map<String, Long>,
        failedIds: Set<String> = emptySet(),
    ): List<VlessProfile> = when (mode) {
        NodeSortMode.ORIGIN -> profiles
        NodeSortMode.NAME -> profiles.sortedWith(naturalProfileComparator)
        NodeSortMode.DELAY -> profiles.sortedWith { left, right ->
            val leftLatency = latenciesMs[left.id]
            val rightLatency = latenciesMs[right.id]
            val leftRank = when {
                leftLatency != null -> 0
                left.id in failedIds -> 1
                else -> 2
            }
            val rightRank = when {
                rightLatency != null -> 0
                right.id in failedIds -> 1
                else -> 2
            }

            when {
                leftRank != rightRank -> leftRank.compareTo(rightRank)
                leftLatency != null && rightLatency != null && leftLatency != rightLatency -> leftLatency.compareTo(rightLatency)
                else -> naturalCompare(left.name, right.name)
            }
        }
    }

    internal val naturalProfileComparator = Comparator<VlessProfile> { left, right ->
        naturalCompare(left.name, right.name)
    }

    internal fun naturalCompare(left: String, right: String): Int {
        var leftIndex = 0
        var rightIndex = 0

        while (leftIndex < left.length && rightIndex < right.length) {
            val leftChar = left[leftIndex]
            val rightChar = right[rightIndex]

            if (leftChar.isDigit() && rightChar.isDigit()) {
                val leftStart = leftIndex
                val rightStart = rightIndex
                while (leftIndex < left.length && left[leftIndex].isDigit()) leftIndex++
                while (rightIndex < right.length && right[rightIndex].isDigit()) rightIndex++

                val leftNumber = left.substring(leftStart, leftIndex).trimStart('0').ifEmpty { "0" }
                val rightNumber = right.substring(rightStart, rightIndex).trimStart('0').ifEmpty { "0" }
                if (leftNumber.length != rightNumber.length) return leftNumber.length.compareTo(rightNumber.length)
                val numericCompare = leftNumber.compareTo(rightNumber)
                if (numericCompare != 0) return numericCompare
                continue
            }

            val foldedLeft = leftChar.lowercaseChar()
            val foldedRight = rightChar.lowercaseChar()
            if (foldedLeft != foldedRight) return foldedLeft.compareTo(foldedRight)
            leftIndex++
            rightIndex++
        }

        return when {
            leftIndex < left.length -> 1
            rightIndex < right.length -> -1
            else -> left.compareTo(right, ignoreCase = true)
        }
    }
}
