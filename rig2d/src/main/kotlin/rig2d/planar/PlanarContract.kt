package rig2d.planar

/** Data projected from studio/contract.json; the core does not invent parameter ranges. */
data class ParameterRange(val minimum: Float, val maximum: Float, val default: Float)

class PlanarContract(
    val maxSide: Int,
    val maxVertices: Int,
    val maxTriangles: Int,
    parameters: Map<String, ParameterRange>,
) {
    private val ranges: Map<String, ParameterRange>
    val parameters: Map<String, ParameterRange> get() = ranges.toMap()

    init {
        require(maxSide in 1..65_536 && maxVertices in 3..65_536 && maxTriangles in 1..131_072) {
            "Contract geometry capacity is invalid"
        }
        require(parameters.size == CHANNELS.size) { "Contract must define the eight animation channels" }
        val snapshot = LinkedHashMap<String, ParameterRange>()
        for ((name, range) in parameters) {
            require(snapshot.size < CHANNELS.size && name in CHANNELS && name !in snapshot) {
                "Contract contains an unsupported or repeated channel"
            }
            require(range.minimum.isFinite() && range.maximum.isFinite() && range.default.isFinite() &&
                range.minimum <= range.maximum && range.default in range.minimum..range.maximum) {
                "Contract contains an invalid parameter range"
            }
            snapshot[name] = range.copy()
        }
        require(snapshot.keys == CHANNELS) { "Contract is missing an animation channel" }
        ranges = snapshot
    }

    internal fun range(name: String): ParameterRange =
        ranges[name] ?: throw IllegalArgumentException("Unknown animation parameter: $name")

    override fun equals(other: Any?): Boolean = other is PlanarContract && maxSide == other.maxSide &&
        maxVertices == other.maxVertices && maxTriangles == other.maxTriangles && ranges == other.ranges

    override fun hashCode(): Int = 31 * (31 * (31 * maxSide + maxVertices) + maxTriangles) + ranges.hashCode()

    companion object {
        internal val CHANNELS = setOf("headX", "headY", "tilt", "blinkLeft", "blinkRight", "mouth", "breath", "sway")
    }
}

class PlanarParameters(val contract: PlanarContract, values: Map<String, Float> = emptyMap()) {
    private val snapshot: Map<String, Float>

    init {
        require(values.size <= PlanarContract.CHANNELS.size) { "Too many animation parameters" }
        val result = contract.parameters.mapValuesTo(LinkedHashMap()) { it.value.default }
        val seen = HashSet<String>()
        for ((name, value) in values) {
            require(seen.size < PlanarContract.CHANNELS.size && seen.add(name)) { "Repeated or excess parameter" }
            val range = contract.range(name)
            require(value.isFinite() && value in range.minimum..range.maximum) { "Parameter outside contract: $name" }
            result[name] = value
        }
        snapshot = result
    }

    fun toMap(): Map<String, Float> = snapshot.toMap()
    operator fun get(name: String): Float = snapshot[name] ?: throw IllegalArgumentException("Unknown animation parameter: $name")
}
