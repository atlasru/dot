package dev.dotclient.android.core.model

import dev.dotclient.android.core.diagnostics.PingResult
import dev.dotclient.android.core.diagnostics.PingStatus

enum class NodeSortMode {
    ORIGIN,
    DELAY,
    NAME,
}

object NodeSorter {
    fun sort(
        profiles: List<ProxyNode>,
        mode: NodeSortMode,
        results: Map<String, PingResult>,
    ): List<ProxyNode> = when (mode) {
        NodeSortMode.ORIGIN -> profiles
        NodeSortMode.NAME -> profiles.sortedWith(naturalProfileComparator)
        NodeSortMode.DELAY -> profiles.sortedWith(compareBy<ProxyNode> {
            when (results[it.id]?.status) {
                PingStatus.OK -> 0
                PingStatus.UNAVAILABLE -> 1
                PingStatus.FAIL -> 2
                null -> 3
            }
        }.thenBy { results[it.id]?.takeIf { result -> result.status == PingStatus.OK }?.latencyMs })
    }

    internal val naturalProfileComparator = Comparator<ProxyNode> { left, right ->
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
