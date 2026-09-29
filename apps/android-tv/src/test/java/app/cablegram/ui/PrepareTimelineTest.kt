package app.cablegram.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PrepareTimelineTest {
    @Test
    fun `highlights remuxing from stage even when percent is slightly behind`() {
        val statuses = prepareStepStatuses(86, "remuxing")
        assertEquals(PrepareStepStatus.COMPLETED, statuses[4])
        assertEquals(PrepareStepStatus.ACTIVE, statuses[5])
        assertEquals(PrepareStepStatus.PENDING, statuses[6])
    }

    @Test
    fun `infers downloading from mid-range percent when stage is missing`() {
        assertEquals(2, resolvePrepareStepIndex(54, null))
    }

    @Test
    fun `marks every earlier step complete at 100 percent`() {
        val statuses = prepareStepStatuses(100, "ready")
        assertEquals(9, statuses.count { it == PrepareStepStatus.COMPLETED })
        assertEquals(PrepareStepStatus.ACTIVE, statuses.last())
    }

    @Test
    fun `highlights catalog as the first prepare step`() {
        assertEquals(0, resolvePrepareStepIndex(6, "catalog"))
        assertEquals(1, resolvePrepareStepIndex(11, "queued"))
    }
}
