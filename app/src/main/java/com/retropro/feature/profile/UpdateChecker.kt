package com.retropro.feature.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本检查 —— 直接打 GitHub Releases API，**零新依赖**。
 *
 * ## 为什么手写 [HttpURLConnection]
 *
 * 项目没有 OkHttp / Retrofit / Ktor。为了一个 GET 请求引一套 HTTP 栈不划算：
 * HttpURLConnection 是平台自带的，一次请求足够。
 *
 * ## 为什么用 org.json
 *
 * 与 [com.retropro.data.backup.BackupManager] 同一口径（Android 自带、零依赖）。
 * 响应里只用得到 `tag_name` 一个字段，手写取值最直白。
 *
 * ## 线程
 *
 * [fetchLatestTag] **内部已切到 [Dispatchers.IO]** —— 调用方从任意协程上下文直接调即可，
 * 但**绝不能在主线程直接调用**（否则 NetworkOnMainThreadException）。
 */
internal object UpdateChecker {

    /** 最新一条**正式** Release（预发布不计入 latest）。 */
    private const val ENDPOINT =
        "https://api.github.com/repos/zeyueryu/RetroPro/releases/latest"

    /** 超时给短：球场弱网下不该让用户盯着转圈 */
    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 6_000

    /** GitHub **强制要求** User-Agent，缺失直接 403 */
    private const val USER_AGENT = "RetroPro-Android"

    /** HTTP 非 200 时抛出，供上层区分 403 限流 / 404 无发布 / 5xx */
    internal class HttpError(val code: Int) : Exception("HTTP $code")

    internal suspend fun fetchLatestTag(): Result<String> = withContext(Dispatchers.IO) {
        try {
            Result.success(doFetch())
        } catch (ce: CancellationException) {
            // 页面退出时协程会被取消；必须原样抛出，不能被 Result 吞掉
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun doFetch(): String {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw HttpError(code)
            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val tag = JSONObject(body).optString("tag_name")
            require(tag.isNotBlank()) { "响应缺少 tag_name" }
            return tag
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * 版本号三元组。**只比 major.minor.patch**，`-m1` / `+build` 这类后缀整体忽略。
 *
 * 用 [Long] 而不是 Int：防住 `99999999999.0.0` 这种异常 tag 造成的溢出。
 */
internal data class SemVer(val major: Long, val minor: Long, val patch: Long) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    companion object {
        /**
         * 解析版本号。**返回 null 表示"不是可识别的版本号"**（如 `nightly`、空串、`1.2.x`），
         * 上层据此走「无法判断」分支，**绝不误报有新版本**。
         *
         * 规则（按顺序）：
         *  1. 去空白；若带 `refs/tags/` 前缀则取末段（GitHub 某些接口给全引用）
         *  2. 去前导 `v` / `V`
         *  3. 截断 `-` 预发布后缀与 `+` 构建后缀（`1.1.14-m1` → `1.1.14`）
         *  4. 按 `.` 拆，取前 3 段，缺失补 0（`1.2` → 1.2.0），多余段忽略（`1.2.3.4` → 1.2.3）
         *  5. 每段必须是非负整数，任一段不满足则整体返回 null
         */
        fun parse(raw: String?): SemVer? {
            if (raw == null) return null
            var s = raw.trim()
            if (s.isEmpty()) return null
            s = s.substringAfterLast("refs/tags/", s)
            if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
            s = s.substringBefore('-').substringBefore('+').trim()
            if (s.isEmpty()) return null

            val parts = s.split('.')
            val n = LongArray(3)
            for (i in 0 until 3) {
                if (i >= parts.size) continue
                n[i] = parts[i].trim().toLongOrNull()?.takeIf { it >= 0 } ?: return null
            }
            return SemVer(n[0], n[1], n[2])
        }
    }
}

/** 远端相对本地的比较结论 */
internal sealed interface VersionVerdict {
    /** 远端可解析且核心版本 > 本地 */
    data class Newer(val remote: SemVer) : VersionVerdict

    /** 远端可解析且核心版本 <= 本地 */
    data object UpToDate : VersionVerdict

    /** 远端 tag 不是可识别版本号 → 无法判断（**不视为有新版本**） */
    data object Unparseable : VersionVerdict
}

/**
 * 只比数字核心（major.minor.patch）。
 *
 * ⚠️ **后缀一律忽略**：本地 `1.1.14-m1` 与远端 `v1.1.14-m1` / `v1.1.14` 都判「已是最新」。
 * 这是刻意取舍 —— `-m1 / -beta / -rc` 的先后没有通用规则，硬比容易把当前版本误报成有新版本。
 * 代价：用 `1.1.14-m1` 的包去发 `v1.1.14` 正式版不会提示，需把版本号升到 `1.1.15`。
 */
internal fun compareVersion(remoteTag: String?, localVersionName: String): VersionVerdict {
    val remote = SemVer.parse(remoteTag) ?: return VersionVerdict.Unparseable
    val local = SemVer.parse(localVersionName) ?: return VersionVerdict.Unparseable
    return if (remote > local) VersionVerdict.Newer(remote) else VersionVerdict.UpToDate
}
