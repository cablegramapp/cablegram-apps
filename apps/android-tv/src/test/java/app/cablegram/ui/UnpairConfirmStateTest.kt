package app.cablegram.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnpairConfirmStateTest {
    @Test
    fun `asking does not unpair`() {
        var unpaired = 0
        val state = UnpairConfirmState()
        state.ask()
        assertTrue(state.asking)
        assertEquals(0, unpaired)
    }

    @Test
    fun `cancelling closes the question without unpairing`() {
        var unpaired = 0
        val state = UnpairConfirmState()
        state.ask()
        state.cancel()
        assertFalse(state.asking)
        state.confirm { unpaired++ }
        assertEquals(0, unpaired)
    }

    @Test
    fun `confirming unpairs once and closes the question`() {
        var unpaired = 0
        val state = UnpairConfirmState()
        state.ask()
        state.confirm { unpaired++ }
        state.confirm { unpaired++ }
        assertFalse(state.asking)
        assertEquals(1, unpaired)
    }
}
