package com.zhiyu.app.data

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.UUID

/** Test files, preferences and SQLite databases must all stay out of the production namespace. */
internal class NativeV4Context(base:Context):ContextWrapper(base),AutoCloseable {
    private val prefix="v4_${UUID.randomUUID().toString().replace("-", "")}_"
    private val root=File(base.filesDir,prefix).apply { check(mkdirs()) }
    private val preferences=mutableSetOf<String>()
    override fun getApplicationContext():Context=this
    override fun getFilesDir()=root
    override fun getDatabasePath(name:String):File {
        require(Regex("[a-zA-Z0-9_-]+\\.sqlite3").matches(name))
        return File(File(root,"databases").apply { mkdirs() },name)
    }
    override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?):SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
    override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,handler:DatabaseErrorHandler?):SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath,factory,handler)
    override fun deleteDatabase(name:String)=SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    override fun getSharedPreferences(name:String,mode:Int)=baseContext.getSharedPreferences(prefix+name,mode).also { preferences.add(prefix+name) }
    override fun close() { preferences.forEach { baseContext.deleteSharedPreferences(it) } }
}
