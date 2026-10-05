package com.github.monun.invcaptive.plugin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.EntityType

/** 봉인 해제 조건. id 는 봉인 아이템에 저장된다 (obtain:MATERIAL / kill:ENTITY_TYPE) */
data class SealCondition(val id: String, val description: Component)

object SealConditions {
    private val OBTAIN = listOf(
        "SCULK_CATALYST", "TRIDENT", "HEART_OF_THE_SEA", "NETHER_STAR", "ELYTRA", "TOTEM_OF_UNDYING",
        "ENCHANTED_GOLDEN_APPLE", "BEACON", "DRAGON_EGG", "SHULKER_SHELL", "NAUTILUS_SHELL", "DRAGON_HEAD",
        "ECHO_SHARD", "RECOVERY_COMPASS", "OMINOUS_TRIAL_KEY", "HEAVY_CORE", "MACE", "WITHER_SKELETON_SKULL",
        "CONDUIT", "SPONGE", "ENCHANTED_BOOK", "DIAMOND_BLOCK", "NETHERITE_INGOT", "ANCIENT_DEBRIS",
        "SCULK_SHRIEKER", "SCULK_SENSOR", "BLAZE_ROD", "ENDER_EYE", "GHAST_TEAR", "PHANTOM_MEMBRANE"
    ).mapNotNull { runCatching { Material.valueOf(it) }.getOrNull() }

    private val KILL = listOf("WITHER", "ENDER_DRAGON", "ELDER_GUARDIAN", "WARDEN", "RAVAGER", "EVOKER")
        .mapNotNull { runCatching { EntityType.valueOf(it) }.getOrNull() }

    fun obtain(type: Material) = SealCondition(
        "obtain:${type.name}",
        Component.text().append(Component.translatable(type.translationKey())).append(Component.text(" 획득")).build()
    )

    fun kill(type: EntityType) = SealCondition(
        "kill:${type.name}",
        Component.text().append(Component.translatable(type.translationKey())).append(Component.text(" 처치")).build()
    )

    /**
     * 블록 파괴. 어떤 블록인지는 Lore 에서 가려진다 (obfuscated). 가린 글자 수는 한국어 이름의 공백 제외 글자 수와 같다.
     * 봉인할 때 이미 캔 블록이면 빨간색으로 표시한다.
     */
    fun breakBlock(type: Material): SealCondition {
        val length = BlockNames.koreanName(type)?.count { !it.isWhitespace() }?.takeIf { it > 0 } ?: 3
        val red = BlockLog.isBroken(type)

        fun part(text: Component) = if (red) text.color(NamedTextColor.RED) else text

        return SealCondition(
            "break:${type.name}",
            Component.text()
                .append(part(Component.text("?".repeat(length)).decorate(TextDecoration.OBFUSCATED)))
                .append(part(Component.text(" 파괴")))
                .build()
        )
    }

    /**
     * 무작위 조건 하나. 획득 / 처치 / 안 캔 블록 파괴 중 종류를 먼저 고른다.
     * 이미 쓰이는 조건은 피하고, 지금 공유 인벤토리에 이미 있는 아이템은 고르지 않는다.
     */
    fun random(inUse: Set<String>, unbroken: List<Material>): SealCondition? {
        val kinds = listOf(
            OBTAIN.filter { !SharedInventory.holds(it) }.map { obtain(it) },
            KILL.map { kill(it) },
            unbroken.map { breakBlock(it) }
        ).filter { it.isNotEmpty() }

        val pool = kinds.randomOrNull() ?: return null

        return pool.filter { it.id !in inUse }.ifEmpty { pool }.random()
    }

    fun brokenType(id: String): Material? =
        if (id.startsWith("break:")) Material.matchMaterial(id.removePrefix("break:")) else null

    fun obtainedMaterial(id: String): Material? =
        if (id.startsWith("obtain:")) Material.matchMaterial(id.removePrefix("obtain:")) else null

    fun killedType(id: String): EntityType? =
        if (id.startsWith("kill:")) runCatching { EntityType.valueOf(id.removePrefix("kill:")) }.getOrNull() else null
}
