package com.example.tunerstudioandroid.dashboard

import android.content.Context
import org.clonetuner.dashboard.DashboardDefinition
import org.clonetuner.dashboard.DashboardReader
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import com.example.tunerstudioandroid.dashboard.vehicle.VehicleModel
import java.util.concurrent.CopyOnWriteArrayList

class DashboardStore(context: Context) {
    data class Entry(val id: String, val label: String, val definition: DashboardDefinition, val vehicle: VehicleModel? = null) {
        val isRealDash get() = id.endsWith(".rd")
    }
    private val app=context.applicationContext
    private val folder=File(app.filesDir,"dashboards").apply { mkdirs() }
    val entries=CopyOnWriteArrayList<Entry>()
    var selectedId: String
        get()=app.getSharedPreferences("dashboards",Context.MODE_PRIVATE).getString("selected", "Speeduino_Sweep.dash")!!
        set(value) { app.getSharedPreferences("dashboards",Context.MODE_PRIVATE).edit().putString("selected",value).apply() }
    init {
        for(name in listOf("Speeduino_Sweep.dash","Speeduino_LED.dash","Speeduino_LED_Amber.dash")) {
            val file=File(folder,name)
            if(!file.exists()) app.assets.open("dashboards/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
            entries.add(Entry(name,name.removePrefix("Speeduino_").removeSuffix(".dash").replace('_',' '),DashboardReader.read(file.toPath())))
        }
        VehicleModel.entries.forEach { model ->
            entries.add(Entry(model.id,model.label,DashboardDefinition(model.label,"",emptyMap(),emptyList(),emptyMap(),emptyList()),model))
        }
        folder.listFiles { file -> file.name.startsWith("import-") && file.extension in setOf("dash", "rd") }?.sortedBy { it.name }?.forEach { file ->
            runCatching {
                entries.add(importedEntry(file.name, read(file, file.extension == "rd")))
            }
        }
    }

    fun import(file: File, displayName: String): Entry {
        val realDash = RealDashReader.recognizes(file) || displayName.substringAfterLast('.').lowercase() in setOf("rd", "rdash")
        val definition = read(file, realDash)
        require(definition.components().isNotEmpty()) { "O arquivo não contém instrumentos" }
        val digest=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).take(8).joinToString("") { "%02x".format(it) }
        val safe = displayName.substringAfterLast('/').replace(Regex("(?i)\\.(dash|rd|rdash)$"), "")
            .replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").trim().take(90).ifBlank { "Painel" }
        val id = "import-$digest-$safe.${if (realDash) "rd" else "dash"}"
        val temp = File.createTempFile("pending-", ".tmp", folder)
        try {
            file.copyTo(temp, overwrite = true)
            Files.move(temp.toPath(), File(folder, id).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
        val entry = importedEntry(id, definition)
        entries.removeAll { it.id==id }; entries.add(entry); selectedId=id
        return entry
    }

    private fun read(file: File, realDash: Boolean) =
        if (realDash) RealDashReader.read(file) else DashboardReader.read(file.toPath())

    private fun importedEntry(id: String, parsed: DashboardDefinition): Entry {
        val name = id.replaceFirst(Regex("^import-[a-f0-9]{16}-"), "")
        val definition = DashboardDefinition(name, parsed.firmwareSignature(), parsed.clusterProperties(),
            parsed.components(), parsed.resources(), parsed.diagnostics())
        return Entry(id, name.substringBeforeLast('.'), definition)
    }
}
