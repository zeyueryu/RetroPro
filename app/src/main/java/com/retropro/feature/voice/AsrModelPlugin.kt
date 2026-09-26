package com.retropro.feature.voice

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * 语音模型插件 —— 把 230MB+ 的识别模型从 APK 里剥离出来，改为**按需下载**。
 *
 * ## 为什么剥离
 *
 * 之前模型打在 `assets/asr/` 里，APK 达 302 MB，其中约 230 MB 是模型 ——
 * 每次改个 UI 都要重下/重装 300MB，且应用商店/分发性都受影响。
 * 现在模型作为**插件**下载到应用私有目录 `filesDir/asr/`，APK 只剩代码与 native 库。
 *
 * ## 目录约定（沿用既有）
 *
 * `filesDir/asr/model.int8.onnx` · `filesDir/asr/tokens.txt` · `filesDir/asr/silero_vad.onnx`
 * —— [LocalAsr] 与 [GlobalAnalyzer] 都从这里加载，无需存储权限（应用私有目录）。
 *
 * ## 下载源（URL 与字节数均已实测核对，非猜测）
 *
 * 每个文件可有多个候选源，**按顺序尝试**，任一源下载完成且字节数精确匹配即通过；
 * 不匹配就丢掉重新换源（字节数是唯一可信的完整性判据 —— 上游没有提供各文件的校验和）。
 *
 * | 文件 | 字节数 | 源（按优先级） |
 * |---|---|---|
 * | model.int8.onnx | 239,233,841 | **ModelScope** → hf-mirror |
 * | tokens.txt | 315,894 | hf-mirror → ModelScope |
 * | silero_vad.onnx | 2,313,101 | **ModelScope v5** → ghfast(GitHub) → GitHub 官方 |
 *
 * ⚠️ silero_vad 各源**不是同一个版本**：GitHub 是旧版（643,854 字节），
 * ModelScope 上是更新的 v5（2,313,101 字节）。两者都能被 sherpa-onnx 加载，
 * 所以把各自期望字节数写在 [Source.bytes] 上，谁成功就按谁校验。
 *
 * ⚠️ hf-mirror 的 Content-Length 在部分文件上虚标（如 tokens.txt 报 524,288 实发 315,894）。
 *   Python 的 `urlopen` 会因此抛 IncompleteRead，而 Android 的 HttpURLConnection 不校验流
 *   完整性、读到 EOF 即止，`written` 仍会等于 [Source.bytes] —— 故镜像站在 App 里可用，
 *   但只放备位，避免任何依赖 Content-Length 的下游踩坑。
 *
 * ⚠️ github.com 的 releases/download 在国内多数网络下直连不通（实测 `Tunnel connection failed`），
 *   已经从主源降级为最后一档兜底；日常下载一律走国内源。
 */
internal object AsrModelPlugin {

    /** 插件目录名（相对 `filesDir`），与旧 assets 时代保持一致，便于老用户覆盖升级 */
    private const val DIR_NAME = "asr"

    private const val SENSE_VOICE_FILE = "model.int8.onnx"
    private const val TOKENS_FILE = "tokens.txt"
    private const val VAD_FILE = "silero_vad.onnx"

    /** 每个文件的候选源。**顺序即优先级** */
    data class Source(val label: String, val url: String, val bytes: Long)

    data class Spec(val fileName: String, val candidates: List<Source>)

    private const val HF_MIRROR =
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"
    private const val MODELSCOPE =
        "https://www.modelscope.cn/models/gomodels/sherpa/resolve/master"
    private const val GITHUB_RELEASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
    /** GitHub Releases 在国内多数网络下直连不通（实测 `Tunnel connection failed`），故只留作最后兜底 */
    private const val GITHUB_MIRROR = "https://ghfast.top"

    /**
     * 实测各源吞吐（2026-09-26，各取 1~16 MiB 样本）：
     *  ```
     *  魔搭  model.int8.onnx  1.9~7.0 MiB/s   ← 最快
     *  镜像站 model.int8.onnx  0.40 MiB/s     ← 慢 17 倍，228 MB 要下约 9 分钟
     *  ```
     * 所以主模型的主源定为魔搭；镜像站只留备位。
     */
    val FILES: List<Spec> = listOf(
        Spec(
            SENSE_VOICE_FILE,
            listOf(
                Source("魔搭", "$MODELSCOPE/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/$SENSE_VOICE_FILE", 239_233_841L),
                Source("镜像站", "$HF_MIRROR/$SENSE_VOICE_FILE", 239_233_841L),
            ),
        ),
        Spec(
            TOKENS_FILE,
            listOf(
                Source("镜像站", "$HF_MIRROR/$TOKENS_FILE", 315_894L),
                Source("魔搭", "$MODELSCOPE/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/$TOKENS_FILE", 315_894L),
            ),
        ),
        Spec(
            VAD_FILE,
            listOf(
                // 镜像站的仓库里根本没有这个文件（实测 404），GitHub 直连又多数不通，
                // 所以主源只能是魔搭 —— 它这里是 v5（2.3 MB），比 GitHub 那份更新。
                Source("魔搭", "$MODELSCOPE/vad/$VAD_FILE", 2_313_101L),
                Source("ghfast", "$GITHUB_MIRROR/$GITHUB_RELEASE/$VAD_FILE", 643_854L),
                Source("GitHub", "$GITHUB_RELEASE/$VAD_FILE", 643_854L),
            ),
        ),
    )

    /** 进度分母：各文件取**首选源**的字节数之和（正常路径就是精确总量） */
    val TOTAL_BYTES: Long = FILES.sumOf { it.candidates.first().bytes }

    /** 大致体积，用于 UI 文案 */
    const val APPROX_MB: Int = 229

    // ---------------------------------------------------------------- 路径与状态

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun file(context: Context, name: String): File = File(dir(context), name)

    /** 三个文件是否都就位且字节数正确 */
    fun isInstalled(context: Context): Boolean = FILES.all { spec ->
        val f = file(context, spec.fileName)
        f.isFile && spec.candidates.any { it.bytes == f.length() }
    }

    /** 已占用的字节数（UI 展示"已安装 229 MB"） */
    fun installedBytes(context: Context): Long =
        FILES.sumOf { file(context, it.fileName).takeIf { f -> f.isFile }?.length() ?: 0L }

    /** 删除已下载的模型（设置页「删除模型」用），失败也不抛 */
    fun remove(context: Context): Boolean =
        runCatching {
            val d = dir(context)
            FILES.forEach { File(d, it.fileName).delete() }
            // .part 残留一并清掉
            d.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
            true
        }.getOrDefault(false)

    // ---------------------------------------------------------------- 安装

    /**
     * 下载并安装模型。**内部已切到 [Dispatchers.IO]**，必须在协程里调用。
     *
     * - [onProgress] 会被**节流**（每 1 MB 或单文件完成时回调一次），
     *   从 IO 线程调用 —— 调用方直接写 Compose 的 `MutableState` 即可（快照状态写入是线程安全的），
     *   但**不要**在回调里做重活。
     * - 任一文件所有源都失败 → 返回 `Result.failure`，已下好的文件**保留**（重试时能省流量）。
     * - 取消（页面退出）时会删掉半截的 `.part`，不留垃圾。
     */
    suspend fun install(
        context: Context,
        onProgress: (downloadedBytes: Long, totalBytes: Long, currentFileName: String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val d = dir(context)
            if (!d.exists() && !d.mkdirs()) error("无法创建模型目录")

            // 空间预判：模型约 229MB，留 50MB 余量，避免下载到一半才失败
            val need = TOTAL_BYTES + 50L * 1024 * 1024
            if (d.usableSpace in 1 until need) {
                error("存储空间不足（还需约 ${need / 1024 / 1024} MB）")
            }

            var done = 0L
            for (spec in FILES) {
                val target = File(d, spec.fileName)

                // 已存在且字节数正确 → 跳过（重试时不重复下载）
                if (target.isFile && spec.candidates.any { it.bytes == target.length() }) {
                    done += target.length()
                    onProgress(done, TOTAL_BYTES, spec.fileName)
                    continue
                }

                var lastError: Throwable? = null
                var succeeded = false
                for (source in spec.candidates) {
                    try {
                        downloadOne(source, target) { fileDone ->
                            // 缓冲是 1MB，所以这里天然就是"每 1MB 回调一次"（240MB ≈ 240 次），
                            // 不需要额外节流；调用方写 Compose 状态也不会造成重组风暴。
                            onProgress(done + fileDone, TOTAL_BYTES, spec.fileName)
                        }
                        succeeded = true
                        break
                    } catch (ce: CancellationException) {
                        File(d, "${spec.fileName}.part").delete()
                        throw ce
                    } catch (t: Throwable) {
                        lastError = t
                    }
                }
                if (!succeeded) {
                    throw lastError ?: IllegalStateException("${spec.fileName} 全部下载源均失败")
                }
                done += target.length()
                onProgress(done, TOTAL_BYTES, spec.fileName)
            }

            require(isInstalled(context)) { "下载完成但校验不通过" }
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** 下单个文件到 `xxx.part`，字节数**精确匹配**才改名到正式文件 */
    private suspend fun downloadOne(
        source: Source,
        target: File,
        onProgress: (fileBytes: Long) -> Unit,
    ) {
        val part = File(target.parentFile, "${target.name}.part")
        part.delete()

        val conn = (URL(source.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "RetroPro-Android")
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IllegalStateException("${source.label} 返回 HTTP $code")
            }
            var written = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(1 shl 20) // 1MB
                    while (true) {
                        coroutineContext.ensureActive() // 取消时立刻退出（页面退出/用户取消）
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        written += n
                        onProgress(written)
                    }
                }
            }
            if (written != source.bytes) {
                part.delete()
                throw IllegalStateException("${source.label} 字节数不符：$written ≠ ${source.bytes}")
            }
            // 原子改名：校验通过才成为正式文件，中断不会留下半个可用文件
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
        } finally {
            conn.disconnect()
            // 成功路径已把 .part 改名成正式文件；这里还留着就说明失败/取消，清掉不留垃圾
            if (part.exists()) part.delete()
        }
    }
}
