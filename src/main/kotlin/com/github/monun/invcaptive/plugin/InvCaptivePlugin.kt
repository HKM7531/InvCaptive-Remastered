package com.github.monun.invcaptive.plugin

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.time.Duration
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Firework
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.world.WorldSaveEvent
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.EnumMap
import kotlin.random.Random

/**
 * 불편한 동거 (InvCaptive)
 *
 * 모든 플레이어가 인벤토리를 공유한다. /invcaptive 로 인벤토리가 장벽으로 잠기고,
 * 시드로 정해진 블록을 파괴할 때마다 칸이 하나씩 해제된다.
 *
 * Original concept and event logic: Noonmaru (GPL-3.0)
 */
class InvCaptivePlugin : JavaPlugin(), Listener {

    private companion object {
        const val PERM_ADMIN = "invcaptive.command"
        const val PERM_BLOCKS = "invcaptive.blocks"
        const val PAGE_SIZE = 20
        const val NAV_BUTTON_WIDTH = 70
        const val EXCLUDED_FILE_NAME = "excluded-blocks.txt"
        const val EXCLUDED_FILE_MARKER = "InvCaptive 제외 블록 목록"

        /**
         * 슬롯 대응에서 제외하는 블록 (플러그인에 내장, 서버 값으로 계산하지 않음. Material 이름 기준)
         * 서바이벌에서 부술 수 없는 블록들이다.
         */
        val BUILTIN_EXCLUDED = setOf(
            // 경도 -1 (파괴 불가)
            "BEDROCK", "BARRIER", "LIGHT", "MOVING_PISTON",
            "COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK",
            "STRUCTURE_BLOCK", "JIGSAW", "TEST_BLOCK", "TEST_INSTANCE_BLOCK",
            "END_PORTAL", "END_PORTAL_FRAME", "END_GATEWAY", "NETHER_PORTAL",
            // 경도는 있지만 선택해서 부술 수 없는 블록
            "WATER", "LAVA", "BUBBLE_COLUMN", "STRUCTURE_VOID",
            // 서바이벌에서 마주치지 않는 블록
            "PETRIFIED_OAK_SLAB", "PLAYER_HEAD", "PLAYER_WALL_HEAD",
            // 공기 종류
            "AIR", "CAVE_AIR", "VOID_AIR"
        )
    }

    private lateinit var slotsByType: EnumMap<Material, Int>

    private val inventoryFile: File
        get() = File(dataFolder, "inventory.yml")

    private val blocksFile: File
        get() = File(dataFolder, "broken-blocks.yml")

    override fun onEnable() {
        BlockNames.load(this)
        loadExcluded()

        val seed = loadSeed()
        slotsByType = createSlotMap(seed)

        SharedInventory.load(inventoryFile)
        BlockLog.load(blocksFile)
        server.pluginManager.registerEvents(this, this)

        // 이미 접속해 있는 플레이어(리로드 등)에게 공유 인벤토리 적용
        SharedInventory.push(Bukkit.getOnlinePlayers())

        server.scheduler.runTaskTimer(this, Runnable { SharedInventory.sync() }, 1L, 1L)
    }

    override fun onDisable() {
        saveAll()
    }

    private fun saveAll() {
        SharedInventory.save(inventoryFile)
        BlockLog.save(blocksFile)
    }

    private val configFile: File
        get() = File(dataFolder, "config.yml")

    private val excludedFile: File
        get() = File(dataFolder, EXCLUDED_FILE_NAME)

    /** 슬롯 대응에서 제외된 블록. excluded-blocks.txt 에서 서버 시작 시 읽는다. */
    private var excludedSet: Set<Material> = emptySet()

    private fun loadSeed(): Long {
        dataFolder.mkdirs()
        val file = configFile
        val config = YamlConfiguration()

        if (file.exists()) config.load(file)

        if (!config.contains("seed")) {
            config.set("seed", Random.nextLong())
            config.save(file)
        }

        return config.getLong("seed")
    }

    // ---------------------------------------------------------------- excluded-blocks.txt

    /**
     * plugins/InvCaptive/excluded-blocks.txt 를 읽는다. 없으면(또는 예전 형식이면) 내장 제외 목록으로 새로 만든다.
     * 한 줄에 하나(또는 쉼표로 구분)로 Material 이름을 적고, # 뒤는 주석이다.
     */
    private fun loadExcluded() {
        dataFolder.mkdirs()

        val file = excludedFile
        val text = if (file.exists()) runCatching { file.readText(Charsets.UTF_8) }.getOrNull() else null

        if (text == null || !text.contains(EXCLUDED_FILE_MARKER)) {
            val defaults = BUILTIN_EXCLUDED.mapNotNull { runCatching { Material.valueOf(it) }.getOrNull() }.toSet()
            runCatching { writeExcludedFile(defaults) }
                .onFailure { logger.warning("$EXCLUDED_FILE_NAME 생성 실패: ${it.message}") }
            excludedSet = defaults
            return
        }

        excludedSet = parseExcluded(text)
    }

    private fun parseExcluded(text: String): Set<Material> {
        val result = LinkedHashSet<Material>()

        for (rawLine in text.lines()) {
            val line = rawLine.substringBefore('#')

            for (token in line.split(',', ' ', '\t')) {
                val name = token.trim()
                if (name.isEmpty()) continue

                val type = Material.matchMaterial(name)

                if (type == null || type.isLegacy || !type.isBlock) {
                    logger.warning("$EXCLUDED_FILE_NAME: 알 수 없는 블록 이름 무시: $name")
                } else {
                    result += type
                }
            }
        }

        return result
    }

    private fun writeExcludedFile(blocks: Set<Material>) {
        val lines = ArrayList<String>()

        lines += "# $EXCLUDED_FILE_MARKER"
        lines += "# 슬롯 대응(칸 해제 블록)에서 제외할 블록의 Material 이름을 한 줄에 하나씩(또는 쉼표로 구분해) 적습니다."
        lines += "# 줄을 지우면 제외가 풀리고, 이름을 추가하면 제외됩니다. # 뒤는 주석입니다."
        lines += "# 수정 후 서버를 재시작해야 적용되며, 적용되면 슬롯 대응이 바뀌므로 /invcaptive 로 다시 시작하세요."
        lines += "# 이 파일을 지우면 내장 기본 목록으로 다시 만들어집니다."
        lines += ""

        for (type in blocks.sortedBy { it.name }) {
            val korean = BlockNames.koreanName(type)
            lines += if (korean != null) "${type.name}  # $korean" else type.name
        }

        excludedFile.parentFile?.mkdirs()
        excludedFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    /** /invcaptive exclude [list | add <블록> | remove <블록>] : 제외 파일을 편집. 적용은 서버 재시작 후. */
    private fun handleExclude(sender: CommandSender, label: String, args: Array<out String>) {
        val action = args.getOrNull(1)?.lowercase()

        if (action == null || action == "list") {
            sendExcludedBlocks(sender)
            sender.sendMessage(Component.text("/$label exclude add|remove <블록> 또는 plugins/InvCaptive/$EXCLUDED_FILE_NAME 직접 편집 (재시작 후 적용)", NamedTextColor.GRAY))
            return
        }

        if (action != "add" && action != "remove") {
            sender.sendMessage(Component.text("사용법: /$label exclude [list | add <블록> | remove <블록>]", NamedTextColor.RED))
            return
        }

        val type = args.getOrNull(2)?.let { Material.matchMaterial(it) }

        if (type == null || type.isLegacy || !type.isBlock) {
            sender.sendMessage(Component.text("블록 이름을 정확히 입력하세요. 예: $label exclude add sculk_sensor", NamedTextColor.RED))
            return
        }

        // 파일을 직접 고친 내용도 반영하도록 파일에서 다시 읽는다
        val current = runCatching { parseExcluded(excludedFile.readText(Charsets.UTF_8)) }.getOrDefault(excludedSet).toMutableSet()

        val changed = if (action == "add") current.add(type) else current.remove(type)

        if (!changed) {
            sender.sendMessage(Component.text(
                if (action == "add") "이미 제외 목록에 있습니다." else "제외 목록에 없는 블록입니다.",
                NamedTextColor.YELLOW
            ))
            return
        }

        runCatching { writeExcludedFile(current) }
            .onFailure {
                sender.sendMessage(Component.text("파일 저장에 실패했습니다: ${it.message}", NamedTextColor.RED))
                return
            }

        sender.sendMessage(
            Component.text()
                .append(Component.text(if (action == "add") "제외 목록에 추가: " else "제외 목록에서 삭제: ", NamedTextColor.GREEN))
                .append(BlockNames.displayName(type, NamedTextColor.WHITE))
                .append(Component.text(" — 서버 재시작 후 적용됩니다. 적용되면 슬롯 대응이 바뀌므로 /$label 로 다시 시작하세요.", NamedTextColor.GRAY))
                .build()
        )
    }

    /**
     * 슬롯 대응 후보 여부. 제외 목록은 excluded-blocks.txt (기본값은 내장 BUILTIN_EXCLUDED)로만 정해지며,
     * 서버(경도 등)에서 계산하지 않는다.
     * (해제 조건은 아이템 획득이 아니라 파괴)
     */
    private fun isSurvivalBreakable(type: Material): Boolean {
        if (type.isLegacy || !type.isBlock || type.isAir) return false
        if (type in excludedSet) return false

        return true
    }

    private fun createSlotMap(seed: Long): EnumMap<Material, Int> {
        val blocks = Material.values()
            .filter { isSurvivalBreakable(it) }
            .shuffled(Random(seed))

        val map = EnumMap<Material, Int>(Material::class.java)

        for (slot in 0 until SharedInventory.SIZE) {
            map[blocks[slot]] = slot
        }

        return map
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        SharedInventory.applyTo(event.player)
    }

    @Suppress("UNUSED_PARAMETER")
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        saveAll()
    }

    @Suppress("UNUSED_PARAMETER")
    @EventHandler
    fun onWorldSave(event: WorldSaveEvent) {
        saveAll()
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (SharedInventory.isBarrier(event.currentItem)) {
            event.isCancelled = true
            return
        }

        if (event.action == InventoryAction.HOTBAR_SWAP) {
            val button = event.hotbarButton

            if (button in 0 until SharedInventory.SIZE) {
                val item = event.whoClicked.inventory.getItem(button)

                if (SharedInventory.isBarrier(item)) {
                    event.isCancelled = true
                }
            }
        }
    }

    @EventHandler
    fun onDropItem(event: PlayerDropItemEvent) {
        if (event.itemDrop.itemStack.type == Material.BARRIER) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        val item = event.item ?: return

        if (item.type == Material.BARRIER) {
            event.isCancelled = true
            return
        }

        // 우클릭 장착은 해당 방어구 칸의 방벽을 손으로 옮겨 칸을 풀어 버리므로, 잠겨 있으면 막는다
        if (SharedInventory.active && event.action.isRightClick) {
            val armorSlot = when (item.type.equipmentSlot) {
                EquipmentSlot.FEET -> 36
                EquipmentSlot.LEGS -> 37
                EquipmentSlot.CHEST -> 38
                EquipmentSlot.HEAD -> 39
                else -> return
            }

            if (SharedInventory.isLocked(armorSlot)) {
                event.setUseItemInHand(Event.Result.DENY)
            }
        }
    }

    @EventHandler
    fun onItemSpawn(event: ItemSpawnEvent) {
        if (event.entity.itemStack.type == Material.BARRIER) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onSwap(event: PlayerSwapHandItemsEvent) {
        if (event.offHandItem?.type == Material.BARRIER || event.mainHandItem?.type == Material.BARRIER) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (!SharedInventory.active) return

        breakBlock(event.player.name, event.block.type)
    }

    /**
     * 블록 파괴 처리: 파괴 기록을 남기고, 대응하는 칸이 잠겨 있으면 해제한다.
     * 실제 파괴와 /invcaptive list 클릭이 같은 처리를 쓴다. 칸이 해제되면 true.
     */
    private fun breakBlock(playerName: String, type: Material): Boolean {
        BlockLog.recordBreak(type)

        val slot = slotsByType[type] ?: return false

        if (!SharedInventory.release(slot)) return false

        BlockLog.recordRelease(type, slot, playerName)

        for (player in Bukkit.getOnlinePlayers()) {
            player.world.spawn(player.location, Firework::class.java)
        }

        val blockName = BlockNames.displayName(type, NamedTextColor.GOLD)

        Bukkit.broadcast(
            Component.text()
                .append(Component.text(playerName, NamedTextColor.RED))
                .append(Component.text("님이 "))
                .append(blockName)
                .append(Component.text("을(를) 파괴하여 인벤토리 잠금이 한칸 해제되었습니다!"))
                .build()
        )

        return true
    }

    /** 사망 시 장벽을 제외한 공유 인벤토리 전체를 사망 위치에 드롭한다 */
    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        if (!SharedInventory.active) return

        event.keepInventory = true

        val drops = event.drops
        drops.clear()
        drops.addAll(SharedInventory.takeAllExceptBarriers())
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        // /q [페이지] : 파괴한 블록 목록
        if (command.name.equals("q", ignoreCase = true)) {
            if (requirePermission(sender, PERM_BLOCKS)) sendBrokenBlocks(sender, args.toList())
            return true
        }

        when (args.firstOrNull()?.lowercase()) {
            null -> if (requirePermission(sender, PERM_ADMIN)) {
                SharedInventory.captive()
                BlockLog.clear()
            }

            "blocks" -> if (requirePermission(sender, PERM_BLOCKS)) {
                sendBrokenBlocks(sender, args.drop(1))
            }

            "list" -> if (requirePermission(sender, PERM_ADMIN)) {
                sendSlotList(sender)
            }

            "excluded" -> if (requirePermission(sender, PERM_ADMIN)) {
                sendExcludedBlocks(sender)
            }

            "exclude" -> if (requirePermission(sender, PERM_ADMIN)) {
                handleExclude(sender, label, args)
            }

            "stop" -> if (requirePermission(sender, PERM_ADMIN)) {
                if (!SharedInventory.active) {
                    sender.sendMessage(Component.text("이미 종료된 상태입니다. /$label 로 다시 시작할 수 있습니다.", NamedTextColor.YELLOW))
                } else {
                    SharedInventory.stop()
                    Bukkit.broadcast(
                        Component.text("InvCaptive가 종료되었습니다. 인벤토리 잠금과 공유가 해제됩니다.", NamedTextColor.GREEN)
                    )
                }
            }

            else -> sender.sendMessage(
                Component.text("사용법: /$label blocks [broken|unbroken|all] [페이지] (블록 확인)", NamedTextColor.RED)
            )
        }

        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (command.name.equals("q", ignoreCase = true)) {
            if (!sender.hasPermission(PERM_BLOCKS)) return emptyList()

            return blocksCompletions(args.toList())
        }

        return when (args.size) {
            1 -> buildList {
                if (sender.hasPermission(PERM_BLOCKS)) add("blocks")
                if (sender.hasPermission(PERM_ADMIN)) {
                    add("list")
                    add("excluded")
                    add("exclude")
                    add("stop")
                }
            }.filter { it.startsWith(args[0], ignoreCase = true) }

            2 -> if (args[0].equals("blocks", ignoreCase = true) && sender.hasPermission(PERM_BLOCKS)) {
                blocksCompletions(args.drop(1))
            } else if (args[0].equals("exclude", ignoreCase = true) && sender.hasPermission(PERM_ADMIN)) {
                listOf("list", "add", "remove").filter { it.startsWith(args[1], ignoreCase = true) }
            } else {
                emptyList()
            }

            3 -> if (args[0].equals("blocks", ignoreCase = true) && sender.hasPermission(PERM_BLOCKS)) {
                blocksCompletions(args.drop(1))
            } else if (args[0].equals("exclude", ignoreCase = true) && sender.hasPermission(PERM_ADMIN)) {
                val candidates: List<Material> = when (args[1].lowercase()) {
                    "add" -> Material.values().filter { !it.isLegacy && it.isBlock && !it.isAir }
                    "remove" -> excludedSet.toList()
                    else -> emptyList()
                }

                candidates.map { it.name.lowercase() }.filter { it.startsWith(args[2], ignoreCase = true) }.take(50)
            } else {
                emptyList()
            }

            else -> emptyList()
        }
    }

    private fun requirePermission(sender: CommandSender, node: String): Boolean {
        if (sender.hasPermission(node)) return true

        sender.sendMessage(Component.text("이 명령어를 사용할 권한이 없습니다.", NamedTextColor.RED))
        return false
    }

    private fun pageCount(total: Int): Int = maxOf(1, (total + PAGE_SIZE - 1) / PAGE_SIZE)

    /** /invcaptive blocks 에서 보여줄 블록 범위 */
    private enum class BlockFilter(val label: String, val title: String) {
        BROKEN("캔 블록", "캔 블록"),
        UNBROKEN("안 캔 블록", "안 캔 블록"),
        ALL("전체", "전체 블록")
    }

    private fun parseBlockFilter(token: String): BlockFilter? = when (token.lowercase()) {
        "broken", "캔" -> BlockFilter.BROKEN
        "unbroken", "안캔" -> BlockFilter.UNBROKEN
        "all", "모두", "전체" -> BlockFilter.ALL
        else -> null
    }

    /** 범위에 해당하는 블록을 가나다순으로. 안 캔 블록 = 슬롯 대응 후보 중 아직 파괴하지 않은 블록 */
    private fun blocksFor(filter: BlockFilter): List<Material> {
        val broken = BlockLog.brokenBlocks().toSet()
        val candidates = Material.values().filter { isSurvivalBreakable(it) }

        val blocks: Collection<Material> = when (filter) {
            BlockFilter.BROKEN -> broken
            BlockFilter.UNBROKEN -> candidates.filter { it !in broken }
            BlockFilter.ALL -> candidates.toSet() + broken
        }

        return blocks.sortedWith(BlockNames.comparator)
    }

    /** blocks / q 명령의 탭 완성. 인자는 [범위] [페이지] 순서 무관하게 각각 최대 하나 */
    private fun blocksCompletions(args: List<String>): List<String> {
        if (args.isEmpty() || args.size > 2) return emptyList()

        val previous = args.dropLast(1)
        val current = args.last()
        val filter = previous.firstNotNullOfOrNull { parseBlockFilter(it) }
        val hasPage = previous.any { it.toIntOrNull() != null }

        return buildList {
            if (filter == null) addAll(listOf("broken", "unbroken", "all"))
            if (!hasPage) addAll((1..pageCount(blocksFor(filter ?: BlockFilter.BROKEN).size)).map { it.toString() })
        }.filter { it.startsWith(current, ignoreCase = true) }
    }

    /**
     * 블록 목록을 가나다순으로 보여준다. 명령어를 쓴 사람에게만 표시된다.
     * 인자: [broken|unbroken|all] [페이지]. 칸 해제에 성공한 블록은 금색으로, 해제한 플레이어와 칸 번호를 옆에 표시한다.
     */
    private fun sendBrokenBlocks(sender: CommandSender, args: List<String>) {
        var filter = BlockFilter.BROKEN
        var pageArg: String? = null

        for (token in args) {
            val parsed = parseBlockFilter(token)

            if (parsed != null) {
                filter = parsed
            } else if (token.toIntOrNull() != null) {
                pageArg = token
            } else {
                sender.sendMessage(Component.text("사용법: /blocks [broken|unbroken|all] [페이지]", NamedTextColor.RED))
                return
            }
        }

        // 플레이어: 채팅에 쌓이지 않도록 대화상자(Dialog)로 보여주고, 페이지를 넘길 때마다 같은 창을 교체한다.
        if (sender is Player) {
            showBrokenBlocksDialog(sender, filter, pageArg?.toInt() ?: 1)
            return
        }

        // 콘솔: 채팅 출력
        val blocks = blocksFor(filter)

        if (blocks.isEmpty()) {
            sender.sendMessage(Component.text(emptyMessage(filter), NamedTextColor.YELLOW))
            return
        }

        val lastPage = pageCount(blocks.size)
        val page = pageArg?.toInt() ?: 1

        if (page !in 1..lastPage) {
            sender.sendMessage(Component.text("페이지 번호는 1~$lastPage 사이로 입력하세요.", NamedTextColor.RED))
            return
        }

        sender.sendMessage(brokenBlocksSummary(blocks, filter, page, lastPage))

        val start = (page - 1) * PAGE_SIZE
        for ((offset, type) in blocks.drop(start).take(PAGE_SIZE).withIndex()) {
            sender.sendMessage(brokenBlockLine(start + offset + 1, type))
        }
    }

    private fun emptyMessage(filter: BlockFilter): String = when (filter) {
        BlockFilter.BROKEN -> "아직 파괴한 블록이 없습니다."
        BlockFilter.UNBROKEN -> "남은 블록이 없습니다."
        BlockFilter.ALL -> "표시할 블록이 없습니다."
    }

    private fun brokenBlocksSummary(blocks: List<Material>, filter: BlockFilter, page: Int, lastPage: Int): Component {
        val brokenCount = blocks.count { BlockLog.isBroken(it) }
        val releasedCount = blocks.count { BlockLog.releaseOf(it) != null }

        val text = when (filter) {
            BlockFilter.BROKEN -> "$page/$lastPage 페이지 · 총 ${blocks.size}종 · 칸 해제 ${releasedCount}개"
            BlockFilter.UNBROKEN -> "$page/$lastPage 페이지 · 총 ${blocks.size}종"
            BlockFilter.ALL -> "$page/$lastPage 페이지 · 총 ${blocks.size}종 · 캔 ${brokenCount}종 · 칸 해제 ${releasedCount}개"
        }

        return Component.text(text, NamedTextColor.YELLOW)
    }

    private fun brokenBlockLine(number: Int, type: Material): Component {
        val release = BlockLog.releaseOf(type)
        val broken = release != null || BlockLog.isBroken(type)

        val color = when {
            release != null -> NamedTextColor.GOLD
            broken -> NamedTextColor.WHITE
            else -> NamedTextColor.GRAY
        }

        val line = Component.text()
            .append(Component.text("$number. ", NamedTextColor.GRAY))
            .append(BlockNames.displayName(type, color))

        if (release != null) {
            line.append(
                Component.text(
                    " · ${release.player} · ${slotLabel(release.slot)}",
                    NamedTextColor.GRAY
                )
            )
        }

        return line.build()
    }

    /** 블록 목록 대화상자. 버튼을 누르면 같은 창이 새 페이지/범위로 교체된다. */
    private fun showBrokenBlocksDialog(player: Player, filter: BlockFilter, requestedPage: Int) {
        val blocks = blocksFor(filter)
        val lastPage = pageCount(blocks.size)
        val page = requestedPage.coerceIn(1, lastPage)

        val start = (page - 1) * PAGE_SIZE
        val lines = blocks.drop(start).take(PAGE_SIZE).withIndex().map { (offset, type) ->
            brokenBlockLine(start + offset + 1, type)
        }

        val content = Component.join(
            JoinConfiguration.newlines(),
            if (blocks.isEmpty()) {
                listOf(Component.text(emptyMessage(filter), NamedTextColor.YELLOW))
            } else {
                listOf(brokenBlocksSummary(blocks, filter, page, lastPage), Component.empty()) + lines
            }
        )

        fun reopen(viewer: Any?, targetFilter: BlockFilter, target: Int) {
            val who = viewer as? Player ?: return
            // 콜백에서 바로 새 대화상자를 열면 기존 창이 교체된다. 메인 스레드에서 실행.
            Bukkit.getScheduler().runTask(this, Runnable { showBrokenBlocksDialog(who, targetFilter, target) })
        }

        val options = ClickCallback.Options.builder().build()

        fun button(label: Component, tooltip: String?, action: DialogActionCallback): ActionButton =
            ActionButton.create(
                label,
                tooltip?.let { Component.text(it) },
                NAV_BUTTON_WIDTH,
                DialogAction.customClick(action, options)
            )

        fun pageButton(label: String, target: Int, tooltip: String?): ActionButton =
            button(
                Component.text(label, NamedTextColor.AQUA),
                tooltip,
                DialogActionCallback { _, audience -> reopen(audience, filter, target) }
            )

        // 범위 토글: 캔 블록 -> 안 캔 블록 -> 전체 -> 캔 블록 순서로 바뀐다
        val nextFilter = BlockFilter.values()[(filter.ordinal + 1) % BlockFilter.values().size]

        val filterButton = button(
            Component.text("범위: ${filter.label}", NamedTextColor.YELLOW),
            "클릭하면 ${nextFilter.label}",
            DialogActionCallback { _, audience -> reopen(audience, nextFilter, 1) }
        )

        val closeButton = button(
            Component.text("닫기", NamedTextColor.GRAY),
            null,
            DialogActionCallback { _, audience ->
                val who = audience as? Player ?: return@DialogActionCallback
                Bukkit.getScheduler().runTask(this, Runnable { who.closeDialog() })
            }
        )

        // 동작 없는 빈 버튼: 줄을 5칸으로 맞추는 용도
        fun spacer(): ActionButton = ActionButton.create(Component.text(" "), null, NAV_BUTTON_WIDTH, null)

        fun fillRow(row: MutableList<ActionButton>) {
            while (row.size % 5 != 0) row += spacer()
        }

        // 한 줄에 5개씩 배치된다. 첫 줄: 번호, 둘째 줄: 범위 + 처음/이전/다음/끝, 맨 아래: 닫기
        val buttons = ArrayList<ActionButton>()

        if (lastPage > 1) {
            // 현재 페이지 주변 번호 버튼 (최대 5개). 현재 페이지는 노란색 [번호]
            val first = (page - 2).coerceAtMost(lastPage - 4).coerceAtLeast(1)
            val last = (first + 4).coerceAtMost(lastPage)

            for (number in first..last) {
                buttons += if (number == page) {
                    button(
                        Component.text("[$number]", NamedTextColor.YELLOW),
                        "현재 페이지",
                        DialogActionCallback { _, audience -> reopen(audience, filter, number) }
                    )
                } else {
                    pageButton("$number", number, "${number}페이지")
                }
            }

            fillRow(buttons)
        }

        buttons += filterButton

        if (lastPage > 1) {
            val prev = if (page > 1) page - 1 else lastPage
            val next = if (page < lastPage) page + 1 else 1

            buttons += pageButton("처음", 1, "1페이지")
            buttons += pageButton("이전", prev, "${prev}페이지")
            buttons += pageButton("다음", next, "${next}페이지")
            buttons += pageButton("끝", lastPage, "${lastPage}페이지")
        }
        val type = DialogType.multiAction(buttons, closeButton, 5)

        val base = DialogBase.builder(Component.text("${filter.title} ($page/$lastPage)", NamedTextColor.YELLOW))
            .body(listOf(DialogBody.plainMessage(content, 400)))
            .pause(false)
            .canCloseWithEscape(true)
            .afterAction(DialogBase.DialogAfterAction.NONE)
            .build()

        val dialog = Dialog.create { factory -> factory.empty().base(base).type(type) }

        player.showDialog(dialog)
    }

    /** 슬롯별로 어떤 블록을 부수면 해제되는지, 현재 잠겨 있는지 표시 */
    private fun sendSlotList(sender: CommandSender) {
        val entries = slotsByType.entries.sortedBy { it.value }
        val lockedCount = entries.count { SharedInventory.isLocked(it.value) }

        sender.sendMessage(Component.text("슬롯별 해제 블록 (잠김 $lockedCount/${entries.size})", NamedTextColor.YELLOW))

        for ((type, slot) in entries) {
            val locked = SharedInventory.isLocked(slot)

            sender.sendMessage(
                Component.text()
                    .append(Component.text("${slotLabel(slot)}: ", NamedTextColor.GRAY))
                    .append(
                        BlockNames.displayName(type, if (locked) NamedTextColor.GOLD else NamedTextColor.DARK_GRAY)
                            .hoverEvent(HoverEvent.showText(Component.text("클릭하여 잠금해제")))
                            .clickEvent(giveClick(type))
                    )
                    .append(Component.text(if (locked) " (잠김)" else " (열림)", NamedTextColor.DARK_GRAY))
                    .build()
            )
        }
    }

    /** 블록 이름 클릭 시 그 블록을 파괴한 것으로 처리해 해당 칸을 해제한다 (OP 전용) */
    private fun giveClick(type: Material): ClickEvent<*> =ClickEvent.callback(
        ClickCallback<Audience> { audience ->
            val player = audience as? Player

            if (player != null) {
                if (!player.hasPermission(PERM_ADMIN)) {
                    player.sendMessage(Component.text("이 명령어를 사용할 권한이 없습니다.", NamedTextColor.RED))
                } else if (!SharedInventory.active) {
                    player.sendMessage(Component.text("InvCaptive가 종료된 상태입니다. /invcaptive 로 다시 시작하세요.", NamedTextColor.YELLOW))
                } else if (!breakBlock(player.name, type)) {
                    player.sendMessage(Component.text("이미 열려 있는 칸입니다.", NamedTextColor.YELLOW))
                }
            }
        },
        ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(Duration.ofHours(1))
            .build()
    )

    /** 슬롯 대응 후보에서 제외된 블록을 가나다순으로 표시 (legacy, 공기 제외) */
    private fun sendExcludedBlocks(sender: CommandSender) {
        val excluded = excludedBlocks()

        sender.sendMessage(Component.text("슬롯 대응에서 제외된 블록 (${excluded.size}개)", NamedTextColor.YELLOW))

        // 사유 없이 쉼표로만 구분해 한 줄로 표시
        sender.sendMessage(
            Component.join(
                JoinConfiguration.separator(Component.text(", ", NamedTextColor.GRAY)),
                excluded.map { BlockNames.displayName(it, NamedTextColor.WHITE) }
            )
        )
    }

    /** 슬롯 대응에서 제외된 블록 (한국어 가나다순) */
    private fun excludedBlocks(): List<Material> = excludedSet.sortedWith(BlockNames.comparator)

    private fun slotLabel(slot: Int): String = when (slot) {
        in 0..8 -> "0-${slot + 1}"
        in 9..35 -> "${(slot - 9) / 9 + 1}-${(slot - 9) % 9 + 1}"
        36 -> "신발"
        37 -> "레깅스"
        38 -> "흉갑"
        39 -> "투구"
        else -> "보조 손"
    }
}
