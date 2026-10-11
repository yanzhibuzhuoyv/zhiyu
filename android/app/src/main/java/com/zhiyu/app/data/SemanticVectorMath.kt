package com.zhiyu.app.data

import kotlin.math.sqrt

object SemanticVectorMath {
    fun normalize(value: FloatArray, dimensions: Int = 512): FloatArray {
        require(value.size == dimensions && value.all { it.isFinite() }) { "语义向量尺寸或数值无效" }
        val norm = sqrt(value.sumOf { it.toDouble() * it })
        require(norm > 1e-12 && norm.isFinite()) { "语义向量为空" }
        return FloatArray(value.size) { (value[it] / norm).toFloat() }
    }
}
