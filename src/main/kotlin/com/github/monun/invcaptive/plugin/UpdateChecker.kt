package com.github.monun.invcaptive.plugin

import com.google.gson.JsonParser
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

/**
 * GitHub 릴리스에서 새 버전이 나왔는지 확인한다. 서버를 켤 때 한 번 확인하고 이후 1시간마다 확인한다.
 * 새 버전이 있으면 콘솔에 알리고, 접속 중이거나 새로 접속하는 모든 플레이어에게도 알린다.
 * 사전 릴리스(알파 등)도 포함해 가장 높은 버전을 기준으로 한다.
 *
 * autoUpdate 가 켜져 있으면 새 jar 를 받아 plugins/update 폴더에 둔다. 서버가 다음에 시작될 때
 * Paper 가 같은 이름의 기존 jar 를 이 파일로 교체한다. 실행 중에는 jar 를 바꾸지 않는다.
 * 받은 파일은 GitHub 릴리스가 알려 주는 SHA-256 과 일치할 때만 저장한다.
 */
class UpdateChecker(
    private val plugin: JavaPlugin,
    private val currentVersion: String,
    private val autoUpdate: Boolean
) {
    data class Release(val tag: String, val url: String, val assetUrl: String?, val sha256: String?)

    @Volatile
    var latest: Release? = null
        private set

    /** 새 jar 를 plugins/update 에 저장해 둔 버전 */
    @Volatile
    private var stagedTag: String? = null

    /** 시작 직후 한 번, 이후 1시간마다 확인한다. 새 버전이 처음 발견될 때만 알린다. */
    fun start() {
        plugin.server.scheduler.runTaskTimerAsynchronously(plugin, Runnable { check() }, 0L, CHECK_INTERVAL_TICKS)
    }

    private fun check() {
        val newest = runCatching { fetchNewest() }
            .onFailure { plugin.logger.info("업데이트 확인에 실패했습니다: ${it.message}") }
            .getOrNull() ?: return

        if (compareVersions(newest.tag, currentVersion) <= 0) return

        val firstTime = newest.tag != latest?.tag
        latest = newest

        if (autoUpdate && stagedTag != newest.tag) {
            runCatching { stage(newest) }
                .onFailure { plugin.logger.warning("새 버전을 받지 못했습니다: ${it.message}") }
        }

        if (!firstTime) return

        if (stagedTag == newest.tag) {
            plugin.logger.warning("새 버전 ${newest.tag} 을(를) plugins/update 에 받아 두었습니다. 서버를 재시작하면 업데이트됩니다. (현재 $currentVersion)")
        } else {
            plugin.logger.warning("새 버전이 나왔습니다: ${newest.tag} (현재 $currentVersion) ${newest.url}")
        }

        // 서버가 돌아가는 중에 발견되면 접속 중인 모든 플레이어에게 바로 알린다 (메인 스레드에서 전송)
        plugin.server.scheduler.runTask(plugin, Runnable {
            for (player in plugin.server.onlinePlayers) notify(player)
        })
    }

    /** 새 버전이 있으면 안내 문구를 보낸다 */
    fun notify(target: CommandSender) {
        val release = latest ?: return

        val message = Component.text()
            .append(Component.text("[InvCaptive] ", NamedTextColor.GOLD))

        if (stagedTag == release.tag) {
            message.append(
                Component.text("새 버전 ${release.tag} 을(를) 받아 두었습니다. 서버를 재시작하면 업데이트됩니다. (현재 $currentVersion)", NamedTextColor.YELLOW)
            )
        } else {
            message
                .append(Component.text("새 버전 ${release.tag} 이(가) 나왔습니다 (현재 $currentVersion). ", NamedTextColor.YELLOW))
                .append(Component.text("[다운로드]", NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(release.url)))
        }

        target.sendMessage(message.build())
    }

    private fun fetchNewest(): Release? {
        val request = HttpRequest.newBuilder(URI.create(RELEASES_URL))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "InvCaptive-UpdateChecker")
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "HTTP ${response.statusCode()}" }

        return JsonParser.parseString(response.body()).asJsonArray
            .map { it.asJsonObject }
            .filter { !it.get("draft").asBoolean }
            .map { release ->
                val asset = release.getAsJsonArray("assets")
                    ?.map { it.asJsonObject }
                    ?.firstOrNull { it.get("name").asString == ASSET_NAME }

                Release(
                    tag = release.get("tag_name").asString,
                    url = release.get("html_url").asString,
                    assetUrl = asset?.get("browser_download_url")?.asString,
                    sha256 = asset?.get("digest")?.takeIf { !it.isJsonNull }?.asString
                        ?.removePrefix("sha256:")?.lowercase()
                )
            }
            .maxWithOrNull { a, b -> compareVersions(a.tag, b.tag) }
    }

    /** 새 jar 를 받아 SHA-256 을 확인한 뒤 plugins/update 에 현재 jar 와 같은 이름으로 저장한다 */
    private fun stage(release: Release) {
        val assetUrl = release.assetUrl ?: error("릴리스에 $ASSET_NAME 이(가) 없습니다")
        val expected = release.sha256 ?: error("릴리스에 파일 해시가 없어 받지 않습니다")

        check(assetUrl.startsWith(ALLOWED_DOWNLOAD_PREFIX)) { "허용되지 않은 다운로드 주소입니다" }

        val jarName = File(plugin.javaClass.protectionDomain.codeSource.location.toURI()).name
        check(jarName.endsWith(".jar")) { "jar 파일로 실행 중이 아니라 건너뜁니다" }

        val updateDir = plugin.server.updateFolderFile.also { it.mkdirs() }
        val temp = File(updateDir, "$jarName.part")

        try {
            val request = HttpRequest.newBuilder(URI.create(assetUrl))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "InvCaptive-UpdateChecker")
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            check(response.statusCode() == 200) { "HTTP ${response.statusCode()}" }

            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L

            response.body().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(8192)

                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break

                        total += read
                        check(total <= MAX_JAR_BYTES) { "파일이 너무 큽니다" }

                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == expected) { "파일 해시가 일치하지 않습니다" }

            Files.move(temp.toPath(), File(updateDir, jarName).toPath(), StandardCopyOption.REPLACE_EXISTING)
            stagedTag = release.tag
        } finally {
            temp.delete()
        }
    }

    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/HKM7531/InvCaptive-Remastered/releases?per_page=20"
        private const val ALLOWED_DOWNLOAD_PREFIX = "https://github.com/HKM7531/InvCaptive-Remastered/releases/download/"
        private const val ASSET_NAME = "InvCaptive.jar"
        private const val MAX_JAR_BYTES = 20L * 1024 * 1024
        private const val CHECK_INTERVAL_TICKS = 20L * 60 * 60

        private val client: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        private val TOKEN = Regex("[0-9]+|[A-Za-z]+")

        /**
         * 버전 비교. "26.3.0" 같은 숫자 부분을 먼저 비교하고, "-a2" 같은 사전 릴리스 표기가 있으면
         * 같은 숫자의 정식 버전보다 낮다. 사전 릴리스끼리는 a1 < a2 < a10 순서로 비교한다.
         */
        fun compareVersions(a: String, b: String): Int {
            val (coreA, preA) = split(a)
            val (coreB, preB) = split(b)

            val core = compareTokens(coreA, coreB)
            if (core != 0) return core

            return when {
                preA.isEmpty() && preB.isEmpty() -> 0
                preA.isEmpty() -> 1
                preB.isEmpty() -> -1
                else -> compareTokens(preA, preB, longerIsLower = true)
            }
        }

        private fun split(version: String): Pair<List<String>, List<String>> {
            val text = version.trim().removePrefix("v").removePrefix("V")
            val index = text.indexOf('-')
            val core = if (index < 0) text else text.substring(0, index)
            val pre = if (index < 0) "" else text.substring(index + 1)

            return TOKEN.findAll(core).map { it.value }.toList() to TOKEN.findAll(pre).map { it.value }.toList()
        }

        private fun compareTokens(a: List<String>, b: List<String>, longerIsLower: Boolean = false): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrNull(i)
                val y = b.getOrNull(i)

                // 사전 릴리스는 "a4-test" 처럼 꼬리가 더 붙은 쪽이 "a4" 보다 낮다
                if (x == null) return if (longerIsLower) 1 else -1
                if (y == null) return if (longerIsLower) -1 else 1

                val numX = x.toBigIntegerOrNull()
                val numY = y.toBigIntegerOrNull()

                val result = when {
                    numX != null && numY != null -> numX.compareTo(numY)
                    numX != null -> -1
                    numY != null -> 1
                    else -> x.compareTo(y, ignoreCase = true)
                }

                if (result != 0) return result
            }

            return 0
        }
    }
}
