package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.ConnectionRules
import com.zhiyu.app.domain.UploadedFile
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/** Bounded local extraction; imported documents never require an app server. */
class NativeDocumentParser(private val context: Context) {
    companion object { private const val MAX_TEXT = 2_000_000; private const val MAX_EXPANDED = 20 * 1024 * 1024 }
    fun parse(file: UploadedFile): String {
        val name = ConnectionRules.documentName(file.name, file.bytes.size)
        val text = try { when (name.substringAfterLast('.').lowercase()) {
            "txt", "md" -> utf8(file.bytes).removePrefix("\uFEFF")
            "docx" -> docx(file.bytes)
            "pdf" -> pdf(file.bytes)
            else -> throw IllegalArgumentException("请选择 TXT、Markdown、PDF 或 DOCX")
        } } catch (_: java.io.IOException) { throw IllegalArgumentException("文档无法读取，请检查文件后重试") }
        catch (_: org.xmlpull.v1.XmlPullParserException) { throw IllegalArgumentException("DOCX 正文格式无效") }
        require(text.length <= MAX_TEXT && text.isNotBlank()) { "文档没有可读文字或超过 200 万字符；扫描图片须先识别文字" }
        return text
    }
    private fun pdf(bytes: ByteArray): String {
        require(bytes.take(5).toByteArray().toString(Charsets.US_ASCII) == "%PDF-") { "PDF 格式无效" }
        return IsolatedPdfParser.parse(context, bytes)
    }
    private fun docx(bytes: ByteArray): String {
        var document: ByteArray? = null
        var expanded = 0
        var entries = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val buffer = ByteArray(8192)
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 2000) { "DOCX 包含过多文件" }
                val output = if (entry.name == "word/document.xml") ByteArrayOutputStream() else null
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    expanded += count
                    require(expanded <= MAX_EXPANDED) { "DOCX 解压内容过大" }
                    output?.write(buffer, 0, count)
                }
                if (output != null) { require(document == null) { "DOCX 正文重复" }; document = output.toByteArray() }
                zip.closeEntry()
            }
        }
        val xml = document ?: throw IllegalArgumentException("DOCX 缺少正文")
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(StringReader(utf8(xml)))
        val result = StringBuilder()
        var inText = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.DOCDECL -> throw IllegalArgumentException("DOCX 不允许外部声明")
                XmlPullParser.START_TAG -> {
                    if (parser.name == "t") inText = true
                    if (parser.name == "tab") result.append('\t')
                    if (parser.name == "br") result.append('\n')
                }
                XmlPullParser.END_TAG -> { if (parser.name == "t") inText = false; if (parser.name == "p") result.append('\n') }
                XmlPullParser.TEXT, XmlPullParser.ENTITY_REF -> if (inText) result.append(parser.text ?: "")
            }
            require(result.length <= MAX_TEXT) { "DOCX 正文过长" }
            parser.nextToken()
        }
        return result.toString()
    }
    private fun utf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("文本须使用 UTF-8 编码") }
}
