package top.qixia.threads

import org.junit.Assert.*
import org.junit.Test
import qixia.refresh.*
import java.util.Properties

class RefreshCompatibilityTest {
    private fun profile(sdk: Int = 35, props: Map<String,String> = emptyMap(),
        nodes: Set<String> = emptySet(), soc: String = "", model: String = "") = RefreshPlatform(sdk,props,nodes,soc,model)

    @Test fun `new mediatek defaults never override explicit user choices`() {
        val mtk=profile(soc="mt6989")
        assertTrue(mtk.modernMtk)
        assertFalse(profile(sdk=34,soc="mt6989").modernMtk)
        assertTrue(profile(props=mapOf("ro.soc.manufacturer" to "MediaTek")).modernMtk)
        assertFalse(profile(soc="SM8650").modernMtk)
        val defaults=RefreshPolicy.parse(RefreshPolicy.parse("").encode())
        assertTrue(defaults.on("compatible",mtk.modernMtk))
        assertTrue(defaults.on("strict",mtk.modernMtk))
        assertFalse(defaults.with("compatible","0").on("compatible",true))
        assertTrue(defaults.with("strict","1").on("strict",false))
    }
    @Test fun `vendor keys require matching features and never remove thermal constraints`() {
        assertEquals(listOf("system/peak_refresh_rate","system/min_refresh_rate","secure/vivo_screen_refresh_rate_mode"),
            profile(nodes=setOf("/sys/module/vivo_display")).settings())
        assertTrue(profile(props=mapOf("ro.product.brand" to "Redmi")).settings().contains("secure/miui_refresh_rate"))
        assertEquals(listOf("system/flyme_force_rate"),profile(model="m2468\n").settings())
        assertFalse(profile(model="m9999").flyme)
        val oplus=profile(nodes=setOf("/sys/kernel/oplus_display/min_fps"))
        assertTrue(oplus.oplus)
        assertTrue(oplus.settings().contains("secure/user_refresh_rate"))
        assertFalse(oplus.settings().any { it.contains("thermal") || it.contains("min_fps") })
    }
    @Test fun `oplus surface dump keeps physical IDs high rates and correct display`() {
        val dump="""
            Display 123
                activeMode={id=8, hwcId=1, resolution=1080x2400, vsyncRate=165.00 Hz, group=0}
                displayModes=
                    {id=3, hwcId=0, resolution=1080x2400, vsyncRate=60.00 Hz, group=0}
                    {id=8, hwcId=1, resolution=1080x2400, vsyncRate=165.00 Hz, group=0}
                    {id=9, hwcId=2, resolution=1440x3200, vsyncRate=165.00 Hz, group=0}
                displayManagerPolicy={defaultModeId=8}
            Display 456
                activeMode={id=8, resolution=1920x1080, vsyncRate=144.00 Hz}
                displayModes=
                    {id=8, resolution=1920x1080, vsyncRate=144.00 Hz}
        """.trimIndent()
        val snapshot=RefreshModes.surface(dump,123,false)
        assertEquals(165f,snapshot.active,0f)
        assertEquals(8,RefreshModes.select(snapshot.modes,1080,2400,165f,""))
        assertEquals(-1,RefreshModes.select(snapshot.modes,1080,2400,165f,"9"))
        assertEquals(-1,RefreshModes.select(snapshot.modes,1920,1080,144f,""))
        assertTrue(RefreshModes.surface(dump,-1,true).modes.isEmpty())
    }
    @Test fun `android 12 physical modes are distinct from framework mapping`() {
        val dump="""
              mSfDisplayModes=
                DisplayMode{id=0, width=1080, height=2400, refreshRate=60.000004}
                DisplayMode{id=2, width=1080, height=2400, refreshRate=120.00001}
              mActiveSfDisplayMode=DisplayMode{id=0, width=1080, height=2400, refreshRate=60.000004}
              mSupportedModesByDisplay:
                0 -> [{id=1, width=1080, height=2400, fps=60.000004}, {id=3, width=1080, height=2400, fps=120.00001}]
        """.trimIndent()
        val physical=RefreshModes.display(dump,true)
        assertEquals(2,physical.size)
        assertEquals(2,RefreshModes.select(physical,1080,2400,120f,""))
        val logical=RefreshModes.display(dump,false)
        assertEquals(2,logical.size)
        assertEquals(-1,RefreshModes.select(logical,1080,2400,120f,""))
        assertTrue(logical.none { it.physical })
    }
    @Test fun `bad dumps cannot fabricate modes`() {
        assertTrue(RefreshModes.display("mSfDisplayModes=\nDisplayMode{id=0, width=1, height=1, refreshRate=NaN}",true).isEmpty())
        assertTrue(RefreshModes.surface("permission denied",123,true).modes.isEmpty())
        assertTrue(RefreshModes.display("mSupportedModesByDisplay:\n  1 -> [{id=3, width=1080, height=2400, fps=144}]",true).isEmpty())
    }
    @Test fun `backdoor ownership recognizes old and new dumps but rejects ambiguity`() {
        assertEquals(false,RefreshModes.overrideState(" (mode override by backdoor: no)"))
        assertEquals(true,RefreshModes.overrideState("debugDisplayModeSetByBackdoor=true\n"))
        assertNull(RefreshModes.overrideState("permission denied"))
        assertNull(RefreshModes.overrideState("(mode override by backdoor: yes)\ndebugDisplayModeSetByBackdoor=false"))
    }
    @Test fun `backlight percentages reject unknown and mismatched units`() {
        assertEquals(50f,RefreshPlatform.brightness(2047.5f,4095f),.01f)
        assertEquals(-1f,RefreshPlatform.brightness(300f,255f),0f)
        assertEquals(-1f,RefreshPlatform.brightness(1f,0f),0f)
        assertEquals(-1f,RefreshPlatform.brightness(Float.NaN,1f),0f)
    }
    private class Settings : RefreshSettingJournal.Store {
        val values=mutableMapOf("system/peak_refresh_rate" to "120", "secure/user_refresh_rate" to "90")
        var reject: String?=null
        override fun get(key: String)=values[key]
        override fun put(key: String, value: String?) {
            if(key==reject)throw IllegalStateException("模拟厂商拒绝")
            if(value==null)values.remove(key) else values[key]=value
        }
    }
    @Test fun `partial settings write restores values and absent keys across namespaces`() {
        val store=Settings();val before=store.values.toMap();val state=Properties()
        val keys=listOf("system/peak_refresh_rate","system/min_refresh_rate","secure/user_refresh_rate")
        RefreshSettingJournal.capture(state,keys,store)
        store.reject="secure/user_refresh_rate"
        assertThrows(Exception::class.java) { RefreshSettingJournal.apply(state,keys,"60",store) }
        assertEquals("60",store.get("system/min_refresh_rate"))
        store.reject=null
        RefreshSettingJournal.restore(state,store)
        assertEquals(before,store.values)
    }
    @Test fun `restore continues other keys and retains baseline on failure`() {
        val store=Settings();val state=Properties()
        val keys=listOf("system/peak_refresh_rate","system/min_refresh_rate","secure/user_refresh_rate")
        RefreshSettingJournal.capture(state,keys,store)
        RefreshSettingJournal.apply(state,keys,"60",store)
        store.reject="secure/user_refresh_rate"
        assertThrows(Exception::class.java) { RefreshSettingJournal.restore(state,store) }
        assertEquals("120",store.get("system/peak_refresh_rate"))
        assertNull(store.get("system/min_refresh_rate"))
        assertEquals(3,state.size)
        store.reject=null
        RefreshSettingJournal.restore(state,store)
        assertEquals("90",store.get("secure/user_refresh_rate"))
    }
    @Test fun `write without baseline is rejected before any mutation`() {
        val store=Settings();val before=store.values.toMap()
        assertThrows(Exception::class.java) { RefreshSettingJournal.apply(Properties(),listOf("system/min_refresh_rate"),"60",store) }
        assertEquals(before,store.values)
    }
}
