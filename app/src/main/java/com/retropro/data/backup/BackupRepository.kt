package com.retropro.data.backup

import android.content.Context
import com.retropro.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 备份的文件读写。
 *
 * ## 为什么备份到 App 专属外部目录
 *
 * `getExternalFilesDir()` **不需要任何权限**（Android 10+ 的分区存储下也不受限），
 * 而写 Downloads 需要走 MediaStore 的流程。代价是普通文件管理器不容易看到它，
 * 所以导出后 UI 会把完整路径显示出来，用户可以用系统的"文件"App 进
 * `Android/data/com.retropro/files/backup/` 拿走。
 *
 * ## 导入会把现有数据全部清掉
 *
 * 备份语义就是"恢复到这个时点"。恢复在一个事务里先清空再整批重插，
 * 失败整体回滚。UI 层必须在导入前明确告知这一点（见 ProfileScreen 的确认弹窗）。
 */
class BackupRepository(
    private val db: AppDatabase,
    private val context: Context,
) {

    private val backupDir: File?
        get() = context.getExternalFilesDir("backup")

    /** 导出为 JSON 文件，返回写好的文件（给 UI 显示路径） */
    suspend fun exportToFile(): File = withContext(Dispatchers.IO) {
        val dir = backupDir ?: error("外部存储不可用")
        dir.mkdirs()
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(System.currentTimeMillis()))
        val out = File(dir, "retropro-backup-$stamp.json")
        out.writeText(BackupManager.export(db).toString(2))
        out
    }

    /** 从输入流恢复。**会清空现有数据**。 */
    suspend fun importFromStream(stream: InputStream) = withContext(Dispatchers.IO) {
        val text = stream.bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        BackupManager.restore(db, root)
    }

    /** 最近一次导出的备份文件（导入 UI 的默认选择） */
    fun latestBackupFile(): File? =
        backupDir?.listFiles { f -> f.name.endsWith(".json") }
            ?.maxByOrNull { it.lastModified() }

    companion object {
        fun previewPath(context: Context): String =
            (context.getExternalFilesDir("backup")?.absolutePath ?: "不可用") +
                "/retropro-backup-<时间>.json"
    }
}
