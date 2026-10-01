package com.github.monun.invcaptive.plugin

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * 인벤토리 칸을 해제한 블록 기록 (해제 순서대로).
 * /invcaptive 로 다시 잠그면 초기화된다.
 */
object ReleaseLog {
    data class Entry(val slot: Int, val block: Material, val player: String)

    private val entries = ArrayList<Entry>()

    fun add(entry: Entry) {
        entries += entry
    }

    fun clear() {
        entries.clear()
    }

    fun all(): List<Entry> = entries.toList()

    fun load(file: File) {
        entries.clear()
        if (!file.exists()) return

        val yaml = YamlConfiguration.loadConfiguration(file)

        for (map in yaml.getMapList("entries")) {
            val slot = (map["slot"] as? Number)?.toInt() ?: continue
            val block = (map["block"] as? String)?.let { Material.matchMaterial(it) } ?: continue
            val player = map["player"] as? String ?: continue

            entries += Entry(slot, block, player)
        }
    }

    fun save(file: File) {
        val yaml = YamlConfiguration()

        yaml.set(
            "entries",
            entries.map { mapOf("slot" to it.slot, "block" to it.block.name, "player" to it.player) }
        )

        file.parentFile?.mkdirs()
        yaml.save(file)
    }
}
