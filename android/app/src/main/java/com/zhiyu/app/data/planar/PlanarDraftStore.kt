package com.zhiyu.app.data.planar

import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** A private edit checkpoint, never an active published character package. */
data class PlanarDraftCheckpoint(val bindingSha: String, val basePackageSha: String?, val width: Int,
    val height: Int, val sourcePng: ByteArray, val draftJson: String)

class PlanarDraftStore(private val root: File) {
    private val active=File(root,"draft.checkpoint")
    private val pending=File(root,"draft.pending")
    val identity:String get()=root.absoluteFile.normalize().path

    fun load(bindingSha:String,basePackageSha:String?):PlanarDraftCheckpoint? = synchronized(lock) {
        identities(bindingSha,basePackageSha);checkFiles()
        if(!active.exists())return@synchronized null
        require(active.length() in 1..MAX_FILE){"PLANAR_DRAFT_BUDGET"}
        try {DataInputStream(BufferedInputStream(active.inputStream())).use {input->
            require(input.readInt()==MAGIC&&input.readInt()==1){"PLANAR_DRAFT_FORMAT"}
            val owner=input.readUTF();val parent=if(input.readBoolean())input.readUTF()else null
            identities(owner,parent)
            if(owner!=bindingSha||parent!=basePackageSha)return@synchronized null
            val width=input.readInt();val height=input.readInt();dimensions(width,height)
            val sourceSize=input.readInt();val textSize=input.readInt()
            require(sourceSize in 1..MAX_SOURCE&&textSize in 1..MAX_TEXT){"PLANAR_DRAFT_BUDGET"}
            require(sourceSize.toLong()+textSize+HEADER<=MAX_FILE){"PLANAR_DRAFT_BUDGET"}
            val sourceSha=input.readUTF();val textSha=input.readUTF();identities(sourceSha,textSha)
            val source=ByteArray(sourceSize);input.readFully(source)
            val text=ByteArray(textSize);input.readFully(text)
            require(input.read()==-1&&sha(source)==sourceSha&&sha(text)==textSha){"PLANAR_DRAFT_INTEGRITY"}
            val decoded=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(text)).toString()
            PlanarDraftCheckpoint(owner,parent,width,height,source,decoded)
        }}catch(failure:IOException){throw IllegalArgumentException("PLANAR_DRAFT_INCOMPLETE",failure)}
    }

    fun save(value:PlanarDraftCheckpoint,checkCancelled:()->Unit={}):Unit = synchronized(lock) {
        identities(value.bindingSha,value.basePackageSha);dimensions(value.width,value.height)
        require(value.sourcePng.size in 1..MAX_SOURCE&&value.draftJson.length in 1..MAX_TEXT){"PLANAR_DRAFT_BUDGET"}
        val text=value.draftJson.toByteArray(Charsets.UTF_8)
        require(text.size in 1..MAX_TEXT&&value.sourcePng.size.toLong()+text.size+HEADER<=MAX_FILE){"PLANAR_DRAFT_BUDGET"}
        checkCancelled();checkFiles();require(root.isDirectory||root.mkdirs()){"PLANAR_DRAFT_DIRECTORY"};checkFiles()
        try {
            FileOutputStream(pending).use {file->
                val out=DataOutputStream(BufferedOutputStream(file))
                out.writeInt(MAGIC);out.writeInt(1);out.writeUTF(value.bindingSha)
                out.writeBoolean(value.basePackageSha!=null);value.basePackageSha?.let(out::writeUTF)
                out.writeInt(value.width);out.writeInt(value.height);out.writeInt(value.sourcePng.size);out.writeInt(text.size)
                out.writeUTF(sha(value.sourcePng));out.writeUTF(sha(text))
                write(out,value.sourcePng,checkCancelled);write(out,text,checkCancelled);out.flush();file.fd.sync()
            }
            checkCancelled();checkFiles()
            Files.move(pending.toPath(),active.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally {if(Files.isRegularFile(pending.toPath(),NOFOLLOW_LINKS))Files.deleteIfExists(pending.toPath())}
        Unit
    }

    /** Active character files and original photos are outside these two owned paths. */
    fun clear()=synchronized(lock) {
        checkFiles();Files.deleteIfExists(active.toPath());Files.deleteIfExists(pending.toPath());Unit
    }
    private fun write(out:DataOutputStream,bytes:ByteArray,checkCancelled:()->Unit) {
        var offset=0
        while(offset<bytes.size){checkCancelled();val count=minOf(8192,bytes.size-offset);out.write(bytes,offset,count);offset+=count}
    }
    private fun checkFiles() {
        val absolute=root.absoluteFile.normalize()
        require(root.canonicalFile==absolute){"PLANAR_DRAFT_PATH"}
        require(!root.exists()||Files.isDirectory(root.toPath(),NOFOLLOW_LINKS)){"PLANAR_DRAFT_DIRECTORY"}
        listOf(active,pending).forEach {file->require(!Files.exists(file.toPath(),NOFOLLOW_LINKS)||
            Files.isRegularFile(file.toPath(),NOFOLLOW_LINKS)){"PLANAR_DRAFT_PATH"}}
    }
    private fun identities(binding:String,parent:String?) {
        require(SHA.matches(binding)&&(parent==null||SHA.matches(parent))){"PLANAR_DRAFT_IDENTITY"}
    }
    private fun dimensions(width:Int,height:Int) {require(width in 2..1536&&height in 2..1536){"PLANAR_DRAFT_DIMENSIONS"}}
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private companion object {
        val lock=Any();val SHA=Regex("[a-f0-9]{64}")
        const val MAGIC=0x5a595044;const val HEADER=512L
        const val MAX_SOURCE=20*1024*1024;const val MAX_TEXT=32*1024*1024
        const val MAX_FILE=52L*1024*1024+HEADER
    }
}
