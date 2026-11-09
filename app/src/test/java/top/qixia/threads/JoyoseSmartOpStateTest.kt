package top.qixia.threads

import org.junit.Assert.*
import org.junit.Test

class JoyoseSmartOpStateTest {
    private val valid = "version=1\nok=1\navailable=1\ndisabled=1\nrequested=1\nreason=ready\n"

    @Test fun usesObservedStateInsteadOfRequestedState() {
        val state = JoyoseSmartOpState.parse(valid.replace("disabled=1", "disabled=0").replace("ok=1", "ok=0"), false)
        assertTrue(state.recognized)
        assertTrue(state.available)
        assertFalse(state.successful)
        assertFalse(state.disabled)
        assertEquals(true, state.requestedDisabled)
    }

    @Test fun acceptsCompleteStatusAndHidesUnavailableComponents() {
        assertTrue(JoyoseSmartOpState.parse(valid, true).successful)
        val unavailable = JoyoseSmartOpState.parse(valid.replace("available=1", "available=0").replace("disabled=1", "disabled=unknown"), true)
        assertTrue(unavailable.recognized)
        assertFalse(unavailable.available)
        assertFalse(JoyoseSmartOpState.parse(valid, false).successful)
    }

    @Test fun rejectsLegacyScriptNoiseDuplicateAndMalformedFields() {
        for (raw in listOf("watchdog already running", valid + "disabled=0\n", valid.replace("version=1", "version=2"),
            valid.replace("disabled=1", "disabled=unknown"), valid.replace("requested=1", "requested=bad"),
            valid.replace("ok=1\n", ""), valid.replace("available=1", "available=yes"))) {
            assertFalse(raw, JoyoseSmartOpState.parse(raw, true).recognized)
        }
    }
}
