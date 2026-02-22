package com.phoneagent.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ========== ENTITIES ==========

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String = ""
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val command: String,
    val status: String, // pending, running, completed, failed
    val stepsTaken: Int = 0,
    val result: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "notifications_log")
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val appName: String,
    val title: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis(),
    val wasActedOn: Boolean = false
)

@Entity(tableName = "preferences")
data class PreferenceEntity(
    @PrimaryKey val key: String,
    val value: String
)

@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val command: String,
    val cronExpression: String,
    val lastRun: Long = 0,
    val isActive: Boolean = true
)

// ========== DAOs ==========

@Dao
interface ConversationDao {
    @Insert
    suspend fun insert(entity: ConversationEntity): Long

    @Query("SELECT * FROM conversations ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<ConversationEntity>

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int
}

@Dao
interface TaskDao {
    @Insert
    suspend fun insert(entity: TaskEntity): Long

    @Update
    suspend fun update(entity: TaskEntity)

    @Query("SELECT * FROM tasks ORDER BY timestamp DESC LIMIT 20")
    suspend fun getRecent(): List<TaskEntity>

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()
}

@Dao
interface NotificationDao {
    @Insert
    suspend fun insert(entity: NotificationEntity): Long

    @Query("SELECT * FROM notifications_log ORDER BY timestamp DESC LIMIT 20")
    suspend fun getRecent(): List<NotificationEntity>

    @Delete
    suspend fun delete(entity: NotificationEntity)

    @Query("DELETE FROM notifications_log")
    suspend fun deleteAll()
}

@Dao
interface PreferenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(entity: PreferenceEntity)

    @Query("SELECT * FROM preferences WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): PreferenceEntity?

    @Query("SELECT * FROM preferences")
    suspend fun getAll(): List<PreferenceEntity>

    @Query("DELETE FROM preferences")
    suspend fun deleteAll()
}

@Dao
interface ScheduledTaskDao {
    @Insert
    suspend fun insert(entity: ScheduledTaskEntity): Long

    @Update
    suspend fun update(entity: ScheduledTaskEntity)

    @Delete
    suspend fun delete(entity: ScheduledTaskEntity)

    @Query("SELECT * FROM scheduled_tasks WHERE isActive = 1")
    suspend fun getActive(): List<ScheduledTaskEntity>

    @Query("DELETE FROM scheduled_tasks")
    suspend fun deleteAll()
}

// ========== DATABASE ==========

@Database(
    entities = [
        ConversationEntity::class,
        TaskEntity::class,
        NotificationEntity::class,
        PreferenceEntity::class,
        ScheduledTaskEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun taskDao(): TaskDao
    abstract fun notificationDao(): NotificationDao
    abstract fun preferenceDao(): PreferenceDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "phoneagent_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// ========== MEMORY MANAGER ==========

class ConversationMemory(private val db: AppDatabase) {

    suspend fun saveMessage(role: String, content: String, sessionId: String = "") {
        withContext(Dispatchers.IO) {
            db.conversationDao().insert(
                ConversationEntity(role = role, content = content, sessionId = sessionId)
            )
        }
    }

    suspend fun getFormattedHistory(): JSONArray {
        return withContext(Dispatchers.IO) {
            val messages = db.conversationDao().getRecent(50).reversed()
            val array = JSONArray()
            for (msg in messages) {
                val obj = JSONObject()
                obj.put("role", msg.role)
                obj.put("content", msg.content)
                array.put(obj)
            }
            array
        }
    }

    suspend fun saveTask(command: String, status: String, steps: Int, result: String): Long {
        return withContext(Dispatchers.IO) {
            db.taskDao().insert(
                TaskEntity(command = command, status = status, stepsTaken = steps, result = result)
            )
        }
    }

    suspend fun savePreference(key: String, value: String) {
        withContext(Dispatchers.IO) {
            db.preferenceDao().set(PreferenceEntity(key = key, value = value))
        }
    }

    suspend fun getPreference(key: String): String? {
        return withContext(Dispatchers.IO) {
            db.preferenceDao().get(key)?.value
        }
    }

    suspend fun getAllPreferences(): Map<String, String> {
        return withContext(Dispatchers.IO) {
            db.preferenceDao().getAll().associate { it.key to it.value }
        }
    }

    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            db.conversationDao().deleteAll()
            db.taskDao().deleteAll()
            db.notificationDao().deleteAll()
            db.preferenceDao().deleteAll()
            db.scheduledTaskDao().deleteAll()
        }
    }

    suspend fun exportHistory(): String {
        return withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            sb.appendLine("=== PhoneAgent Conversation Export ===")
            sb.appendLine("Exported: ${java.util.Date()}")
            sb.appendLine()
            val messages = db.conversationDao().getRecent(1000).reversed()
            for (msg in messages) {
                val date = java.util.Date(msg.timestamp)
                sb.appendLine("[$date] ${msg.role.uppercase()}: ${msg.content}")
                sb.appendLine("---")
            }
            sb.toString()
        }
    }

    suspend fun getRecentNotificationsFormatted(): String {
        return withContext(Dispatchers.IO) {
            val notifications = db.notificationDao().getRecent()
            if (notifications.isEmpty()) return@withContext "No recent notifications."
            val sb = StringBuilder("Recent Notifications:\n")
            for (n in notifications) {
                val date = java.util.Date(n.timestamp)
                sb.appendLine("[$date] ${n.appName}: ${n.title} - ${n.body}")
            }
            sb.toString()
        }
    }
}
