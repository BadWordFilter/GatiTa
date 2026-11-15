package ai.gatita.app

import org.junit.Assert.*
import org.junit.Test

class RiderEditsTest {
    private val original = mapOf(Seat.D to Rider.ADULT, Seat.P to Rider.ADULT,
        Seat.RL to Rider.KID, Seat.RR to Rider.PET, Seat.RM to Rider.NONE)

    @Test fun passengerChangePreservesRearSeats() {
        val result = RiderEdits.apply(original, "조수석은 할머니야")!!
        assertEquals(Rider.ELDER, result[Seat.P])
        assertEquals(Rider.KID, result[Seat.RL])
        assertEquals(Rider.PET, result[Seat.RR])
        assertEquals(Rider.ADULT, original[Seat.P])
    }
    @Test fun rearLeftChangePreservesOthers() {
        val result = RiderEdits.apply(original, "뒷좌석 왼쪽에 어르신")!!
        assertEquals(Rider.ELDER, result[Seat.RL])
        assertEquals(Rider.PET, result[Seat.RR])
        assertEquals(Rider.ADULT, result[Seat.P])
    }
    @Test fun clearOnlyRequestedSeat() {
        val result = RiderEdits.apply(original, "옆자리는 비워 줘")!!
        assertEquals(Rider.NONE, result[Seat.P])
        assertEquals(Rider.KID, result[Seat.RL])
        assertEquals(Rider.PET, result[Seat.RR])
    }
    @Test fun multipleSeatChangesAndRearCount() {
        val result = RiderEdits.apply(original, "조수석에 할머니, 뒷좌석에는 아이 둘")!!
        assertEquals(Rider.ELDER, result[Seat.P])
        assertEquals(2, result.values.count { it == Rider.KID })
        assertEquals(Rider.NONE, result[Seat.RM])
    }
    @Test fun reversedPhraseAndAmbiguity() {
        assertEquals(Rider.ELDER, RiderEdits.apply(original, "할머니는 조수석")!![Seat.P])
        assertNull(RiderEdits.apply(original, "조수석은 글쎄"))
        assertNull(RiderEdits.apply(original, "조수석에 아이 둘"))
        assertEquals(Rider.ADULT, original[Seat.P])
    }
}
