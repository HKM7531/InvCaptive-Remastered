package com.github.monun.invcaptive.plugin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.io.File
import kotlin.math.min

/**
 * 모든 플레이어가 공유하는 인벤토리.
 *
 * NMS 없이 Bukkit API만 사용한다. 서버가 기준(canonical) 인벤토리를 하나 들고 있고,
 * 매 틱마다 (1) 플레이어 인벤토리의 변경을 기준 인벤토리로 모으고(pull)
 * (2) 기준 인벤토리를 모든 플레이어에게 복제한다(push).
 *
 * 슬롯 번호는 Bukkit PlayerInventory 기준이다.
 * 0..35 = 일반 칸, 36..39 = 방어구(신발 -> 투구), 40 = 보조 손
 */
object SharedInventory {
    const val SIZE = 41

    /** 봉인의 원인이 된 사망 정보 (Lore 에 표시) */
    data class DeathInfo(val playerName: String, val time: String, val message: Component?)

    private val slots = arrayOfNulls<ItemStack>(SIZE)

    /** false 이면 공유와 잠금이 종료된 상태 (/invcaptive stop). /invcaptive 로 다시 시작한다. */
    var active: Boolean = true
        private set

    private val sealKey = NamespacedKey("invcaptive", "seal_condition")

    fun isBarrier(item: ItemStack?): Boolean = item != null && item.type == Material.BARRIER

    /** 사망 페널티로 봉인된 칸의 표시 아이템(구조물 공허) */
    fun isSeal(item: ItemStack?): Boolean = sealConditionOf(item) != null

    /** 장벽이든 봉인이든 잠긴 칸의 표시 아이템 */
    fun isLockItem(item: ItemStack?): Boolean = isBarrier(item) || isSeal(item)

    /** 봉인 아이템에 기록된 해제 조건 id. 봉인 아이템이 아니면 null */
    fun sealConditionOf(item: ItemStack?): String? {
        if (item == null || item.type != Material.STRUCTURE_VOID) return null
        return item.itemMeta?.persistentDataContainer?.get(sealKey, PersistentDataType.STRING)
    }

    /** 해당 칸이 아직 장벽이나 봉인으로 잠겨 있는지 */
    fun isLocked(slot: Int): Boolean = slot in 0 until SIZE && isLockItem(slots[slot])

    /** 해당 칸이 장벽으로 잠겨 있는지 (봉인은 해당 없음) */
    fun isBarrierSlot(slot: Int): Boolean = slot in 0 until SIZE && isBarrier(slots[slot])

    fun itemAt(slot: Int): ItemStack? = slots.getOrNull(slot)

    /** 봉인된 칸 -> 해제 조건 id */
    fun sealedSlots(): Map<Int, String> {
        val result = LinkedHashMap<Int, String>()
        for (i in 0 until SIZE) sealConditionOf(slots[i])?.let { result[i] = it }
        return result
    }

    /** 잠긴 칸을 뺀 공유 인벤토리에 해당 아이템이 있는지 */
    fun holds(type: Material): Boolean = slots.any { it != null && it.type == type && !isLockItem(it) }

    // ---------------------------------------------------------------- sync

    /** 매 틱 호출: 플레이어 변경 수집 후 전원에게 반영 */
    fun sync() {
        if (!active) return

        val players = Bukkit.getOnlinePlayers()
        pull(players)
        push(players)
    }

    /**
     * 기준 인벤토리를 프로그램에서 직접 바꿀 때는 반드시 이 함수를 쓴다.
     * 먼저 아직 반영되지 않은 플레이어 변경을 수집하고, 바꾼 뒤 바로 전원에게 반영한다.
     */
    fun <T> mutate(action: () -> T): T {
        val players = Bukkit.getOnlinePlayers()
        pull(players)
        val result = action()
        push(players)
        return result
    }

    /** 플레이어 인벤토리 -> 기준 인벤토리 */
    fun pull(players: Collection<out Player>) {
        if (!active || players.isEmpty()) return

        val base = slots.copyOf()
        val claimed = BooleanArray(SIZE)

        for (player in players) {
            val inventory = player.inventory

            for (slot in 0 until SIZE) {
                val mine = inventory.getItem(slot).normalized()
                if (same(mine, base[slot])) continue

                if (!claimed[slot]) {
                    slots[slot] = mine?.clone()
                    claimed[slot] = true
                } else if (!same(mine, slots[slot])) {
                    // 같은 틱에 두 명 이상이 같은 칸을 서로 다르게 바꾼 경우
                    resolveConflict(player, base[slot], mine, slot)
                }
            }
        }
    }

    /** 기준 인벤토리 -> 플레이어 인벤토리 */
    fun push(players: Collection<out Player>) {
        for (player in players) applyTo(player)
    }

    /** 접속 직후처럼 한 명에게만 기준 인벤토리를 덮어쓸 때 */
    fun applyTo(player: Player) {
        if (!active) return

        val inventory = player.inventory

        for (slot in 0 until SIZE) {
            val target = slots[slot]
            if (!same(inventory.getItem(slot).normalized(), target)) {
                inventory.setItem(slot, target?.clone())
            }
        }
    }

    /**
     * 늦게 변경한 쪽이 덮어써지면서 아이템이 사라지거나 복제되지 않도록 처리한다.
     * - 빈 칸에 새 아이템을 넣은 경우: 그 아이템을 플레이어 위치에 드롭
     * - 같은 아이템을 더 주운 경우: 늘어난 만큼만 합치고 넘치면 드롭
     * - 그 외(꺼내기, 이동 등): 변경을 무시
     */
    private fun resolveConflict(player: Player, base: ItemStack?, mine: ItemStack?, slot: Int) {
        if (mine == null || isLockItem(mine)) return

        if (base == null) {
            drop(player, mine.clone())
            return
        }

        if (mine.isSimilar(base) && mine.amount > base.amount) {
            val extra = mine.amount - base.amount
            val current = slots[slot]

            if (current != null && current.isSimilar(base)) {
                val add = min(current.maxStackSize - current.amount, extra)
                current.amount += add
                if (extra > add) drop(player, base.clone().apply { amount = extra - add })
            } else {
                drop(player, base.clone().apply { amount = extra })
            }
        }
    }

    private fun drop(player: Player, item: ItemStack) {
        player.world.dropItemNaturally(player.location, item)
    }

    // ---------------------------------------------------------------- game rules

    /** /invcaptive : 공유를 (다시) 시작하고 전체 칸을 장벽으로 채운 뒤 핫바 첫 칸만 비운다 */
    fun captive() {
        active = true

        // 전부 덮어쓰므로 플레이어 변경을 먼저 수집(pull)할 필요가 없다
        for (i in 0 until SIZE) slots[i] = ItemStack(Material.BARRIER)
        slots[0] = null

        val players = Bukkit.getOnlinePlayers()
        push(players)

        for (player in players) player.updateInventory()
    }

    /**
     * /invcaptive stop : 장벽을 모두 치우고 공유를 끝낸다.
     * 종료 시점의 공유 인벤토리 내용이 모든 플레이어에게 그대로 남는다.
     */
    fun stop() {
        mutate {
            for (i in 0 until SIZE) {
                if (isLockItem(slots[i])) slots[i] = null
            }
        }

        active = false

        for (player in Bukkit.getOnlinePlayers()) player.updateInventory()
    }

    /** 해당 칸이 장벽이면 "새로운 인벤토리" 황금 사과로 바꾸고 true */
    fun release(slot: Int): Boolean = mutate {
        if (slot !in 0 until SIZE || !isBarrier(slots[slot])) {
            false
        } else {
            slots[slot] = releaseMarker()
            true
        }
    }

    /** 봉인 수가 (장벽이 아닌 칸 = 열린 칸 + 봉인된 칸)의 절반에 닿아 더 봉인하면 절반 이상이 되는지 */
    fun sealLimitReached(): Boolean {
        val pool = (0 until SIZE).count { !isBarrier(slots[it]) }
        return (sealedSlots().size + 1) * 2 >= pool
    }

    /**
     * 사망 페널티: 핫바 1번 칸(0)을 제외한 잠기지 않은 칸 하나를 골라 봉인한다. 봉인한 칸 번호(없으면 null).
     * 칸에 아이템이 남아 있으면 사라지므로, 사망 시 아이템을 먼저 꺼낸 뒤 호출해야 한다.
     */
    fun sealRandomSlot(death: DeathInfo, conditionId: (Set<String>) -> SealCondition?): Int? = mutate {
        val candidates = (1 until SIZE).filter { !isLockItem(slots[it]) }
        if (candidates.isEmpty()) return@mutate null

        val condition = conditionId(sealedSlots().values.toSet()) ?: return@mutate null
        val slot = candidates.random()

        slots[slot] = sealItem(condition, death)
        slot
    }

    /** 봉인된 칸을 "새로운 인벤토리" 황금 사과로 바꾼다. 봉인 칸이 아니면 false */
    fun unseal(slot: Int): Boolean = mutate {
        if (slot in 0 until SIZE && isSeal(slots[slot])) {
            slots[slot] = releaseMarker()
            true
        } else {
            false
        }
    }

    private fun sealItem(condition: SealCondition, death: DeathInfo): ItemStack = ItemStack(Material.STRUCTURE_VOID).apply {
        editMeta { meta ->
            meta.displayName(Component.text("봉인된 인벤토리", NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false))
            val lore = ArrayList<Component>()
            lore += (death.message ?: Component.text("${death.playerName}이(가) 죽었습니다."))
                .colorIfAbsent(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)
            lore += Component.text(death.time, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            lore += Component.empty()
            lore += Component.text("봉인 해제 조건", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            lore += Component.text().append(condition.description.color(NamedTextColor.YELLOW))
                .decoration(TextDecoration.ITALIC, false).build()

            meta.lore(lore)
            meta.persistentDataContainer.set(sealKey, PersistentDataType.STRING, condition.id)
        }
    }

    /** 사망 시: 장벽을 제외한 모든 아이템을 꺼내 반환하고 기준 인벤토리에서 제거 */
    fun takeAllExceptBarriers(): List<ItemStack> = mutate {
        val taken = ArrayList<ItemStack>()

        for (i in 0 until SIZE) {
            val item = slots[i] ?: continue
            if (isLockItem(item)) continue

            taken += item
            slots[i] = null
        }

        taken
    }

    private fun releaseMarker(): ItemStack = ItemStack(Material.GOLDEN_APPLE).apply {
        editMeta { meta ->
            meta.displayName(Component.text("새로운 인벤토리", NamedTextColor.GOLD))
        }
    }

    // ---------------------------------------------------------------- persistence

    fun load(file: File) {
        if (!file.exists()) return

        val yaml = YamlConfiguration.loadConfiguration(file)

        active = yaml.getBoolean("active", true)

        for (i in 0 until SIZE) {
            slots[i] = yaml.getItemStack("slots.$i").normalized()
        }
    }

    fun save(file: File) {
        mutate { } // 마지막 변경까지 수집

        val yaml = YamlConfiguration()

        yaml.set("active", active)

        for (i in 0 until SIZE) {
            slots[i]?.let { yaml.set("slots.$i", it) }
        }

        file.parentFile?.mkdirs()
        yaml.save(file)
    }

    // ---------------------------------------------------------------- helpers

    private fun ItemStack?.normalized(): ItemStack? {
        if (this == null || type.isAir || amount <= 0) return null
        return this
    }

    private fun same(a: ItemStack?, b: ItemStack?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return a.amount == b.amount && a.isSimilar(b)
    }
}
