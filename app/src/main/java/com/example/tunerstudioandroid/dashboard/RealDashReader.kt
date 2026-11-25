package com.example.tunerstudioandroid.dashboard

import org.clonetuner.dashboard.DashboardDefinition
import org.clonetuner.dashboard.DashboardDefinition.Component
import org.clonetuner.dashboard.DashboardDefinition.Resource
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Reads the base instrument layout found in exported RealDash dashboards (format 4). */
internal object RealDashReader {
    private const val FILE_MAGIC = 0x00034006
    private const val INSTRUMENT_TAG = 0x0cbd
    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_INSTRUMENTS = 512
    private const val MAX_IMAGE_PIXELS = 8_000_000L
    private val styleTag = "\$#V2#\$".toByteArray(Charsets.UTF_16LE)
    private val pngTag = byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private val inputTags = setOf(0x01020304, 0xfefdfce6.toInt())

    fun recognizes(file: File): Boolean = file.inputStream().use { input ->
        input.read() == 6 && input.read() == 64 && input.read() == 3 && input.read() == 0
    }

    fun read(file: File): DashboardDefinition {
        require(file.length() in 12L..MAX_BYTES.toLong()) { "Tamanho inválido para um painel RealDash." }
        val data = Binary(file.readBytes())
        require(data.int(0) == FILE_MAGIC && data.int(4) == 4) { "Formato de painel RealDash incompatível." }
        val name = data.string(12) ?: error("Cabeçalho RealDash inválido.")
        val descriptionEnd = data.stringEnd(name.end, 32_768) ?: error("Cabeçalho RealDash inválido.")
        val width = data.int(descriptionEnd)
        val height = data.int(descriptionEnd + 4)
        require(width in 1..16384 && height in 1..16384) { "Dimensões inválidas no painel RealDash." }

        val records = mutableListOf<Instrument>()
        var viewEnd = data.size
        for (offset in descriptionEnd + 12 until data.size - 4) {
            if (data.short(offset + 2) != INSTRUMENT_TAG) continue
            val record = instrument(data, offset) ?: continue
            if (records.isNotEmpty() && startsView(data, offset)) {
                viewEnd = offset
                break
            }
            require(records.size < MAX_INSTRUMENTS) { "O painel RealDash tem instrumentos demais." }
            records.add(record)
        }
        require(records.isNotEmpty()) { "Nenhum instrumento compatível encontrado no painel RealDash." }

        val diagnostics = mutableListOf("Importação RealDash. Animações, ações de botões e efeitos personalizados ficam desativados.")
        if (viewEnd != data.size) diagnostics.add("Somente a primeira vista do arquivo foi importada.")
        val referenced = mutableSetOf<String>()
        val components = records.mapIndexedNotNull { index, record ->
            if (!record.visible) return@mapIndexedNotNull null
            val end = records.getOrNull(index + 1)?.offset ?: viewEnd
            component(data, record, end, index, diagnostics)?.also {
                it.text("BackgroundImageFileName").takeIf(String::isNotBlank)?.let(referenced::add)
            }
        }
        require(components.isNotEmpty()) { "O painel RealDash não contém elementos compatíveis." }
        val resources = images(data, records.first().offset, referenced)
        (referenced - resources.keys).forEach { diagnostics.add("Imagem embutida indisponível: $it") }
        val drawable = components.filter { component ->
            val imageAvailable = component.text("BackgroundImageFileName") in resources
            when (component.painter()) {
                "Static Image" -> imageAvailable
                "Static Label" -> imageAvailable || component.text("LabelText").isNotBlank()
                else -> true
            }
        }
        require(drawable.isNotEmpty()) { "Nenhum elemento do painel RealDash pôde ser exibido." }
        val properties = mapOf("designAspectRatio" to (width.toDouble() / height).coerceIn(0.25, 5.0).toString())
        return DashboardDefinition(name.value.ifBlank { file.nameWithoutExtension }, "", properties,
            drawable, resources, diagnostics)
    }

    private fun startsView(data: Binary, offset: Int): Boolean =
        data.int(offset - 20) == 1 && data.int(offset - 16) == 1 &&
            data.int(offset - 12) == 0 && data.int(offset - 8) == 0 &&
            data.int(offset - 4) in 1..MAX_INSTRUMENTS

    private fun instrument(data: Binary, offset: Int): Instrument? {
        val type = data.short(offset)
        if (type !in 1..128) return null
        val name = data.string(offset + 4) ?: return null
        val visible = data.int(name.end) and 2 != 0
        val image = data.string(name.end + 8) ?: return null
        val geometry = image.end
        val fields = data.int(geometry)
        if (fields !in 16..17) return null
        val colors = geometry + 4 + fields * 4
        val style = colors + 44
        if (data.int(colors) != 9 || data.int(style - 4) != 6 || !data.matches(style, styleTag)) return null
        val x = data.float(geometry + 4)
        val y = data.float(geometry + 8)
        val right = data.float(geometry + 12)
        val bottom = data.float(geometry + 16)
        if (!listOf(x, y, right, bottom).all { it.isFinite() && it in -4f..5f } ||
            right - x < 0.001f || bottom - y < 0.001f) return null
        val font = data.string(style + styleTag.size) ?: return null
        val text = data.string(font.end) ?: return null
        return Instrument(offset, type, visible, name.value, image.value, x, y, right - x, bottom - y, text)
    }

    private fun component(data: Binary, record: Instrument, end: Int, index: Int,
                          diagnostics: MutableList<String>): Component? {
        val input = inputId(data, record.text.end, end)
        val signal = signals[input]
        val painter = when (record.type) {
            1, 8 -> "Analog Gauge"
            2 -> if (input == -1 && record.text.value.isNotBlank() && record.text.value.toDoubleOrNull() == null)
                "Static Label" else "Basic Readout"
            3 -> if (record.width >= record.height) "Horizontal Bar Gauge" else "Vertical Bar Gauge"
            5 -> if (record.image.isNotBlank()) "Static Image" else return null
            11 -> if (record.image.isNotBlank() || record.text.value.isNotBlank()) "Static Label" else return null
            else -> {
                diagnostics.add("${record.name}: tipo de instrumento RealDash ${record.type} indisponível.")
                return null
            }
        }
        val properties = mutableMapOf(
            "RelativeX" to record.x.toString(), "RelativeY" to record.y.toString(),
            "RelativeWidth" to record.width.toString(), "RelativeHeight" to record.height.toString(),
            "Title" to record.name, "FontColor" to data.int(record.text.end + 4).toString(),
            "BackgroundImageFileName" to record.image.takeIf { it.endsWith(".png", true) }.orEmpty()
        )
        if (painter == "Static Label") properties["LabelText"] = record.text.value
        if (signal != null && record.type in setOf(1, 2, 3, 8)) {
            val range = needleRange(data, record, end) ?: (signal.minimum to signal.maximum)
            properties.putAll(mapOf("OutputChannel" to signal.channel, "Units" to signal.units,
                "Min" to range.first.toString(), "Max" to range.second.toString(),
                "ValueDigits" to signal.digits.toString()))
        } else if (painter in setOf("Analog Gauge", "Basic Readout", "Horizontal Bar Gauge", "Vertical Bar Gauge")) {
            diagnostics.add("${record.name}: canal RealDash sem correspondência no Speedroid.")
        }
        return Component("realdash-$index", painter, properties)
    }

    private fun inputId(data: Binary, start: Int, end: Int): Int? {
        // Newer exports delimit the input descriptor with a format-specific tag.
        for (offset in start + 116 until minOf(end - 3, start + 900)) {
            if (data.int(offset) in inputTags && data.int(offset - 16) == 4) {
                return data.int(offset - 12)
            }
        }
        // Earlier text instruments store the target ID in the legacy input block.
        return if (start + 128 <= end && data.int(start + 100) == 7 && data.int(start + 104) == 1)
            data.int(start + 124) else null
    }

    private fun needleRange(data: Binary, record: Instrument, end: Int): Pair<Double, Double>? {
        val start = record.text.end
        if (start + 160 > end || record.type != 1 || data.int(start + 100) != 9 || data.int(start + 104) != 6 ||
            data.int(start + 132) != 16) return null
        val minimum = data.float(start + 152).toDouble()
        val maximum = data.float(start + 156).toDouble()
        return if (minimum in -100_000.0..100_000.0 && maximum in -100_000.0..100_000.0 && maximum > minimum)
            minimum to maximum else null
    }

    private fun images(data: Binary, end: Int, names: Set<String>): Map<String, Resource> {
        val remaining = names.associateBy { it.lowercase(Locale.ROOT) }.toMutableMap()
        val resources = linkedMapOf<String, Resource>()
        var pixelsLeft = MAX_IMAGE_PIXELS
        var position = 12
        while (position < end && remaining.isNotEmpty()) {
            if (data.matches(position, pngTag) && data.bigInt(position + 12) == 0x49484452) {
                val size = data.int(position - 8)
                val width = data.bigInt(position + 16)
                val height = data.bigInt(position + 20)
                val pixels = width.toLong() * height
                if (size in 33..(end - position) && data.int(position - 4) == 0 &&
                    width in 1..4096 && height in 1..4096 && pixels <= pixelsLeft) {
                    for (length in 5..256) {
                        val name = data.string(position - 12 - 2 * length) ?: continue
                        if (name.end != position - 8) continue
                        val key = remaining.remove(name.value.lowercase(Locale.ROOT)) ?: continue
                        resources[key] = Resource(key, "png", data.bytes.copyOfRange(position, position + size))
                        pixelsLeft -= pixels
                        break
                    }
                    position += size
                    continue
                }
            }
            position++
        }
        return resources
    }

    private data class Instrument(val offset: Int, val type: Int, val visible: Boolean, val name: String, val image: String,
                                  val x: Float, val y: Float, val width: Float, val height: Float,
                                  val text: Field)
    private data class Field(val value: String, val end: Int)
    private data class Signal(val channel: String, val units: String, val minimum: Double, val maximum: Double,
                              val digits: Int = 0)

    // RealDash target IDs are public. Custom inputs have no implicit Speeduino channel.
    private val signals = mapOf(
        0 to Signal("afr", "AFR", 7.0, 22.0, 1), 2 to Signal("afrTarget", "AFR", 7.0, 22.0, 1),
        12 to Signal("batteryVoltage", "V", 0.0, 18.0, 1), 14 to Signal("coolant", "°C", -40.0, 150.0),
        24 to Signal("gpsSpeedKmh", "km/h", 0.0, 240.0), 27 to Signal("iat", "°C", -40.0, 120.0),
        31 to Signal("map", "kPa", 0.0, 250.0, 1), 37 to Signal("rpm", "rpm", 0.0, 8000.0),
        42 to Signal("tps", "%", 0.0, 100.0, 1), 64 to Signal("vss", "km/h", 0.0, 240.0),
        139 to Signal("gear", "", 0.0, 8.0), 200 to Signal("gear", "", 0.0, 8.0),
        254 to Signal("lambda", "", 0.5, 1.6, 2)
    )

    private class Binary(val bytes: ByteArray) {
        val size get() = bytes.size
        private val little = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        private val big = ByteBuffer.wrap(bytes)
        fun int(offset: Int) = if (offset in 0..size - 4) little.getInt(offset) else Int.MIN_VALUE
        fun bigInt(offset: Int) = if (offset in 0..size - 4) big.getInt(offset) else Int.MIN_VALUE
        fun short(offset: Int) = if (offset in 0..size - 2) little.getShort(offset).toInt() and 65535 else -1
        fun float(offset: Int) = Float.fromBits(int(offset))
        fun matches(offset: Int, token: ByteArray) =
            offset in 0..size - token.size && token.indices.all { bytes[offset + it] == token[it] }

        fun stringEnd(offset: Int, maxLength: Int): Int? {
            val length = int(offset)
            return if (length in 0..maxLength && offset + 4 + length * 2 <= size) offset + 4 + length * 2 else null
        }

        fun string(offset: Int): Field? {
            val end = stringEnd(offset, 512) ?: return null
            val value = String(bytes, offset + 4, end - offset - 4, Charsets.UTF_16LE)
            if (value.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' } || '\uFFFD' in value) return null
            return Field(value, end)
        }
    }
}
