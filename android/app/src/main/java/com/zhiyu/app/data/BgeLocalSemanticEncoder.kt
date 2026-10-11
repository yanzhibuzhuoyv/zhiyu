package com.zhiyu.app.data

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONException
import java.io.IOException
import java.nio.LongBuffer
import java.security.MessageDigest

/** One bounded process-wide CPU session. No HTTP, provider key or runtime model download. */
class BgeLocalSemanticEncoder(context: Context) : LocalSemanticEncoder {
    private val app = context.applicationContext
    private val windowLock = Mutex()
    private var cursor: SemanticDocumentCursor? = null
    override val dimensions = 512
    override val modelIdentity = IDENTITY
    companion object {
        const val MODEL_SHA = "a20c8811f4c52f72f31783c615b6a6ea2eaac6b8b52eea5a107e004f4cc632f7"
        const val VOCAB_SHA = "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c"
        const val CONFIG_SHA = "3853a7979202c348751b753e36f579c41d8da7d36af617d3d907e1fc9b441f2a"
        const val TOKENIZER_CONFIG_SHA = "e6f3b96db926a37d4039995fbf5ad17de158dfb8f6343d607e4dbaad18d75f5a"
        const val QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："
        val IDENTITY = "bge-small-zh-v1.5:7999e1d3359715c523056ef9478215996d62a620:qint8:$MODEL_SHA:wp-utf16-v1:$VOCAB_SHA:$CONFIG_SHA:$TOKENIZER_CONFIG_SHA:case-preserved:accents-preserved:cls:l2:prefix-sha256:" +
            MessageDigest.getInstance("SHA-256").digest(QUERY_PREFIX.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + ":d512:chunk510-source8192-v2"
        private val sharedLock = Any()
        private var sharedTokenizer: WordPieceTokenizer? = null
        private var sharedRuntime: Runtime? = null
    }
    private class Runtime(val session: OrtSession, val options: OrtSession.SessionOptions) {
        val inferenceLock = Any()
    }
    override fun isAvailable(): Boolean = try {
        validateManifest()
        app.assets.open("semantic/bge-small-zh-v1.5-int8.onnx").use { it.read() >= 0 }
    } catch (_: IOException) { false } catch (_: IllegalArgumentException) { false } catch (_: JSONException) { false }

    override fun documentChunkCount(text: String): Int = tokenizer().chunks(text).count()
    override suspend fun encodeDocument(text: String): FloatArray = withContext(Dispatchers.IO) {
        val chunks = tokenizer().chunks(text).take(2).toList()
        require(chunks.isNotEmpty()) { "没有可编码的文本" }
        if (chunks.size > 1) throw SemanticInputTooLong("原话超过单段 token 或原文窗口，请使用独立分段索引；没有截断原话")
        encode(chunks.single(), emptyList()).embedding
    }
    override suspend fun encodeQuery(query: String): FloatArray = withContext(Dispatchers.IO) {
        val prefix = queryPrefix()
        val chunks = tokenizer().chunks(query, 510 - prefix.size).take(2).toList()
        require(chunks.isNotEmpty()) { "查询内容为空" }
        if (chunks.size > 1) throw SemanticInputTooLong("查询超过单段长度，请使用多段查询；没有截断查询")
        encode(chunks.single(), prefix).embedding
    }
    override suspend fun encodeQueryChunks(query: String): List<SemanticChunk> = withContext(Dispatchers.IO) {
        val prefix = queryPrefix()
        val chunks = tokenizer().chunks(query, 510 - prefix.size).take(9).toList()
        require(chunks.isNotEmpty()) { "查询内容为空" }
        if (chunks.size > 8) throw SemanticInputTooLong("查询超过 8 个语义分段，请缩短查询；没有进行全文语义编码")
        chunks.map { currentCoroutineContext().ensureActive(); encode(it, prefix) }
    }
    override suspend fun encodeDocumentChunks(text: String, startChunk: Int, maxChunks: Int): List<SemanticChunk> =
        withContext(Dispatchers.IO) { windowLock.withLock {
            require(startChunk >= 0 && maxChunks in 1..8) { "语义索引批次范围无效" }
            val coroutine = currentCoroutineContext()
            val activeCursor = cursor ?: SemanticDocumentCursor(tokenizer()).also { cursor = it }
            activeCursor.encodeWindow(text, startChunk, maxChunks, encode = { encode(it, emptyList()) },
                ensureActive = { coroutine.ensureActive() })
        } }
    private fun queryPrefix() = tokenizer().tokenize(QUERY_PREFIX).map { it.id }.toList()
    private fun encode(chunk: SemanticTokenChunk, prefix: List<Int>): SemanticChunk {
        val tokenizer = tokenizer()
        val ids = longArrayOf(tokenizer.clsId.toLong()) + prefix.map { it.toLong() }.toLongArray() +
            chunk.tokens.map { it.id.toLong() }.toLongArray() + longArrayOf(tokenizer.sepId.toLong())
        require(ids.size in 3..512) { "语义编码输入超过模型窗口" }
        val runtime = runtime()
        val vector = try { synchronized(runtime.inferenceLock) { run(runtime.session, ids) } }
            catch (failure: OrtException) { throw SemanticEncodingUnavailable("本地语义模型运行失败", failure) }
        return SemanticChunk(chunk.chunkIndex, chunk.tokenStart, chunk.tokenEndExclusive,
            chunk.textStart, chunk.textEndExclusive, SemanticVectorMath.normalize(vector))
    }
    private fun run(session: OrtSession, ids: LongArray): FloatArray {
        val environment = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1, ids.size.toLong())
        val tensors = linkedMapOf<String, OnnxTensor>()
        try {
            tensors["input_ids"] = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape)
            tensors["attention_mask"] = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape)
            tensors["token_type_ids"] = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size)), shape)
            return session.run(tensors).use { result ->
                val output = result[0] as? OnnxTensor ?: throw SemanticEncodingUnavailable("本地语义模型输出类型无效")
                if (!output.info.shape.contentEquals(longArrayOf(1, 512))) throw SemanticEncodingUnavailable("本地语义模型输出尺寸无效")
                val buffer = output.floatBuffer ?: throw SemanticEncodingUnavailable("本地语义模型输出不是浮点向量")
                if (buffer.remaining() != 512) throw SemanticEncodingUnavailable("本地语义向量长度无效")
                FloatArray(512).also { buffer.get(it) }
            }
        } finally { tensors.values.forEach { it.close() } }
    }
    private fun tokenizer(): WordPieceTokenizer = synchronized(sharedLock) {
        sharedTokenizer ?: try {
            validateManifest()
            verify("config.json", CONFIG_SHA, 4096)
            val config = JSONObject(String(verify("tokenizer_config.json", TOKENIZER_CONFIG_SHA, 4096), Charsets.UTF_8))
            require(!config.getBoolean("do_lower_case") && config.isNull("strip_accents") && config.getBoolean("tokenize_chinese_chars")) { "语义分词配置不匹配" }
            val words = String(verify("vocab.txt", VOCAB_SHA, 128 * 1024), Charsets.UTF_8).lineSequence().toList().let { if (it.last().isEmpty()) it.dropLast(1) else it }
            require(words.size == 21128) { "语义词表尺寸不匹配" }
            WordPieceTokenizer(words).also { sharedTokenizer = it }
        } catch (failure: IOException) { throw SemanticEncodingUnavailable("手机未安装中文语义模型，请安装包含模型的版本", failure) }
          catch (failure: IllegalArgumentException) { throw SemanticEncodingUnavailable("中文语义模型包校验失败", failure) }
          catch (failure: JSONException) { throw SemanticEncodingUnavailable("中文语义模型包标注损坏", failure) }
    }
    private fun runtime(): Runtime = synchronized(sharedLock) {
        sharedRuntime ?: try {
            tokenizer()
            val model = verify("bge-small-zh-v1.5-int8.onnx", MODEL_SHA, 24_006_596, exact = true)
            val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2); setInterOpNumThreads(2) }
            try { Runtime(OrtEnvironment.getEnvironment().createSession(model, options), options).also { sharedRuntime = it } }
            catch (failure: Throwable) { options.close(); throw failure }
        } catch (failure: IOException) { throw SemanticEncodingUnavailable("手机未安装中文语义权重", failure) }
          catch (failure: OrtException) { throw SemanticEncodingUnavailable("手机无法加载中文语义模型", failure) }
    }
    private fun validateManifest() {
        val metadata = JSONObject(String(read("manifest.json", 16 * 1024), Charsets.UTF_8))
        require(metadata.getJSONObject("model").getString("sha256") == MODEL_SHA &&
            metadata.getJSONObject("vocab").getString("sha256") == VOCAB_SHA && metadata.getInt("dimension") == 512 &&
            metadata.getInt("max_tokens") == 512 && metadata.getString("pooling") == "CLS" &&
            metadata.getString("normalization") == "L2" && metadata.getString("query_prefix") == QUERY_PREFIX) { "语义模型包版本不匹配" }
    }
    private fun verify(name: String, hash: String, limit: Int, exact: Boolean = false): ByteArray = read(name, limit, exact).also {
        require(MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { byte -> "%02x".format(byte) } == hash) { "语义模型文件校验失败：$name" }
    }
    private fun read(name: String, limit: Int, exact: Boolean = false): ByteArray = app.assets.open("semantic/$name").use { stream ->
        val bytes = ByteArray(limit)
        var offset = 0
        while (offset < limit) {
            val count = stream.read(bytes, offset, limit - offset)
            if (count < 0) break
            require(count > 0) { "语义模型文件读取无进展" }
            offset += count
        }
        require(stream.read() == -1 && (!exact || offset == limit)) { "语义模型文件大小不匹配" }
        if (offset == limit) bytes else bytes.copyOf(offset)
    }
}
