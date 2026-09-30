package com.aicode.core.db

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aicode.core.util.FileLogger

class FileMigration(
    val version: Int,
    val scriptName: String,
    val sqlStatements: List<String>
) : Migration(version - 1, version) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            // 整段迁移包在事务里：任一条语句失败整体回滚，避免留半迁移状态
            db.beginTransaction()
            try {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS migration_history (" +
                            "version INTEGER PRIMARY KEY, " +
                            "script_name TEXT, " +
                            "executed_at INTEGER)"
                )
                for (sql in sqlStatements) {
                    db.execSQL(sql)
                }
                db.execSQL(
                    "INSERT INTO migration_history (version, script_name, executed_at) VALUES (?, ?, ?)",
                    arrayOf<Any>(version, scriptName, System.currentTimeMillis())
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Throwable) {
            FileLogger.e(FileMigration::class.simpleName, "迁移执行失败已回滚: $scriptName (v$version)", e)
            throw e
        }
        FileLogger.i("MigrationLoader", "Applied migration: $scriptName")
    }
}

object MigrationLoader {
    fun loadMigrations(context: Context): Array<Migration> {
        val assetManager = context.assets
        val migrationsDir = "migrations"
        val files = runCatching { assetManager.list(migrationsDir) }.getOrNull()
        if (files == null) {
            FileLogger.w("MigrationLoader", "迁移目录不可用: $migrationsDir")
            return emptyArray()
        }

        val migrations = mutableListOf<Migration>()

        // File format: {version}_{description}.sql, e.g., "7_add_workspace_path.sql"
        for (fileName in files) {
            if (!fileName.endsWith(".sql")) continue

            val versionStr = fileName.substringBefore('_')
            val version = versionStr.toIntOrNull()
            if (version == null) {
                FileLogger.w("MigrationLoader", "跳过无法解析版本的迁移文件: $fileName")
                continue
            }

            val sqlContent = runCatching {
                assetManager.open("$migrationsDir/$fileName").bufferedReader().use { it.readText() }
            }.getOrElse {
                FileLogger.w("MigrationLoader", "读取迁移文件失败: $fileName", it)
                continue
            }

            val statements = SqlScriptSplitter.split(sqlContent)

            migrations.add(FileMigration(version, fileName, statements))
        }

        FileLogger.i("MigrationLoader", "共加载 ${migrations.size} 个迁移文件")
        return migrations.toTypedArray()
    }
}
