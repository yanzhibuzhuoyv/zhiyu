package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.zhiyu.app.domain.UploadedFile
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.DeflaterOutputStream

class NativeDocumentParserTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val parser = NativeDocumentParser(context)
    @Test fun textAndMarkdownRetainChineseAndWhitespaceWithoutCallingAnyServer() {
        val original = "\n生日是九月十八。\n最后一个标记。\n"
        for (name in listOf("test.txt", "test.md")) assertEquals(original, parser.parse(UploadedFile(name, original.toByteArray())))
        assertThrows(IllegalArgumentException::class.java) { parser.parse(UploadedFile("bad.txt", byteArrayOf(0xFF.toByte()))) }
    }
    @Test fun docxReadsChineseParagraphsAndEscapedText() {
        val content = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>原文 &amp; 武器</w:t></w:r></w:p></w:body></w:document>"
        assertEquals("原文 & 武器\n", parser.parse(UploadedFile("test.docx", zip(content))))
    }
    @Test fun declarationsAndMissingDocxBodiesAreRejected() {
        val xml = "<!DOCTYPE test [<!ENTITY external SYSTEM 'https://example.com/key'>]><test><t>&external;</t></test>"
        assertThrows(IllegalArgumentException::class.java) { parser.parse(UploadedFile("bad.docx", zip(xml))) }
        assertThrows(IllegalArgumentException::class.java) { parser.parse(UploadedFile("bad.pdf", "not a PDF".toByteArray())) }
    }
    @Test fun pdfExtractsTheFullTextOnThePhone() {
        PDFBoxResourceLoader.init(context)
        val bytes = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage(); document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12f); stream.newLineAtOffset(50f, 700f)
                stream.showText("Birthday September 18. ORIGINAL-END-761"); stream.endText()
            }
            document.save(bytes)
        }
        val text = parser.parse(UploadedFile("test.pdf", bytes.toByteArray()))
        assertTrue(text.contains("Birthday September 18. ORIGINAL-END-761"))
    }
    @Test fun compressedPdfCannotExhaustTheChatProcessAndNormalTextStillWorks() {
        val pid = android.os.Process.myPid()
        val packed = ByteArrayOutputStream()
        DeflaterOutputStream(packed).use { stream ->
            stream.write("BT /F1 12 Tf 50 700 Td (".toByteArray())
            val block = ByteArray(65536) { 'A'.code.toByte() }
            repeat(1024) { stream.write(block) }
            stream.write(") Tj ET".toByteArray())
        }
        val pdf = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        fun write(text: String) { pdf.write(text.toByteArray(Charsets.US_ASCII)) }
        fun obj(number: Int, body: String) { offsets.add(pdf.size()); write("$number 0 obj\n$body\nendobj\n") }
        write("%PDF-1.4\n")
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>")
        obj(4, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        offsets.add(pdf.size())
        write("5 0 obj\n<< /Length ${packed.size()} /Filter /FlateDecode >>\nstream\n")
        pdf.write(packed.toByteArray()); write("\nendstream\nendobj\n")
        val xref = pdf.size()
        write("xref\n0 6\n0000000000 65535 f \n")
        offsets.forEach { write("%010d 00000 n \n".format(java.util.Locale.ROOT, it)) }
        write("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        assertTrue(pdf.size() < 100_000)
        val began = System.nanoTime()
        assertThrows(IllegalArgumentException::class.java) { parser.parse(UploadedFile("compressed-limit.pdf", pdf.toByteArray())) }
        assertEquals(pid, android.os.Process.myPid())
        assertEquals("完整原文仍可导入", parser.parse(UploadedFile("after.txt", "完整原文仍可导入".toByteArray())))
        nativeProbeReport("PDF_ISOLATION_REPORT compressed_bytes=${pdf.size()} expanded_literal_bytes=67108864 parent_alive=true elapsed_ms=${(System.nanoTime() - began) / 1_000_000.0}")
    }
    private fun zip(content: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { pack ->
            pack.putNextEntry(ZipEntry("word/document.xml")); pack.write(content.toByteArray()); pack.closeEntry()
        }
        return bytes.toByteArray()
    }
}
