package com.lingion.mailgofer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [CachedMessage::class], version = 1, exportSchema = false)
abstract class MailGoferDb : RoomDatabase() {
    abstract fun cachedMessageDao(): CachedMessageDao

    companion object {
        @Volatile private var instance: MailGoferDb? = null

        fun get(context: Context): MailGoferDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MailGoferDb::class.java, "mailgofer.db")
                .fallbackToDestructiveMigration() // v1 无存量,后续版本禁用此行并写迁移
                .build()
                .also { instance = it }
        }
    }
}
