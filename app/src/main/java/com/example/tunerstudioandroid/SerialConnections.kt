package com.example.tunerstudioandroid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import org.clonetuner.comm.SerialIo
import java.io.IOException
import java.util.Timer
import java.util.TimerTask
import java.util.UUID

internal class UsbReconnectTarget(port: UsbSerialPort, val baud: Int, val enableDtr: Boolean) {
    private val original = port.driver.device
    private val serial = serialNumber(original)
    val portNumber = port.portNumber
    var armed = false
    var attachment: String? = original.deviceName
        private set
    var attempts = 0
    var permissionDenied = false
    var nextAttemptAt = 0L

    fun findDevice(manager: UsbManager): UsbDevice? {
        val matches = manager.deviceList.values.filter {
            it.vendorId == original.vendorId && it.productId == original.productId
        }
        return if (serial == null) matches.singleOrNull() else {
            matches.filter { manager.hasPermission(it) && serialNumber(it) == serial }.singleOrNull()
                ?: matches.singleOrNull()?.takeUnless { manager.hasPermission(it) }
        }
    }

    fun updateAttachment(device: UsbDevice?) {
        val name = device?.deviceName
        if (name == attachment) return
        attachment = name
        attempts = 0
        permissionDenied = false
        nextAttemptAt = 0
    }

    private fun serialNumber(device: UsbDevice): String? =
        runCatching { device.serialNumber }.getOrNull()?.takeIf { it.isNotBlank() }
}

/** Classic Bluetooth SPP (e.g. HC-05/HC-06), paired in Android settings first. */
@SuppressLint("MissingPermission")
class BluetoothSerial(private val device: BluetoothDevice) : SerialIo {
    @Volatile private var socket: BluetoothSocket? = null
    override fun description() = "Bluetooth · ${device.name ?: device.address}"
    override fun open() {
        val candidate = device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"))
        socket = candidate
        val timeout = Timer("bluetooth-connect-timeout", true)
        timeout.schedule(object : TimerTask() { override fun run() { runCatching { candidate.close() } } }, 12000)
        try { candidate.connect() } catch (e: Exception) { close(); throw e } finally { timeout.cancel() }
    }
    override fun isOpen() = socket?.isConnected == true
    override fun read(buffer: ByteArray, count: Int): Int {
        try {
            val input = socket?.inputStream ?: throw IOException("Bluetooth desconectado")
            val available = input.available()
            if (available == 0) { Thread.sleep(5); return 0 }
            return input.read(buffer, 0, minOf(count, available)).also { if (it < 0) throw IOException("Conexão encerrada") }
        } catch (e: Exception) { throw IllegalStateException("Leitura Bluetooth: ${e.message}", e) }
    }
    override fun write(buffer: ByteArray): Int {
        try {
            val output = socket?.outputStream ?: throw IOException("Bluetooth desconectado")
            output.write(buffer); output.flush(); return buffer.size
        } catch (e: Exception) { throw IllegalStateException("Envio Bluetooth: ${e.message}", e) }
    }
    override fun close() { runCatching { socket?.close() }; socket = null }
}

class UsbSerial(
    private val manager: UsbManager,
    private val port: UsbSerialPort,
    private val baud: Int,
    private val enableDtr: Boolean = false
) : SerialIo {
    @Volatile private var opened = false
    private var connection: android.hardware.usb.UsbDeviceConnection? = null
    // Read whole USB packets, including space for the status bytes used by FTDI.
    private val receiveBuffer = ByteArray(4096)
    private var receiveOffset = 0
    private var receiveSize = 0
    override fun description() = "USB · ${port.driver.javaClass.simpleName} · porta ${port.portNumber + 1} · $baud baud"
    @Synchronized override fun open() {
        val device = port.driver.device
        if (manager.deviceList[device.deviceName] == null) throw IOException("Dispositivo USB desconectado")
        if (!manager.hasPermission(device)) throw IOException("Sem permissão para USB")
        val link = manager.openDevice(device) ?: throw IOException("Não foi possível abrir o dispositivo USB")
        connection = link
        try {
            port.open(link)
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            if (enableDtr) port.setDTR(true)
            receiveOffset = 0
            receiveSize = 0
            opened = true
        } catch (e: Exception) { close(); throw e }
    }
    override fun isOpen() = opened && port.isOpen
    override fun read(buffer: ByteArray, count: Int): Int {
        try {
            if (count == 0) return 0
            if (!opened) throw IOException("Dispositivo USB desconectado")
            if (receiveOffset == receiveSize) {
                receiveSize = port.read(receiveBuffer, 150)
                receiveOffset = 0
            }
            val bytes = minOf(count, receiveSize - receiveOffset)
            receiveBuffer.copyInto(buffer, 0, receiveOffset, receiveOffset + bytes)
            receiveOffset += bytes
            return bytes
        } catch (e: Exception) { opened = false; throw IllegalStateException("Leitura USB: ${e.message}", e) }
    }
    override fun write(buffer: ByteArray): Int {
        try { port.write(buffer, 1500); return buffer.size }
        catch (e: Exception) { opened = false; throw IllegalStateException("Envio USB: ${e.message}", e) }
    }
    @Synchronized override fun close() {
        opened = false
        runCatching { port.close() }
        connection?.close()
        connection = null
    }
}
