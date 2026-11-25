package com.example.tunerstudioandroid.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RealDashReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun decodesInputsAndBoundsInTheFirstView() {
        val file = temporary.newFile("teste.rd")
        file.writeBytes(dashboard {
            gauge("Velocidade", 64)
            gauge("RPM oculto", 37, visible = false)
            listOf(1, 1, 0, 0, 1).forEach(::integer)
            gauge("Temperatura de outra vista", 14)
        })

        assertTrue(RealDashReader.recognizes(file))
        val definition = RealDashReader.read(file)
        assertEquals("Teste", definition.name())
        assertEquals("1.7777777777777777", definition.clusterProperties()["designAspectRatio"])
        val gauge = definition.components().single()
        assertEquals("vss", gauge.text("OutputChannel"))
        assertEquals("km/h", gauge.text("Units"))
        assertEquals(0.25, gauge.number("RelativeWidth", 0.0), 0.0001)
        assertTrue(definition.diagnostics().any { it.contains("primeira vista") })
    }

    @Test fun unknownInputNeverUsesTheLastSavedNumber() {
        val file = temporary.newFile("desconhecido.rd")
        file.writeBytes(dashboard { gauge("Canal externo", 10001) })
        val definition = RealDashReader.read(file)
        val gauge = definition.components().single()
        assertEquals("Basic Readout", gauge.painter())
        assertEquals("", gauge.text("OutputChannel"))
        assertEquals("", gauge.text("LabelText"))
        assertTrue(definition.diagnostics().any { it.contains("sem correspondência") })
    }

    @Test fun rejectsTruncatedInstrument() {
        val file = temporary.newFile("incompleto.rd")
        val full = dashboard { gauge("Velocidade", 64) }
        file.writeBytes(full.copyOf(full.size - 145))
        assertThrows(IllegalArgumentException::class.java) { RealDashReader.read(file) }
    }

    private fun dashboard(content: BinaryFixture.() -> Unit): ByteArray = BinaryFixture().apply {
        listOf(0x00034006, 4, 6).forEach(::integer)
        text("Teste")
        text("descrição".repeat(80))
        listOf(1280, 720, 0).forEach(::integer)
        content()
    }.toByteArray()

    private class BinaryFixture : ByteArrayOutputStream() {
        fun integer(value: Int) {
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        }

        fun text(value: String) {
            integer(value.length)
            write(value.toByteArray(Charsets.UTF_16LE))
        }

        fun gauge(name: String, targetId: Int, visible: Boolean = true) {
            integer(0x0cbd0002)
            text(name)
            integer(if (visible) 0x40000002 else 0x40000000)
            integer(0)
            text("")
            integer(16)
            listOf(0.1f, 0.25f, 0.35f, 0.5f).forEach { integer(it.toRawBits()) }
            repeat(12) { integer(0) }
            integer(9)
            repeat(9) { integer(0) }
            text("\$#V2#\$")
            text("")
            text("120")
            repeat(28) { integer(0) }
            listOf(4, targetId, 0, 0, 0x01020304).forEach(::integer)
        }
    }
}
