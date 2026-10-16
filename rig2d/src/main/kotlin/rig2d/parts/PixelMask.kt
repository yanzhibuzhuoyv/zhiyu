package rig2d.parts

/** Immutable packed mask. No connected component, area or anatomy count pruning. */
class PixelMask private constructor(val width: Int, val height: Int, private val words: LongArray) {
    val count: Int = words.sumOf(java.lang.Long::bitCount)
    private val size = width * height

    operator fun get(index: Int): Boolean {
        require(index in 0 until size) { "像素编号越界" }
        return words[index ushr 6] and (1L shl (index and 63)) != 0L
    }
    fun toBooleans(): BooleanArray = BooleanArray(size) { get(it) }
    fun union(other: PixelMask): PixelMask = combine(other) { a, b -> a or b }
    fun intersect(other: PixelMask): PixelMask = combine(other) { a, b -> a and b }
    fun subtract(other: PixelMask): PixelMask = combine(other) { a, b -> a and b.inv() }

    private fun combine(other: PixelMask, operation: (Long, Long) -> Long): PixelMask {
        require(width == other.width && height == other.height) { "遮罩尺寸不一致" }
        return PixelMask(width, height, LongArray(words.size) { operation(words[it], other.words[it]) })
    }
    override fun equals(other: Any?): Boolean = other is PixelMask && width == other.width &&
        height == other.height && words.contentEquals(other.words)
    override fun hashCode(): Int = 31 * (31 * width + height) + words.contentHashCode()

    companion object {
        const val MAX_SIDE = 1536
        const val MAX_PIXELS = 4_000_000
        internal fun checkedCount(width: Int, height: Int): Int {
            require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "图像最长边超出1..$MAX_SIDE" }
            val count = width.toLong() * height.toLong()
            require(count <= MAX_PIXELS) { "像素数量超出$MAX_PIXELS" }
            return count.toInt()
        }
        fun fromIndices(width: Int, height: Int, indices: IntArray): PixelMask {
            val count = checkedCount(width, height)
            require(indices.size <= count) { "像素编号列表过长" }
            val words = LongArray((count + 63) / 64)
            indices.forEach { index ->
                require(index in 0 until count) { "像素编号越界" }
                words[index ushr 6] = words[index ushr 6] or (1L shl (index and 63))
            }
            return PixelMask(width, height, words)
        }
        fun fromBooleans(width: Int, height: Int, pixels: BooleanArray): PixelMask {
            val count = checkedCount(width, height)
            require(pixels.size == count) { "遮罩像素数量错误" }
            return fromPredicate(width, height, count) { pixels[it] }
        }
        fun fromConfidence(width: Int, height: Int, values: FloatArray, threshold: Float): PixelMask {
            val count = checkedCount(width, height)
            require(values.size == count) { "置信度像素数量错误" }
            require(threshold.isFinite() && threshold in 0f..1f) { "置信度阈值无效" }
            return fromPredicate(width, height, count) { index ->
                val value = values[index]
                require(value.isFinite() && value in 0f..1f) { "置信度含无效数值" }
                value >= threshold
            }
        }
        fun empty(width: Int, height: Int): PixelMask {
            val count = checkedCount(width, height)
            return PixelMask(width, height, LongArray((count + 63) / 64))
        }
        private fun fromPredicate(width: Int, height: Int, count: Int, selected: (Int) -> Boolean): PixelMask {
            val words = LongArray((count + 63) / 64)
            for (index in 0 until count) if (selected(index)) {
                words[index ushr 6] = words[index ushr 6] or (1L shl (index and 63))
            }
            return PixelMask(width, height, words)
        }
    }
}
