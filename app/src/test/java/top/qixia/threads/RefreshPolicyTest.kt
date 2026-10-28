package top.qixia.threads

import org.junit.Assert.*
import org.junit.Test
import qixia.refresh.RefreshPolicy

class RefreshPolicyTest {
    private val rates = floatArrayOf(48f,60f,90f,144f,165f)
    private fun decide(raw: String = "", active: Boolean = true, locked: Boolean = false,
                       battery: Int = 80, brightness: Float = 50f, launcher: Boolean = false) =
        RefreshPolicy.parse(raw).decide("com.test.game",launcher,locked,active,battery,brightness,rates)
    @Test fun `supported rates are not capped at 120 and idle uses lowest`() {
        assertEquals(165f,decide().hz,0f);assertEquals(48f,decide(active=false).hz,0f)
    }
    @Test fun `app idle and active override with inherited values`() {
        val raw="app.com.test.game=60,144"
        assertEquals(60f,decide(raw,active=false).hz,0f);assertEquals(144f,decide(raw).hz,0f)
        assertEquals(165f,decide("app.com.test.game=-2,-2,0").hz,0f)
    }
    @Test fun `game category no longer overrides idle and active choices`() {
        assertEquals(60f,decide("app.com.test.game=60,144,1\ngame_hz=165",active=false).hz,0f)
        assertEquals(144f,decide("app.com.test.game=60,144,1").hz,0f)
        assertFalse(RefreshPolicy.valid("game_hz","165"))
        assertEquals(144f,decide("app.com.test.game=144,144",active=false).hz,0f)
    }
    @Test fun `lock and launcher take priority over per app rules`() {
        assertEquals(48f,decide("app.com.test.game=144,144,1",locked=true).hz,0f)
        assertEquals(165f,decide("app.com.test.game=60,60,0",launcher=true).hz,0f)
    }
    @Test fun `launcher only uses gesture rate while interacting and idles normally`() {
        val raw="gesture_hz=144\nidle_hz=60\napp.com.test.game=165,165"
        assertEquals(144f,decide(raw,launcher=true).hz,0f)
        assertEquals(60f,decide(raw,launcher=true,active=false).hz,0f)
        assertEquals(48f,decide(raw,launcher=true,locked=true,active=false).hz,0f)
    }
    @Test fun `low battery caps and low brightness avoids low refresh flicker`() {
        assertEquals(48f,decide(battery=3).hz,0f)
        assertEquals(144f,decide("brightness_threshold=20\nbrightness_hz=144",battery=3,brightness=5f).hz,0f)
        assertEquals(165f,decide("battery_threshold=0",battery=10).hz,0f)
    }
    @Test fun `malformed injected and nonfinite config never enters shell`() {
        for(v in listOf("NaN","Infinity","120;id","120\nfoo=1","120\n"," 120","0x1.8p6","-3")) assertFalse(RefreshPolicy.valid("active_hz",v))
        assertFalse(RefreshPolicy.valid("app.com.test;id","60,120,0"))
        assertFalse(RefreshPolicy.valid("app.com.test","60"))
        assertFalse(RefreshPolicy.valid("enabled","2"))
        assertEquals("-1",RefreshPolicy.parse("active_hz=NaN").get("active_hz"))
    }
    @Test fun `roundtrip and removing app rules preserve all other options`() {
        val p=RefreshPolicy.parse("enabled=1\napp.com.test.game=60,144,1\ndisplay_modes=4 -1 0").with("strict","1")
        assertEquals(p.values,RefreshPolicy.parse(p.encode()).values.filterKeys { it in p.values })
        assertFalse(p.with("app.com.test.game",null).values.containsKey("app.com.test.game"))
        assertEquals("4 -1 0",p.with("app.com.test.game",null).get("display_modes"))
    }
    @Test fun `mapping binds every actual rate including 144 and 165`() {
        val mapping=RefreshPolicy.bindDisplayModes("9 7 -1 3 0",rates)
        val p=RefreshPolicy.parse("display_modes=$mapping")
        assertEquals("9 7 -1 3 0",mapping)
        assertEquals("9",p.displayMode(165f,rates));assertEquals("7",p.displayMode(144f,rates))
        assertEquals("",p.displayMode(90f,rates));assertEquals("3",p.displayMode(60f,rates))
        assertEquals("0",p.displayMode(48f,rates));assertEquals("",p.displayMode(120f,rates))
        assertEquals("9 7 -1 3 0",p.displayModeIds(rates))
    }
    @Test fun `incomplete or unsafe display modes are rejected`() {
        for(value in listOf("","-2 1 0","1.5 2 0","1;id 2 0","1 2\n0","1 2 0\n"," 1 2 0","1 2 0 ",
            "2147483648 2 0","165:1 90:2 60:-1","NaN","Infinity"))
            assertFalse(value,RefreshPolicy.valid("display_modes",value))
        assertEquals("-1 -1 -1",RefreshPolicy.parse("display_modes=165:1").get("display_modes"))
        assertFalse(RefreshPolicy.valid("mode.120", "1"))
    }
    @Test fun `any device rate count gets exactly that many automatic inputs`() {
        val p=RefreshPolicy.parse("")
        assertEquals("-1 -1 -1",p.get("display_modes"))
        for(device in listOf(floatArrayOf(60f),floatArrayOf(60f,120f),floatArrayOf(60f,90f,144f),rates)) {
            assertEquals(List(device.size) { "-1" }.joinToString(" "),p.displayModeIds(device))
            val bound=RefreshPolicy.bindDisplayModes(p.displayModeIds(device),device)
            assertTrue(RefreshPolicy.valid("display_modes",bound))
            assertEquals("-1 -1 -1",bound)
            for(hz in device) assertEquals("",p.with("display_modes",bound).displayMode(hz,device))
        }
    }
    @Test fun `device rates are sorted and count mismatch uses automatic selection`() {
        val p=RefreshPolicy.parse("display_modes=9 7 3")
        assertEquals("9 7 3",p.displayModeIds(floatArrayOf(60f,165f,144f)))
        assertEquals("9",p.displayMode(165f,floatArrayOf(144f,60f,165f,60f)))
        assertEquals("-1 -1",p.displayModeIds(floatArrayOf(60f,165f)))
        assertEquals("",p.displayMode(165f,floatArrayOf(60f,165f)))
    }
    @Test fun `fractional rates retain independent IDs and precise persistence`() {
        val rates=floatArrayOf(59.94f,60f,119.88f,120.00001f)
        val p=RefreshPolicy.parse("").with("display_modes",RefreshPolicy.bindDisplayModes("4 3 2 1",rates))
        assertEquals("4",p.displayMode(120.00001f,rates));assertEquals("3",p.displayMode(119.88f,rates))
        assertEquals("2",p.displayMode(60f,rates));assertEquals("1",p.displayMode(59.94f,rates))
        assertEquals("4 3 2 1",RefreshPolicy.parse(p.encode()).displayModeIds(rates))
        assertEquals("120",RefreshPolicy.rateLabel(120.00001f))
        assertEquals("59.94",RefreshPolicy.rateLabel(59.94f))
    }
    @Test fun `discovery does not fabricate rates or retain invalid and duplicate modes`() {
        assertArrayEquals(floatArrayOf(165f,60f),RefreshPolicy.displayRates(floatArrayOf(60f,165f,60f,0f,-1f,Float.NaN,Float.POSITIVE_INFINITY)),0f)
        assertEquals("",RefreshPolicy.parse("").displayModeIds(floatArrayOf()))
        assertThrows(IllegalArgumentException::class.java) { RefreshPolicy.bindDisplayModes("",floatArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { RefreshPolicy.bindDisplayModes("1 2 3",rates) }
        assertThrows(IllegalArgumentException::class.java) { RefreshPolicy.bindDisplayModes("1 2 3 4;id 5",rates) }
    }
    @Test fun `selection labels rank device rates without fixed frequency tiers`() {
        fun label(hz:String, device:List<Float>)=top.qixia.threads.compose.ui.refreshChoice(hz,device)
        assertEquals("高 (165Hz)",label("-1",rates.toList()))
        assertEquals("低 (48Hz)",label("0",rates.toList()))
        assertEquals("144 Hz",label("144",rates.toList()))
        assertEquals("中 (120Hz)",label("120",listOf(60f,120f,165f)))
        assertEquals("60 Hz",label("60",listOf(60f)))
        assertEquals("未获取",label("-1",emptyList()))
        assertEquals("未获取",label("0",emptyList()))
        assertEquals("跟随全局",label("-2",rates.toList()))
        assertEquals("高 (120Hz)",label("165",listOf(60f,120f)))
    }
    @Test fun `actual fractional refresh matches closest available mode`() {
        assertEquals(119.88f,RefreshPolicy.resolve(120f,floatArrayOf(59.94f,119.88f)),0f)
    }
}
