package com.zhiyu.app.data.planar

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import rig2d.planar.ParameterRange
import rig2d.planar.PlanarContract
import rig2d.planar.PlanarMesh

data class PlanarLayer(val id: String, val role: String, val z: Double, val texturePng: ByteArray, val mesh: PlanarMesh)

data class PlanarDocument(
    val width: Int,
    val height: Int,
    val name: String,
    val sourcePng: ByteArray,
    val layers: List<PlanarLayer>,
    val definitionJson: String,
    val contract: PlanarContract,
)

/** Data-only package reader. expectedSourceSha refers to source.png bytes, never the original JPEG. */
class PlanarPackageReader(contractJson: String) {
    private val contractData = objectValue(PlanarJson.parse(contractJson))
    private val parameterData = objectValue(contractData["parameters"])
    val contract: PlanarContract = loadContract()

    fun read(input: InputStream, expectedSourceSha: String? = null, checkCancelled: () -> Unit = {}): PlanarDocument {
        require(expectedSourceSha == null || SHA.matches(expectedSourceSha)) { "PLANAR_EXPECTED_SOURCE_SHA" }
        val scratch = File.createTempFile("zhiyu-planar-", ".zip")
        try {
            spool(input, scratch, checkCancelled)
            val files = readArchive(scratch, checkCancelled)
            val manifest = objectValue(parseBytes(files.getValue("manifest.json")))
            require(manifest.keys == setOf("format", "files")) { "PLANAR_MANIFEST" }
            val format = string(manifest["format"])
            require(format == DESKTOP || format == NATIVE) { "PLANAR_PACKAGE_FORMAT" }
            val hashes = objectValue(manifest["files"])
            require(hashes.keys == files.keys - "manifest.json") { "PLANAR_MANIFEST_FILES" }
            for ((path, digest) in hashes) {
                checkWork(checkCancelled)
                require(string(digest) == sha(files.getValue(path))) { "PLANAR_MANIFEST_HASH" }
            }
            return parseDocument(files, format, expectedSourceSha, checkCancelled)
        } catch (error: IOException) {
            throw IllegalArgumentException("PLANAR_INVALID_ARCHIVE", error)
        } finally {
            Files.deleteIfExists(scratch.toPath())
        }
    }

    /** Exports an Android-native archive; this does not assert Python/OpenCV derivation equivalence. */
    fun write(document: PlanarDocument, checkCancelled: () -> Unit = {}): ByteArray {
        checkWork(checkCancelled)
        require(document.contract == contract && document.width in 2..contract.maxSide && document.height in 2..contract.maxSide) { "PLANAR_DOCUMENT_CONTRACT" }
        require(document.sourcePng.size in 1..MAX_SOURCE && document.layers.size in 1..64) { "PLANAR_DOCUMENT_BUDGET" }
        validName(document.name)
        require(document.definitionJson.length <= MAX_JSON) { "PLANAR_JSON_BUDGET" }
        val files = linkedMapOf("source.png" to document.sourcePng.copyOf())
        val layers = document.layers.map { layer -> nativeLayer(layer, files, checkCancelled) }
        val asset = mapOf(
            "format" to "rig2d-planar-1", "mode" to "front_mesh", "width" to document.width,
            "height" to document.height, "name" to document.name, "sourceSha" to sha(files.getValue("source.png")),
            "layers" to layers, "parameters" to parameterData,
            "capabilities" to mapOf("trueSideViews" to false, "cubismMoc3" to false, "identityVerified" to false,
                "reviewRequired" to true, "generator" to "android-planar-native-v1"),
        )
        files["asset.json"] = PlanarJson.encode(asset).toByteArray(Charsets.UTF_8)
        files["definition.json"] = document.definitionJson.toByteArray(Charsets.UTF_8)
        require(strictUtf8(files.getValue("definition.json")) == document.definitionJson) { "PLANAR_JSON_UNICODE" }
        validateFileBudgets(files)
        parseDocument(files, NATIVE, null, checkCancelled)
        files["manifest.json"] = PlanarJson.encode(mapOf("format" to NATIVE, "files" to files.mapValues { sha(it.value) })).toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((path, bytes) in files.toSortedMap()) {
                checkWork(checkCancelled)
                zip.putNextEntry(ZipEntry(path).apply { time = 1767225600000L })
                var offset = 0
                while (offset < bytes.size) {
                    checkWork(checkCancelled)
                    val count = minOf(8192, bytes.size - offset); zip.write(bytes, offset, count); offset += count
                    require(output.size() <= MAX_PACKAGE) { "PLANAR_COMPRESSED_BUDGET" }
                }
                zip.closeEntry()
            }
        }
        require(output.size() <= MAX_PACKAGE) { "PLANAR_COMPRESSED_BUDGET" }
        return output.toByteArray()
    }

    private fun nativeLayer(layer: PlanarLayer, files: MutableMap<String, ByteArray>, checkCancelled: () -> Unit): Map<String, Any?> {
        require(layer.mesh.contract == contract && layer.texturePng.size in 1..MAX_SOURCE) { "PLANAR_LAYER_CONTRACT" }
        val vertices = layer.mesh.baseVertices()
        val size = png(layer.texturePng, false, checkCancelled)
        val digest = sha(layer.texturePng); val path = "textures/$digest.png"
        if (!files.containsKey(path)) files[path] = layer.texturePng.copyOf()
        return mapOf(
            "id" to layer.id, "name" to layer.id, "role" to layer.role, "z" to layer.z,
            "texture" to path, "textureSha" to digest,
            "bounds" to listOf(vertices.filterIndexed { index, _ -> index % 2 == 0 }.min().toDouble(),
                vertices.filterIndexed { index, _ -> index % 2 == 1 }.min().toDouble(), size.first, size.second),
            "phase" to 0, "strength" to 1, "vertices" to vertices.map { it.toDouble() },
            "uv" to layer.mesh.uv().map { it.toDouble() }, "indices" to layer.mesh.indices().toList(),
            "deltas" to layer.mesh.deltas().mapValues { (_, values) -> values.map { it.toDouble() } },
        )
    }

    private fun parseDocument(files: Map<String, ByteArray>, format: String, expectedSourceSha: String?, checkCancelled: () -> Unit): PlanarDocument {
        require(files.keys.containsAll(setOf("asset.json", "definition.json", "source.png"))) { "PLANAR_INCOMPLETE_PACKAGE" }
        val asset = objectValue(parseBytes(files.getValue("asset.json")))
        val fields = setOf("format", "mode", "width", "height", "name", "sourceSha", "layers", "parameters", "capabilities")
        require(asset.keys == fields || asset.keys == fields + "pixelSha") { "PLANAR_ASSET_FIELDS" }
        require(format != DESKTOP || asset.containsKey("pixelSha")) { "PLANAR_PIXEL_SHA" }
        asset["pixelSha"]?.let { require(SHA.matches(string(it))) { "PLANAR_PIXEL_SHA" } }
        require(asset["format"] == "rig2d-planar-1" && asset["mode"] == "front_mesh") { "PLANAR_ASSET_FORMAT" }
        val width = integer(asset["width"], 2, contract.maxSide); val height = integer(asset["height"], 2, contract.maxSide)
        require(objectValue(asset["parameters"]) == parameterData) { "PLANAR_PARAMETERS_CONTRACT" }
        val capabilities = objectValue(asset["capabilities"])
        require(capabilities["trueSideViews"] == false && capabilities["cubismMoc3"] == false &&
            capabilities["identityVerified"] == false && capabilities["reviewRequired"] == true) { "PLANAR_CAPABILITIES" }
        val source = files.getValue("source.png")
        require(string(asset["sourceSha"]) == sha(source) && (expectedSourceSha == null || expectedSourceSha == sha(source))) { "PLANAR_SOURCE_SHA" }
        val sourceSize = png(source, true, checkCancelled)
        require(sourceSize == width to height) { "PLANAR_SOURCE_DIMENSIONS" }
        val rawLayers = array(asset["layers"])
        require(rawLayers.size in 1..64) { "PLANAR_LAYER_BUDGET" }
        val usedIds = hashSetOf<String>(); val textures = hashSetOf<String>()
        var decodedPixels = 0L // Contract budget counts layer textures; the source is bounded separately by maxSide.
        val layers = rawLayers.map { raw ->
            checkWork(checkCancelled)
            val layer = objectValue(raw)
            require(layer.keys == setOf("id", "name", "role", "z", "texture", "textureSha", "bounds", "phase", "strength", "vertices", "uv", "indices", "deltas")) { "PLANAR_LAYER_FIELDS" }
            val id = string(layer["id"]); require(ID.matches(id) && usedIds.add(id)) { "PLANAR_LAYER_ID" }
            validName(string(layer["name"]))
            val role = string(layer["role"]); require(role in ROLES) { "PLANAR_LAYER_ROLE" }
            val z = number(layer["z"], -1001.0, 100.0)
            number(layer["phase"], 0.0, 6.283); number(layer["strength"], 0.0, 1.0)
            val bounds = array(layer["bounds"]); require(bounds.size == 4) { "PLANAR_LAYER_BOUNDS" }
            val x = integer(bounds[0], 0, width - 1); val y = integer(bounds[1], 0, height - 1)
            val w = integer(bounds[2], 1, width - x); val h = integer(bounds[3], 1, height - y)
            decodedPixels += w.toLong() * h
            require(decodedPixels <= MAX_PIXELS && decodedPixels * 4 <= MAX_TEXTURE_BYTES) { "PLANAR_DECODE_BUDGET" }
            val path = string(layer["texture"]); val digest = string(layer["textureSha"])
            require(SHA.matches(digest) && path == "textures/$digest.png" && files.containsKey(path)) { "PLANAR_TEXTURE_PATH" }
            val texture = files.getValue(path); require(sha(texture) == digest) { "PLANAR_TEXTURE_HASH" }; textures.add(path)
            require(png(texture, false, checkCancelled) == w to h) { "PLANAR_TEXTURE_DIMENSIONS" }
            val vertices = floats(layer["vertices"], contract.maxVertices * 2)
            require(vertices.size % 2 == 0 && vertices.indices.all { index -> vertices[index] in (if (index % 2 == 0) x.toFloat()..(x + w).toFloat() else y.toFloat()..(y + h).toFloat()) }) { "PLANAR_VERTEX_BOUNDS" }
            val uv = floats(layer["uv"], contract.maxVertices * 2)
            val indices = array(layer["indices"]).also { require(it.size <= contract.maxTriangles * 3) { "PLANAR_TRIANGLE_BUDGET" } }.map { integer(it, 0, contract.maxVertices - 1) }.toIntArray()
            val deltas = objectValue(layer["deltas"])
            require(deltas.keys.all { it in parameterData }) { "PLANAR_DELTA_CHANNEL" }
            val mesh = PlanarMesh(contract, vertices, uv, indices, deltas.mapValues { floats(it.value, contract.maxVertices * 2) })
            PlanarLayer(id, role, z, texture, mesh)
        }
        require(files.keys.filter { it.startsWith("textures/") }.toSet() == textures) { "PLANAR_UNUSED_TEXTURE" }
        val definition = strictUtf8(files.getValue("definition.json"))
        validateDefinition(objectValue(PlanarJson.parse(definition)), width, height, format == NATIVE, checkCancelled)
        checkWork(checkCancelled)
        // These arrays are privately owned by this parse and can transfer directly to the document.
        return PlanarDocument(width, height, validName(string(asset["name"])), source, layers.sortedWith(compareBy<PlanarLayer> { it.z }.thenBy { it.id }), definition, contract)
    }

    private fun loadContract(): PlanarContract {
        require(contractData["format"] == "rig2d-planar-1" && contractData["mode"] == "front_mesh" && contractData["parameterInput"] == "reject_out_of_range") { "PLANAR_CONTRACT" }
        require(integer(contractData["maxLayers"], 64, 64) == 64 && integer(contractData["maxSourceBytes"], MAX_SOURCE, MAX_SOURCE) == MAX_SOURCE &&
            integer(contractData["maxTexturePixels"], MAX_PIXELS.toInt(), MAX_PIXELS.toInt()) == MAX_PIXELS.toInt() &&
            integer(contractData["maxTextureBytes"], MAX_TEXTURE_BYTES.toInt(), MAX_TEXTURE_BYTES.toInt()) == MAX_TEXTURE_BYTES.toInt()) { "PLANAR_CONTRACT_BUDGET" }
        require(parameterData.keys == CHANNELS && array(contractData["roles"]).toSet() == ROLES) { "PLANAR_CONTRACT_CHANNELS" }
        val ranges = parameterData.mapValues { (name, raw) ->
            val values = array(raw); val minimum = if (name in setOf("headX", "headY", "tilt", "sway")) -1.0 else 0.0
            require(values == listOf(minimum, 1.0, 0.0)) { "PLANAR_CONTRACT_RANGE" }
            ParameterRange(minimum.toFloat(), 1f, 0f)
        }
        return PlanarContract(integer(contractData["maxSide"], 1536, 1536), integer(contractData["maxVertices"], 1089, 1089), integer(contractData["maxTriangles"], 2048, 2048), ranges)
    }

    private fun validateDefinition(definition: Map<String, Any?>, width: Int, height: Int, native: Boolean, checkCancelled: () -> Unit) {
        if (!native) require(definition.keys.all { it in setOf("anchors", "parts", "subjectMask") }) { "PLANAR_DEFINITION_FIELDS" }
        val anchors = objectValue(definition["anchors"])
        require(anchors.keys == setOf("head", "neck", "eyeLeft", "eyeRight", "mouth")) { "PLANAR_ANCHORS" }
        for ((key, value) in anchors) {
            val values = array(value)
            if (key in setOf("head", "neck")) point(values, width, height)
            else {
                require(values.size == 4) { "PLANAR_RECTANGLE" }; point(values.take(2), width, height)
                val x1 = number(values[2], 0.0, width.toDouble()); val y1 = number(values[3], 0.0, height.toDouble())
                require(x1 - number(values[0], 0.0, width.toDouble()) >= 1 && y1 - number(values[1], 0.0, height.toDouble()) >= 1) { "PLANAR_RECTANGLE" }
            }
        }
        val parts = array(definition["parts"] ?: emptyList<Any?>()); require(parts.size <= 60) { "PLANAR_PART_BUDGET" }
        val ids = hashSetOf("base", "eye_left", "eye_right", "mouth")
        var strokes = 0; var strokePoints = 0
        if (native && definition.containsKey("subjectBrush")) validateBrush(definition["subjectBrush"], width, height).let { strokes += it.first; strokePoints += it.second }
        for (raw in parts) {
            checkWork(checkCancelled)
            val part = objectValue(raw)
            if (!native) require(part.keys.all { it in setOf("id", "name", "role", "z", "polygon", "mask", "pivot", "strength") }) { "PLANAR_PART_FIELDS" }
            val id = string(part["id"]); require(ID.matches(id) && ids.add(id)) { "PLANAR_PART_ID" }
            validName(string(part["name"])); require(part["role"] in ROLES - "base") { "PLANAR_PART_ROLE" }
            number(part["z"], -1000.0, 90.0); number(part["strength"], 0.0, 1.0); point(array(part["pivot"]), width, height)
            if (part.containsKey("mask")) {
                val mask = base64(part["mask"], 8 * 1024 * 1024)
                require(png(mask, false, checkCancelled, allowMaskColor = true) == width to height) { "PLANAR_MASK_DIMENSIONS" }
            } else {
                val polygon = array(part["polygon"]); require(polygon.size in 3..256) { "PLANAR_POLYGON" }
                polygon.forEach { point(array(it), width, height) }
            }
            if (native && part.containsKey("brush")) validateBrush(part["brush"], width, height).let { strokes += it.first; strokePoints += it.second }
            require(strokes <= 512 && strokePoints <= 16384) { "PLANAR_BRUSH_BUDGET" }
        }
        definition["subjectMask"]?.let { value ->
            val mask = base64(value, width * height)
            require(mask.size == width * height && mask.all { it == 0.toByte() || it == 1.toByte() }) { "PLANAR_SUBJECT_MASK" }
        }
    }

    private fun validateBrush(value: Any?, width: Int, height: Int): Pair<Int, Int> {
        val strokes = array(value); require(strokes.size <= 512) { "PLANAR_BRUSH_BUDGET" }; var total = 0
        for (raw in strokes) {
            val stroke = objectValue(raw); require(stroke.keys == setOf("points", "radius", "keep") && stroke["keep"] is Boolean) { "PLANAR_BRUSH_FIELDS" }
            val points = array(stroke["points"]); require(points.size in 1..4096) { "PLANAR_BRUSH_BUDGET" }
            total += points.size; require(total <= 16384) { "PLANAR_BRUSH_BUDGET" }
            number(stroke["radius"], .5, maxOf(width, height).toDouble()); points.forEach { point(array(it), width, height) }
        }
        return strokes.size to total
    }

    private fun point(values: List<Any?>, width: Int, height: Int) {
        require(values.size == 2) { "PLANAR_POINT" }; number(values[0], 0.0, width - 1.0); number(values[1], 0.0, height - 1.0)
    }
    private fun base64(value: Any?, limit: Int): ByteArray {
        val encoded = string(value)
        require(encoded.length <= (limit + 2L) / 3 * 4 && encoded.length % 4 == 0) { "PLANAR_MASK_BUDGET" }
        val result = try { Base64.getDecoder().decode(encoded) } catch (error: IllegalArgumentException) { throw IllegalArgumentException("PLANAR_MASK_BASE64", error) }
        require(result.size <= limit && Base64.getEncoder().encodeToString(result) == encoded) { "PLANAR_MASK_BASE64" }; return result
    }

    private fun spool(input: InputStream, file: File, checkCancelled: () -> Unit) {
        var total = 0L; val buffer = ByteArray(8192)
        file.outputStream().use { output ->
            while (true) {
                checkWork(checkCancelled)
                val count = input.read(buffer)
                if (count < 0) break
                require(count > 0) { "PLANAR_INVALID_INPUT_STREAM" }
                total += count; require(total <= MAX_PACKAGE) { "PLANAR_COMPRESSED_BUDGET" }; output.write(buffer, 0, count)
            }
        }
        require(total > 0) { "PLANAR_EMPTY_ARCHIVE" }
    }

    private data class ArchiveEntry(val name: String, val size: Long, val compressed: Long, val crc: Long, val offset: Long, val flags: Int, val method: Int)
    private fun readArchive(file: File, checkCancelled: () -> Unit): Map<String, ByteArray> {
        val entries = centralEntries(file)
        val result = linkedMapOf<String, ByteArray>(); var expanded = 0L
        ZipFile(file).use { zip ->
            require(zip.size() == entries.size) { "PLANAR_ZIP_ENTRY_MISMATCH" }
            for (entry in entries) {
                checkWork(checkCancelled)
                val found = zip.getEntry(entry.name) ?: throw IllegalArgumentException("PLANAR_ZIP_ENTRY_MISMATCH")
                require(found.size == entry.size && found.compressedSize == entry.compressed && found.crc == entry.crc) { "PLANAR_ZIP_ENTRY_MISMATCH" }
                val output = ByteArrayOutputStream(minOf(entry.size, 8192).toInt()); val crc = CRC32(); var size = 0L
                zip.getInputStream(found).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        checkWork(checkCancelled); val count = input.read(buffer); if (count < 0) break
                        require(count > 0) { "PLANAR_INVALID_ZIP_STREAM" }
                        size += count; expanded += count
                        require(size <= entry.size && expanded <= MAX_EXPANDED) { "PLANAR_EXPANDED_BUDGET" }
                        crc.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                }
                require(size == entry.size && crc.value == entry.crc) { "PLANAR_ZIP_CRC" }; result[entry.name] = output.toByteArray()
            }
        }
        require(result.keys.containsAll(setOf("manifest.json", "asset.json", "definition.json", "source.png"))) { "PLANAR_INCOMPLETE_PACKAGE" }
        return result
    }

    private fun centralEntries(file: File): List<ArchiveEntry> = RandomAccessFile(file, "r").use { input ->
        val length = input.length(); require(length in 22..MAX_PACKAGE.toLong()) { "PLANAR_ZIP_SIZE" }
        val tail = ByteArray(minOf(length, 65557).toInt()); input.seek(length - tail.size); input.readFully(tail)
        val end = (tail.size - 22 downTo 0).firstOrNull { i -> le32(tail, i) == 0x06054b50L && i + 22 + le16(tail, i + 20) == tail.size }
            ?: throw IllegalArgumentException("PLANAR_ZIP_END")
        require(le16(tail, end + 4) == 0 && le16(tail, end + 6) == 0 && le16(tail, end + 8) == le16(tail, end + 10)) { "PLANAR_ZIP_MULTIDISK" }
        val count = le16(tail, end + 10); require(count in 1..68) { "PLANAR_ENTRY_BUDGET" }
        val centralSize = le32(tail, end + 12); val centralOffset = le32(tail, end + 16)
        require(centralOffset + centralSize == length - tail.size + end) { "PLANAR_ZIP_CENTRAL_BOUNDS" }
        input.seek(centralOffset)
        val entries = ArrayList<ArchiveEntry>(count); val names = hashSetOf<String>(); val offsets = hashSetOf<Long>(); var expanded = 0L
        repeat(count) {
            val header = ByteArray(46); input.readFully(header); require(le32(header, 0) == 0x02014b50L) { "PLANAR_ZIP_CENTRAL" }
            val flags = le16(header, 8); val method = le16(header, 10)
            require(flags and 0xf7f1 == 0 && method in setOf(0, 8) && le16(header, 34) == 0) { "PLANAR_ZIP_ENCODING" }
            val size = le32(header, 24); val compressed = le32(header, 20); val offset = le32(header, 42)
            val nameSize = le16(header, 28); val extraSize = le16(header, 30); val commentSize = le16(header, 32)
            require(nameSize in 1..100 && input.filePointer + nameSize + extraSize + commentSize <= centralOffset + centralSize) { "PLANAR_ZIP_NAME_BUDGET" }
            val rawName = ByteArray(nameSize); input.readFully(rawName); val name = rawName.toString(Charsets.US_ASCII)
            val mode = (le32(header, 38) ushr 16).toInt() and 0xf000
            require(SAFE_FILE.matches(name) && names.add(name) && offsets.add(offset) && mode in setOf(0, 0x8000) && le32(header, 38) and 0x10L == 0L) { "PLANAR_UNSAFE_ARCHIVE_PATH" }
            require(size <= entryLimit(name) && compressed <= MAX_PACKAGE && offset < centralOffset) { "PLANAR_ZIP_ENTRY_BUDGET" }
            expanded += size; require(expanded <= MAX_EXPANDED) { "PLANAR_EXPANDED_BUDGET" }
            input.seek(input.filePointer + extraSize + commentSize)
            entries.add(ArchiveEntry(name, size, compressed, le32(header, 16), offset, flags, method))
        }
        require(input.filePointer == centralOffset + centralSize) { "PLANAR_ZIP_CENTRAL_SIZE" }
        val sorted = entries.sortedBy { it.offset }; require(sorted.first().offset == 0L) { "PLANAR_ZIP_PREAMBLE" }
        for ((index, entry) in sorted.withIndex()) validateLocal(input, entry, if (index + 1 < sorted.size) sorted[index + 1].offset else centralOffset)
        entries
    }

    private fun validateLocal(input: RandomAccessFile, entry: ArchiveEntry, nextOffset: Long) {
        input.seek(entry.offset); val header = ByteArray(30); input.readFully(header)
        require(le32(header, 0) == 0x04034b50L && le16(header, 6) == entry.flags && le16(header, 8) == entry.method) { "PLANAR_ZIP_LOCAL_HEADER" }
        val nameSize = le16(header, 26); val extraSize = le16(header, 28)
        require(nameSize == entry.name.length && input.filePointer + nameSize + extraSize + entry.compressed <= nextOffset) { "PLANAR_ZIP_LOCAL_BOUNDS" }
        val name = ByteArray(nameSize); input.readFully(name); require(name.toString(Charsets.US_ASCII) == entry.name) { "PLANAR_ZIP_LOCAL_NAME" }
        if (entry.flags and 8 == 0) {
            require(le32(header, 14) == entry.crc && le32(header, 18) == entry.compressed && le32(header, 22) == entry.size) { "PLANAR_ZIP_LOCAL_SIZES" }
            require(input.filePointer + extraSize + entry.compressed == nextOffset) { "PLANAR_ZIP_HIDDEN_ENTRY" }
        }
        else {
            val descriptorOffset = input.filePointer + extraSize + entry.compressed
            require(descriptorOffset + 12 <= nextOffset) { "PLANAR_ZIP_DESCRIPTOR" }; input.seek(descriptorOffset)
            val first = ByteArray(4); input.readFully(first)
            val descriptor = ByteArray(12)
            if (le32(first, 0) == 0x08074b50L) { require(descriptorOffset + 16 <= nextOffset); input.readFully(descriptor) }
            else { first.copyInto(descriptor); input.readFully(descriptor, 4, 8) }
            require(le32(descriptor, 0) == entry.crc && le32(descriptor, 4) == entry.compressed && le32(descriptor, 8) == entry.size) { "PLANAR_ZIP_DESCRIPTOR" }
            require(input.filePointer == nextOffset) { "PLANAR_ZIP_HIDDEN_ENTRY" }
        }
    }

    /** Checks PNG structure and bounded scanline inflation without allocating a bitmap. */
    private fun png(raw: ByteArray, source: Boolean, checkCancelled: () -> Unit = {}, allowMaskColor: Boolean = false): Pair<Int, Int> {
        require(raw.size in 45..MAX_SOURCE && raw.take(8) == listOf<Byte>(-119, 80, 78, 71, 13, 10, 26, 10)) { "PLANAR_INVALID_PNG" }
        var offset = 8; var width = 0; var height = 0; var chunks = 0; var ended = false; var sawData = false; var afterData = false
        var rows = intArrayOf(); var paletteRequired = false; var sawPalette = false; var colorType = -1; var bitDepth = 0; var paletteEntries = 0
        val data = mutableListOf<Pair<Int, Int>>()
        while (offset < raw.size) {
            checkWork(checkCancelled)
            require(++chunks <= 65536 && offset.toLong() + 12 <= raw.size) { "PLANAR_PNG_CHUNKS" }
            val size = be32(raw, offset); require(size <= MAX_SOURCE && offset + 12L + size <= raw.size) { "PLANAR_PNG_CHUNK_BUDGET" }
            val length = size.toInt(); val type = raw.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
            require(type.length == 4 && type.all { it in 'A'..'Z' || it in 'a'..'z' }) { "PLANAR_PNG_CHUNK_TYPE" }
            val crc = CRC32(); crc.update(raw, offset + 4, length + 4)
            require(crc.value == be32(raw, offset + 8 + length)) { "PLANAR_PNG_CRC" }
            when (type) {
                "IHDR" -> {
                    require(chunks == 1 && length == 13) { "PLANAR_PNG_HEADER" }
                    width = be32(raw, offset + 8).toInt(); height = be32(raw, offset + 12).toInt()
                    require(width in (if (source) 2 else 1)..contract.maxSide && height in (if (source) 2 else 1)..contract.maxSide) { "PLANAR_PNG_DIMENSIONS" }
                    val depth = raw[offset + 16].toInt() and 255; val color = raw[offset + 17].toInt() and 255
                    colorType = color; bitDepth = depth
                    val channels = when (color) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> 0 }
                    val interlace = raw[offset + 20].toInt() and 255
                    val legalDepth = when (color) { 0 -> depth in setOf(1, 2, 4, 8, 16); 3 -> depth in setOf(1, 2, 4, 8); 2, 4, 6 -> depth in setOf(8, 16); else -> false }
                    require(legalDepth && (allowMaskColor || depth == 8 && color in setOf(2, 6) && interlace == 0) &&
                        channels > 0 && raw[offset + 18] == 0.toByte() && raw[offset + 19] == 0.toByte() && interlace in 0..1) { "PLANAR_PNG_REQUIRES_RGB_OR_RGBA8" }
                    paletteRequired = color == 3
                    rows = pngRows(width, height, channels * depth, interlace)
                }
                "PLTE" -> {
                    require(!sawData && !sawPalette && colorType !in setOf(0, 4) && length in 3..768 && length % 3 == 0 &&
                        (!paletteRequired || length / 3 <= (1 shl bitDepth))) { "PLANAR_PNG_PALETTE" }
                    sawPalette = true; paletteEntries = length / 3
                }
                "IDAT" -> { require(width > 0 && !afterData && !ended) { "PLANAR_PNG_IDAT" }; sawData = true; data.add(offset + 8 to length) }
                "IEND" -> { require(sawData && length == 0 && offset + 12 == raw.size) { "PLANAR_PNG_END" }; ended = true }
                "acTL", "fcTL", "fdAT" -> throw IllegalArgumentException("PLANAR_ANIMATED_PNG")
                "iCCP", "zTXt", "iTXt", "eXIf" -> throw IllegalArgumentException("PLANAR_PNG_COMPRESSED_OR_ORIENTED_METADATA")
                "tRNS" -> { require(!sawData && when (colorType) { 0 -> length == 2; 2 -> length == 6; 3 -> sawPalette && length in 1..paletteEntries; else -> false }) { "PLANAR_PNG_TRANSPARENCY" } }
                else -> { require(type[0].isLowerCase() || type == "PLTE") { "PLANAR_PNG_CRITICAL_CHUNK" }; if (sawData) afterData = true }
            }
            offset += length + 12
        }
        require(ended && width > 0 && (!paletteRequired || sawPalette)) { "PLANAR_PNG_INCOMPLETE" }
        validateInflatedPng(raw, data, rows, checkCancelled)
        return width to height
    }

    private fun pngRows(width: Int, height: Int, bitsPerPixel: Int, interlace: Int): IntArray {
        if (interlace == 0) return IntArray(height) { (width * bitsPerPixel + 7) / 8 + 1 }
        val result = ArrayList<Int>()
        for (pass in listOf(intArrayOf(0, 0, 8, 8), intArrayOf(4, 0, 8, 8), intArrayOf(0, 4, 4, 8),
            intArrayOf(2, 0, 4, 4), intArrayOf(0, 2, 2, 4), intArrayOf(1, 0, 2, 2), intArrayOf(0, 1, 1, 2))) {
            val columns = maxOf(0, (width - pass[0] + pass[2] - 1) / pass[2]); val lines = maxOf(0, (height - pass[1] + pass[3] - 1) / pass[3])
            if (columns > 0) repeat(lines) { result.add((columns * bitsPerPixel + 7) / 8 + 1) }
        }
        return result.toIntArray()
    }

    private fun validateInflatedPng(raw: ByteArray, chunks: List<Pair<Int, Int>>, rows: IntArray, checkCancelled: () -> Unit) {
        val inflater = Inflater(); var total = 0L; val expected = rows.sumOf { it.toLong() }; val buffer = ByteArray(8192)
        var row = 0; var nextFilter = 0L
        try {
            for ((offset, length) in chunks) {
                checkWork(checkCancelled)
                if (length == 0) continue
                require(!inflater.finished()) { "PLANAR_PNG_TRAILING_ZLIB" }; inflater.setInput(raw, offset, length)
                while (!inflater.needsInput() && !inflater.finished()) {
                    checkWork(checkCancelled); val count = inflater.inflate(buffer)
                    require(count > 0 || inflater.needsInput() || inflater.finished()) { "PLANAR_PNG_INVALID_ZLIB" }
                    require(total + count <= expected) { "PLANAR_PNG_INFLATE_BUDGET" }
                    for (index in 0 until count) if (total + index == nextFilter) {
                        require(row < rows.size && buffer[index].toInt() in 0..4) { "PLANAR_PNG_FILTER" }; nextFilter += rows[row++]
                    }
                    total += count
                }
                if (inflater.finished()) require(inflater.remaining == 0) { "PLANAR_PNG_TRAILING_ZLIB" }
            }
            require(inflater.finished() && total == expected) { "PLANAR_PNG_SCANLINES" }
        } catch (error: DataFormatException) { throw IllegalArgumentException("PLANAR_PNG_INVALID_ZLIB", error) }
        finally { inflater.end() }
    }

    private fun validateFileBudgets(files: Map<String, ByteArray>) {
        require(files.size <= 67 && files.values.sumOf { it.size.toLong() } <= MAX_EXPANDED) { "PLANAR_EXPANDED_BUDGET" }
        files.forEach { (path, bytes) -> require(bytes.size <= entryLimit(path)) { "PLANAR_ENTRY_BUDGET" } }
    }
    private fun entryLimit(name: String): Long = when (name) { "manifest.json" -> 65536L; "asset.json", "definition.json" -> MAX_JSON.toLong(); else -> MAX_SOURCE.toLong() }
    private fun parseBytes(raw: ByteArray): Any? = PlanarJson.parse(strictUtf8(raw))
    private fun strictUtf8(raw: ByteArray): String {
        require(raw.size <= MAX_JSON) { "PLANAR_JSON_BUDGET" }
        return try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString() }
        catch (error: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("PLANAR_JSON_UTF8", error) }
    }
    @Suppress("UNCHECKED_CAST")
    private fun objectValue(value: Any?): Map<String, Any?> = (value as? Map<*, *>)?.also { require(it.keys.all { key -> key is String }) { "PLANAR_JSON_OBJECT" } } as? Map<String, Any?> ?: throw IllegalArgumentException("PLANAR_JSON_OBJECT")
    private fun array(value: Any?): List<Any?> = value as? List<*> ?: throw IllegalArgumentException("PLANAR_JSON_ARRAY")
    private fun string(value: Any?): String = value as? String ?: throw IllegalArgumentException("PLANAR_JSON_STRING")
    private fun number(value: Any?, low: Double, high: Double): Double = (value as? Number)?.toDouble()?.also { require(it.isFinite() && it in low..high) { "PLANAR_NUMBER_RANGE" } } ?: throw IllegalArgumentException("PLANAR_JSON_NUMBER")
    private fun integer(value: Any?, low: Int, high: Int): Int = number(value, low.toDouble(), high.toDouble()).also { require(it % 1.0 == 0.0) { "PLANAR_JSON_INTEGER" } }.toInt()
    private fun floats(value: Any?, limit: Int): FloatArray = array(value).also { require(it.size <= limit) { "PLANAR_GEOMETRY_BUDGET" } }.map { number(it, -Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat() }.toFloatArray()
    private fun validName(value: String): String = value.trim().also { require(it.codePointCount(0, it.length) in 1..80 && value.none { char -> char.code < 32 }) { "PLANAR_NAME" } }
    private fun checkWork(callback: () -> Unit) { callback(); if (Thread.currentThread().isInterrupted) throw CancellationException("PLANAR_CANCELLED") }
    private fun le16(raw: ByteArray, offset: Int): Int = (raw[offset].toInt() and 255) or ((raw[offset + 1].toInt() and 255) shl 8)
    private fun le32(raw: ByteArray, offset: Int): Long = (0..3).fold(0L) { value, index -> value or ((raw[offset + index].toLong() and 255) shl (index * 8)) }
    private fun be32(raw: ByteArray, offset: Int): Long = (0..3).fold(0L) { value, index -> (value shl 8) or (raw[offset + index].toLong() and 255) }
    companion object {
        const val DESKTOP = "rig2d-planar-package-1"
        const val NATIVE = "rig2d-planar-native-package-1"
        private const val MAX_PACKAGE = 64 * 1024 * 1024
        private const val MAX_EXPANDED = 128L * 1024 * 1024
        private const val MAX_SOURCE = 20 * 1024 * 1024
        private const val MAX_JSON = 32 * 1024 * 1024
        private const val MAX_PIXELS = 24_000_000L
        private const val MAX_TEXTURE_BYTES = 96_000_000L
        private val CHANNELS = setOf("headX", "headY", "tilt", "blinkLeft", "blinkRight", "mouth", "breath", "sway")
        private val ROLES = setOf("base", "face", "eye_left", "eye_right", "mouth", "hair", "body", "accessory")
        private val SHA = Regex("[a-f0-9]{64}")
        private val ID = Regex("[A-Za-z0-9_-]{1,80}")
        private val SAFE_FILE = Regex("source\\.png|asset\\.json|definition\\.json|manifest\\.json|textures/[a-f0-9]{64}\\.png")
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
