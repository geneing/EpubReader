package com.geneing.epubreader.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "book_folders")
data class BookFolderEntity(
    @PrimaryKey val treeUri: String,
    val displayName: String,
    val addedAt: Long,
    val lastScannedAt: Long? = null,
    val lastScanError: String? = null,
)

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val uri: String,
    val folderUri: String?,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val isAvailable: Boolean = true,
    val lastOpenedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val addedAt: Long = 0L,
    val publicationTitle: String? = null,
    val author: String? = null,
    val coverPath: String? = null,
    @ColumnInfo(defaultValue = "0") val progressPercent: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val metadataLoaded: Boolean = false,
)

@Dao
interface BookFolderDao {
    @Query("SELECT * FROM book_folders ORDER BY displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<BookFolderEntity>>

    @Query("SELECT * FROM book_folders WHERE treeUri = :uri LIMIT 1")
    suspend fun find(uri: String): BookFolderEntity?

    @Upsert
    suspend fun upsert(folder: BookFolderEntity)

    @Query("DELETE FROM book_folders WHERE treeUri = :uri")
    suspend fun delete(uri: String)
}

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE uri = :uri LIMIT 1")
    suspend fun find(uri: String): BookEntity?

    @Query("SELECT * FROM books WHERE folderUri = :folderUri")
    suspend fun findInFolder(folderUri: String): List<BookEntity>

    @Upsert
    suspend fun upsert(book: BookEntity)

    @Upsert
    suspend fun upsertAll(books: List<BookEntity>)

    @Query("UPDATE books SET isAvailable = 0 WHERE folderUri = :folderUri")
    suspend fun markFolderBooksUnavailable(folderUri: String)

    @Query("UPDATE books SET lastOpenedAt = :openedAt WHERE uri = :uri")
    suspend fun markOpened(uri: String, openedAt: Long)

    @Query("UPDATE books SET publicationTitle = :title, author = :author, coverPath = :coverPath, metadataLoaded = 1 WHERE uri = :uri")
    suspend fun updatePublicationInfo(uri: String, title: String?, author: String?, coverPath: String?)

    @Query("UPDATE books SET progressPercent = :progressPercent WHERE uri = :uri")
    suspend fun updateProgress(uri: String, progressPercent: Double)

    @Query("DELETE FROM books WHERE folderUri = :folderUri")
    suspend fun deleteForFolder(folderUri: String)

    @Query("DELETE FROM books WHERE uri = :uri")
    suspend fun delete(uri: String)
}

@Database(
    entities = [BookFolderEntity::class, BookEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun folders(): BookFolderDao
    abstract fun books(): BookDao

    companion object {
        @Volatile
        private var instance: LibraryDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN lastOpenedAt INTEGER")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN addedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN publicationTitle TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN author TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN coverPath TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN progressPercent REAL NOT NULL DEFAULT 0")
                db.execSQL("UPDATE books SET addedAt = lastModified WHERE addedAt = 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN metadataLoaded INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): LibraryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                LibraryDatabase::class.java,
                "epubreader-library.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }
    }
}
