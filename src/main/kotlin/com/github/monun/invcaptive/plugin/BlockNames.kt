package com.github.monun.invcaptive.plugin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Material
import org.bukkit.plugin.java.JavaPlugin
import java.io.InputStreamReader
import java.text.Collator
import java.util.Locale
import java.util.Properties

/**
 * 블록의 한국어 이름 테이블. 화면 표시는 클라이언트 언어 번역을 쓰고,
 * 이 테이블은 가나다순 정렬에만 사용한다. (리소스: ko_kr_blocks.properties)
 */
object BlockNames {
    private val names = HashMap<String, String>()
    private val collator: Collator = Collator.getInstance(Locale.KOREAN)

    fun load(plugin: JavaPlugin) {
        val stream = plugin.getResource("ko_kr_blocks.properties") ?: return

        val props = Properties()
        InputStreamReader(stream, Charsets.UTF_8).use { props.load(it) }

        for (key in props.stringPropertyNames()) {
            names[key] = props.getProperty(key)
        }
    }

    private fun ownKey(type: Material) = "block.minecraft.${type.name.lowercase()}"

    /**
     * 벽 머리·벽 현수막·벽 횃불 같은 벽 블록은 마인크래프트의 번역 키가 일반 블록과 같아서
     * (예: 플레이어 벽 머리 -> 플레이어 머리) 구분이 안 된다. 그래서 Material 이름으로 먼저 찾는다.
     */
    fun koreanName(type: Material): String? =
        names[ownKey(type)] ?: type.blockTranslationKey?.let { names[it] }

    /** 화면 표시용 이름. 번역 키가 다른 블록과 겹치는 경우에는 고정 한국어 이름을 쓴다. */
    fun displayName(type: Material, color: TextColor): Component {
        val own = names[ownKey(type)]
        val key = type.blockTranslationKey

        return if (own != null && key != ownKey(type)) {
            Component.text(own, color)
        } else {
            Component.translatable(key ?: type.name, color)
        }
    }

    /** 한국어 이름 가나다순. 이름 테이블에 없는 블록은 맨 뒤에 영문 이름순으로 둔다. */
    val comparator: Comparator<Material> = Comparator { a, b ->
        val nameA = koreanName(a)
        val nameB = koreanName(b)

        when {
            nameA != null && nameB != null -> {
                val result = collator.compare(nameA, nameB)
                if (result != 0) result else a.name.compareTo(b.name)
            }
            nameA != null -> -1
            nameB != null -> 1
            else -> a.name.compareTo(b.name)
        }
    }
}
