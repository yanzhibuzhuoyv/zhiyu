package com.zhiyu.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.zhiyu.app.domain.ConnectionRules
import com.zhiyu.app.domain.UploadedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class DocumentReader(private val context: Context) {
    suspend fun read(uri: Uri): UploadedFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "document.txt"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    require(cursor.getLong(sizeIndex) <= ConnectionRules.MAX_FILE_BYTES) { "单个文件不能超过 5 MB" }
                }
            }
        }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= ConnectionRules.MAX_FILE_BYTES) { "单个文件不能超过 5 MB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException("无法读取选中的文件")
        UploadedFile(ConnectionRules.documentName(name, bytes.size), bytes)
    }
}
