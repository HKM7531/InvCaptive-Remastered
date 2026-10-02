package com.github.monun.invcaptive.plugin

import com.google.gson.JsonParser
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * GitHub 릴리스에서 새 버전이 나왔는지 확인한다. 서버를 켤 때 한 번 확인하고,
 * 새 버전이 있으면 콘솔에 알리고 관리자(invcaptive.command)가 접속할 때도 알린다.
 * 사전 릴리스(알파 등)도 포함해 가장 높은 버전을 기준으로 한다.
 */
class UpdateChecker(private val plugin: JavaPlugin, private val currentVersion: String) {
    data class Release(val tag: String, val url: String)

    @Volatile
    var latest: Release? = null
        private set

    fun check() {
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val newest = runCatching { fetchNewest() }
                .onFailure { plugin.logger.info("업데이트 확인에 실패했습니다: ${it.message}") }
                .getOrNull() ?: return@Runnable

            if (compareVersions(newest.tag, currentVersion) > 0) {
                latest = newest
                plugin.logger.warning("새 버전이 나왔습니다: ${newest.tag} (현재 $currentVersion) ${newest.url}")
            }
        })
    }

    /** 새 버전이 있으면 안내 문구를 보낸다 */
    fun notify(target: CommandSender) {
        val release = latest ?: return

        target.sendMessage(
            Component.text()
                .append(Component.text("[InvCaptive] ", NamedTextColor.GOLD))
                .append(Component.text("새 버전 ${release.tag} 이(가) 나왔습니다 (현재 $currentVersion). ", NamedTextColor.YELLOW))
                .append(
                    Component.text("[다운로드]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl(release.url))
                )
                .build()
        )
    }

    private fun fetchNewest(): Release? {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

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
            .map { Release(it.get("tag_name").asString, it.get("html_url").asString) }
            .maxWithOrNull { a, b -> compareVersions(a.tag, b.tag) }
    }

    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/HKM7531/InvCaptive-Remastered/releases?per_page=20"

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
                else -> compareTokens(preA, preB)
            }
        }

        private fun split(version: String): Pair<List<String>, List<String>> {
            val text = version.trim().removePrefix("v").removePrefix("V")
            val index = text.indexOf('-')
            val core = if (index < 0) text else text.substring(0, index)
            val pre = if (index < 0) "" else text.substring(index + 1)

            return TOKEN.findAll(core).map { it.value }.toList() to TOKEN.findAll(pre).map { it.value }.toList()
        }

        private fun compareTokens(a: List<String>, b: List<String>): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrNull(i)
                val y = b.getOrNull(i)

                if (x == null) return -1
                if (y == null) return 1

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
