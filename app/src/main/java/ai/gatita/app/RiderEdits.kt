package ai.gatita.app

/** Explicit seat changes preserve all other seats. Unclear changes are rejected for re-entry. */
internal object RiderEdits {
    private val seatPattern = Regex("뒷좌석왼쪽|뒷자리왼쪽|뒷좌석오른쪽|뒷자리오른쪽|뒷좌석가운데|뒷자리가운데|조수석|옆자리|앞자리|앞좌석|보조석|뒷좌석|뒷자리|뒤에")
    private fun compact(text: String) = text.replace(Regex("\\s+"), "")
    fun hasSeat(text: String) = seatPattern.containsMatchIn(compact(text))
    fun apply(current: Map<Seat, Rider>, text: String): Map<Seat, Rider>? {
        val t = compact(text)
        val locations = seatPattern.findAll(t).toList()
        if (locations.isEmpty()) return null
        val result = current.toMutableMap()
        for ((index, location) in locations.withIndex()) {
            val targets = when (location.value) {
                "뒷좌석왼쪽", "뒷자리왼쪽" -> listOf(Seat.RL)
                "뒷좌석오른쪽", "뒷자리오른쪽" -> listOf(Seat.RR)
                "뒷좌석가운데", "뒷자리가운데" -> listOf(Seat.RM)
                "뒷좌석", "뒷자리", "뒤에" -> listOf(Seat.RL, Seat.RR, Seat.RM)
                else -> listOf(Seat.P)
            }
            val end = locations.getOrNull(index + 1)?.range?.first ?: t.length
            var clause = t.substring(location.range.last + 1, end).trim(',', '，')
            // Also allow "할머니는 조수석".
            if (clause.isBlank() || clause in listOf("이야", "야", "으로", "에")) {
                val start = locations.getOrNull(index - 1)?.range?.last?.plus(1) ?: 0
                clause = t.substring(start, location.range.first).substringAfterLast(',')
            }
            if (listOf("빈자리", "비워", "없어", "안타", "내렸", "비었").any { clause.contains(it) }) {
                targets.forEach { result[it] = Rider.NONE }
                continue
            }
            val parsed = MainActivity.parseRiders(clause) ?: return null
            val occupants = listOf(Seat.P, Seat.RL, Seat.RR, Seat.RM).mapNotNull { parsed[it]?.takeIf { r -> r != Rider.NONE } }
            if (occupants.isEmpty() || occupants.size > targets.size) return null
            targets.forEach { result[it] = Rider.NONE }
            targets.zip(occupants).forEach { (seat, rider) -> result[seat] = rider }
        }
        result[Seat.D] = Rider.ADULT
        return result
    }
}
