package com.github.monun.invcaptive.plugin

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * 플레이어가 파괴한 블록 종류와, 그중 인벤토리 칸 해제에 성공한 블록의 기록.
 * /invcaptive 로 다시 잠그면 초기화된다.
 */
object BlockLog {
    data class Release(val slot: Int, val player: String)

    private val broken = LinkedHashSet<Material>()
    private val releases = HashMap<Material, Release>()

    fun recordBreak(type: Material) {
        broken += type
    }

    fun recordRelease(type: Material, slot: Int, player: String) {
        broken += type
        releases[type] = Release(slot, player)
    }

    fun clear() {
        broken.clear()
        releases.clear()
    }

    fun brokenBlocks(): List<Material> = broken.toList()

    fun isBroken(type: Material): Boolean = type in broken

    fun releaseOf(type: Material): Release? = releases[type]

    fun load(file: File) {
        clear()
        if (!file.exists()) return

        val yaml = YamlConfiguration.loadConfiguration(file)

        for (name in yaml.getStringList("blocks")) {
            Material.matchMaterial(name)?.let { broken += it }
        }

        for (map in yaml.getMapList("releases")) {
            val type = (map["block"] as? String)?.let { Material.matchMaterial(it) } ?: continue
            val slot = (map["slot"] as? Number)?.toInt() ?: continue
            val player = map["player"] as? String ?: continue

            broken += type
            releases[type] = Release(slot, player)
        }
    }

    fun save(file: File) {
        val yaml = YamlConfiguration()

        yaml.set("blocks", broken.map { it.name })
        yaml.set(
            "releases",
            releases.map { (type, release) ->
                mapOf("block" to type.name, "slot" to release.slot, "player" to release.player)
            }
        )

        file.parentFile?.mkdirs()
        yaml.save(file)
    }
}
