package com.zhiyu.app.domain

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Real encoder outputs only. Float32 storage is versioned independently of immutable originals. */
object LocalSemanticVectorRules {
    const val FORMAT = "l2-f32-le-v1"
    fun normalized(vector: FloatArray, dimensions: Int): FloatArray {
        require(dimensions in 1..4096 && vector.size == dimensions && vector.all { it.isFinite() }) { "语义向量格式不正确" }
        val norm = sqrt(vector.sumOf { it.toDouble() * it })
        require(norm > 1e-12 && norm.isFinite()) { "语义向量为空" }
        return FloatArray(dimensions) { (vector[it] / norm).toFloat() }
    }
    fun encode(vector: FloatArray, dimensions: Int): ByteArray {
        val result = ByteBuffer.allocate(dimensions * 4).order(ByteOrder.LITTLE_ENDIAN)
        normalized(vector, dimensions).forEach(result::putFloat)
        return result.array()
    }
    fun decode(bytes: ByteArray, dimensions: Int): FloatArray {
        require(bytes.size == dimensions * 4) { "语义向量长度不一致" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dimensions) { buffer.float.also { value -> require(value.isFinite()) } }
    }
    fun score(bytes: ByteArray, queries: List<FloatArray>, dimensions: Int): Double {
        require(bytes.size == dimensions * 4 && queries.size in 1..8 && queries.all { it.size == dimensions }) { "语义索引维度不一致" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val scores = DoubleArray(queries.size)
        var norm = 0.0
        repeat(dimensions) { component ->
            val value = buffer.float
            require(value.isFinite()) { "语义索引向量损坏" }
            norm += value * value
            queries.forEachIndexed { index, query -> scores[index] += value * query[component] }
        }
        require(norm in 0.98..1.02) { "语义索引向量归一化校验失败" }
        return scores.max().coerceIn(-1.0, 1.0)
    }
}
