package com.example.tunerstudioandroid

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.espresso.matcher.RootMatchers.isDialog
import android.widget.EditText

@RunWith(AndroidJUnit4::class)
class MapAndSettingsTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun View.getLocationOnScreenY() = IntArray(2).also(::getLocationOnScreen)[1]

    @Test fun portraitMapScrollsAndEditsOriginalOffset() = checkMap(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
    @Test fun landscapeMapScrollsAndEditsOriginalOffset() = checkMap(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)

    private fun checkMap(orientation: Int) {
        activity.scenario.onActivity { it.requestedOrientation = orientation }
        val expected = if (orientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) 1 else 2
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        var ready = false
        while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
            activity.scenario.onActivity { ready = it.resources.configuration.orientation == expected }
            if (!ready) android.os.SystemClock.sleep(50)
        }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue("Requested orientation must be applied", ready)
        activity.scenario.recreate()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500,5000)
        var tableTitle = ""
        var firstCell = ""
        var yAxisName = ""
        var otherCell = 0.0
        var firstIndex = 0
        var otherIndex = 1
        activity.scenario.onActivity { a ->
            val s = ViewModelProvider(a)[StudioModel::class.java].session
            val t = s.ini.tables.values.first { it.zBins == "veTable" }
            tableTitle = t.title
            yAxisName = t.yBins
            val c = s.ini.constant(t.zBins)!!
            val reversed = orientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            for (i in 0 until c.cols()) s.ecu.set(t.xBins, i, 500.0 + 500*(if(reversed) c.cols()-1-i else i))
            for (i in 0 until c.rows()) s.ecu.set(t.yBins, i, 20.0 + 10*(if(reversed) c.rows()-1-i else i))
            firstIndex = if(reversed) 255 else 0
            otherIndex = if(reversed) 254 else 1
            firstCell = "veTable, ${t.xLabel} 500.0, ${t.yLabel} 20.0"
            otherCell = s.ecu.get(c, otherIndex)
        }
        onView(withText("Mapas")).perform(click())
        onView(withText(tableTitle)).perform(scrollTo(), click())
        onView(withText("Fechar")).inRoot(isDialog()).check { view, error ->
            if (error != null) throw error
            val cells = descendants(view.rootView).filter { it.contentDescription?.startsWith("veTable, ") == true }
            assertEquals(256, cells.size)
            val first = cells.first { it.contentDescription.toString().startsWith("$firstCell, valor ") }
            val viewport = descendants(view.rootView).filterIsInstance<MapViewport>().single()
            val scroll = descendants(viewport).filterIsInstance<ScrollView>().single()
            val (sheet, header) = descendants(viewport).filterIsInstance<HorizontalScrollView>()
            val xAxis = header.getLocationOnScreenY()
            assertTrue("The low-RPM, low-load cell is visible on open", first.getGlobalVisibleRect(Rect()))
            assertEquals(0, sheet.scrollX)
            val origin = IntArray(2).also(first::getLocationOnScreen)
            assertEquals(cells.minOf { IntArray(2).also(it::getLocationOnScreen)[0] }, origin[0])
            assertEquals(cells.maxOf { it.getLocationOnScreenY() }, origin[1])
            assertTrue(xAxis > origin[1])
            scroll.scrollTo(0, 0)
            assertEquals(xAxis, header.getLocationOnScreenY())
            val yAxis = descendants(viewport).first { it.contentDescription?.startsWith("$yAxisName [") == true }
            val yAxisX = IntArray(2).also(yAxis::getLocationOnScreen)[0]
            sheet.scrollTo(sheet.getChildAt(0).width - sheet.width, 0)
            assertTrue(sheet.scrollX > 0)
            assertEquals(sheet.scrollX, header.scrollX)
            assertEquals(yAxisX, IntArray(2).also(yAxis::getLocationOnScreen)[0])
            sheet.scrollTo(0, 0)
            scroll.scrollTo(0, scroll.getChildAt(0).height)
            assertTrue(scroll.scrollY > 0)
            assertEquals(xAxis, header.getLocationOnScreenY())
            val visible = Rect()
            assertTrue(first.getGlobalVisibleRect(visible))
            assertEquals(first.width, visible.width())
            assertEquals(first.height, visible.height())
            val bitmap = android.graphics.Bitmap.createBitmap(view.rootView.width,view.rootView.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.rootView.draw(android.graphics.Canvas(bitmap))
            java.io.File(view.context.filesDir,"map-review-$orientation.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
            bitmap.recycle()
        }
        onView(withContentDescription(org.hamcrest.Matchers.startsWith("$firstCell, valor "))).perform(click())
        onView(isAssignableFrom(EditText::class.java)).perform(replaceText("37"), closeSoftKeyboard())
        onView(withText("Aplicar")).perform(click())
        activity.scenario.onActivity { a ->
            val ecu = ViewModelProvider(a)[StudioModel::class.java].session.ecu
            assertEquals(37.0, ecu.get("veTable", firstIndex), 0.0)
            assertEquals(otherCell, ecu.get("veTable", otherIndex), 0.0)
        }
    }

    @Test fun appThemeAndScreenPreferenceSurviveRecreation() {
        onView(withContentDescription("Configurações")).perform(click())
        onView(withText("Tema · Escuro")).perform(scrollTo(), click())
        onView(withText("Claro")).inRoot(isDialog()).perform(click())
        onView(withText("Tema · Claro")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
        onView(withText("Manter a tela ligada")).perform(scrollTo(), click())
        activity.scenario.recreate()
        activity.scenario.onActivity { a ->
            assertEquals(android.content.res.Configuration.UI_MODE_NIGHT_NO,
                a.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            val toggle = descendants(a.window.decorView).filterIsInstance<androidx.appcompat.widget.SwitchCompat>().single()
            assertTrue(toggle.isChecked)
        }
        onView(withText("Manter a tela ligada")).perform(scrollTo(), click())
        onView(withText("Tema · Claro")).perform(scrollTo(), click())
        onView(withText("Escuro")).inRoot(isDialog()).perform(click())
        onView(withText("Tema · Escuro")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
    }

    @Test fun settingsDoNotListInternalPanelsAsSeparateFeatures() {
        onView(withText("Ajustes")).perform(click())
        activity.scenario.onActivity { a ->
            val buttons = descendants(a.window.decorView).filterIsInstance<Button>()
            assertFalse("Internal panels must stay within their parent dialog", buttons.any { it.text.toString() == "engine_constants_west" })
        }
    }

    @Test fun requiredFuelCommandOpensTheLocalCalculator() {
        onView(withText("Ajustes")).perform(click())
        onView(withHint("Buscar parâmetro ou diálogo")).perform(scrollTo(), replaceText("Fuel and engine setup"), closeSoftKeyboard())
        onView(org.hamcrest.Matchers.allOf(withText("Fuel and engine setup"),isAssignableFrom(Button::class.java))).perform(scrollTo(), click())
        onView(withText("Calculate Required Fuel...")).perform(scrollTo(), click())
        onView(withContentDescription("Cilindrada do motor")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
    }

    @Test fun nestedCurvePanelsOpenEditors() {
        activity.scenario.onActivity { a ->
            val ecu = ViewModelProvider(a)[StudioModel::class.java].session.ecu
            ecu.set("flexEnabled", 1.0)
            for (i in 0..5) ecu.set("flexFuelBins", i, i*20.0)
        }
        onView(withText("Ajustes")).perform(click())
        onView(withHint("Buscar parâmetro ou diálogo")).perform(scrollTo(), replaceText("flexFuel"), closeSoftKeyboard())
        onView(withText("Fuel Sensor Settings")).perform(scrollTo(), click())
        onView(withText("Flex Fuel Adjustments")).perform(scrollTo(), click())
        onView(withContentDescription(org.hamcrest.Matchers.startsWith("flexFuelBins [0], valor "))).check { view, error ->
            if (error != null) throw error
            val cells = descendants(view.rootView).filter { it.contentDescription?.startsWith("flexFuelBins [") == true || it.contentDescription?.startsWith("flexFuelAdj [") == true }
            assertEquals(12, cells.size)
            val scroll = descendants(view.rootView).filterIsInstance<MapViewport>().single()
                .let(::descendants).filterIsInstance<ScrollView>().single()
            scroll.scrollTo(0, scroll.getChildAt(0).height)
            val rect = Rect()
            assertTrue(view.getGlobalVisibleRect(rect))
            assertEquals(view.height, rect.height())
            assertEquals(view.width, rect.width())
            val ys = cells.filter { it.contentDescription?.startsWith("flexFuelBins [") == true }
                .map { IntArray(2).also(it::getLocationOnScreen)[1] }
            assertEquals(ys.maxOrNull(), IntArray(2).also(view::getLocationOnScreen)[1])
        }
    }
}
