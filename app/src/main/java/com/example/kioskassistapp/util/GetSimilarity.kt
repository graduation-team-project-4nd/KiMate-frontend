package com.example.kioskassistapp.util

object TextSimilarity {
    // 두 문자열의 유사도를 0.0 ~ 1.0 사이의 값으로 반환 (1.0이면 완전 일치)
    fun calculate(s1: String, s2: String): Double {
        val longer = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1
        val longerLength = longer.length

        if (longerLength == 0) return 1.0

        val distance = levenshteinDistance(longer, shorter)
        return (longerLength - distance).toDouble() / longerLength.toDouble()
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val costs = IntArray(s2.length + 1)
        for (i in 0..s2.length) costs[i] = i

        for (i in 1..s1.length) {
            costs[0] = i
            var nw = i - 1
            for (j in 1..s2.length) {
                val cj = Math.min(1 + Math.min(costs[j], costs[j - 1]),
                    if (s1[i - 1] == s2[j - 1]) nw else nw + 1)
                nw = costs[j]
                costs[j] = cj
            }
        }
        return costs[s2.length]
    }
}