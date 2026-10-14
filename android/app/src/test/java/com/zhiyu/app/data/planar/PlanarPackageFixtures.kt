package com.zhiyu.app.data.planar

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object PlanarPackageFixtures {
    const val contract = """{"format":"rig2d-planar-1","mode":"front_mesh","parameterInput":"reject_out_of_range","maxSide":1536,"maxLayers":64,"maxVertices":1089,"maxTriangles":2048,"maxSourceBytes":20971520,"maxTexturePixels":24000000,"maxTextureBytes":96000000,"roles":["base","face","eye_left","eye_right","mouth","hair","body","accessory"],"parameters":{"headX":[-1,1,0],"headY":[-1,1,0],"tilt":[-1,1,0],"blinkLeft":[0,1,0],"blinkRight":[0,1,0],"mouth":[0,1,0],"breath":[0,1,0],"sway":[-1,1,0]},"trueSideViews":false,"cubismMoc3":false}"""
    const val definition = """{"anchors":{"head":[1,0],"neck":[1,1],"eyeLeft":[0,0,1,1],"eyeRight":[1,0,2,1],"mouth":[0,1,2,2]},"parts":[]}"""
    val png: ByteArray get() = png(2, 2)
    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun png(width: Int, height: Int, color: Int = 6, depth: Int = 8,
        scanlines: ByteArray = byteArrayOf(0, -1, 0, 0, -1, 0, -1, 0, -1, 0, 0, 0, -1, -1, -1, -1, -1, -1), palette: ByteArray? = null): ByteArray {
        val result = ByteArrayOutputStream()
        result.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        fun chunk(kind: String, bytes: ByteArray) {
            val stream = DataOutputStream(result)
            val type = kind.toByteArray(StandardCharsets.US_ASCII)
            stream.writeInt(bytes.size); stream.write(type); stream.write(bytes)
            val crc = CRC32(); crc.update(type); crc.update(bytes); stream.writeInt(crc.value.toInt())
        }
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use { it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(depth.toByte(), color.toByte(), 0, 0, 0)) }
        chunk("IHDR", header.toByteArray())
        palette?.let { chunk("PLTE", it) }
        val image = ByteArrayOutputStream()
        DeflaterOutputStream(image).use { it.write(scanlines) }
        chunk("IDAT", image.toByteArray()); chunk("IEND", byteArrayOf())
        return result.toByteArray()
    }
    fun files(assetChange: (String) -> String = { it }, definitionText: String = definition): Map<String, ByteArray> {
        val source = png
        val textureSha = sha(source)
        val parameters = contract.substringAfter("\"parameters\":").substringBefore(",\"trueSideViews\"")
        val layer = """{"id":"base","name":"保留原图","role":"base","z":-1001,"texture":"textures/$textureSha.png","textureSha":"$textureSha","bounds":[0,0,2,2],"phase":0,"strength":1,"vertices":[0,0,2,0,0,2],"uv":[0,0,1,0,0,1],"indices":[0,1,2],"deltas":{"headX":[0,0,0,0,0,0]}}"""
        val asset = """{"format":"rig2d-planar-1","mode":"front_mesh","width":2,"height":2,"name":"测试角色","sourceSha":"${sha(source)}","pixelSha":"${"0".repeat(64)}","layers":[$layer],"parameters":$parameters,"capabilities":{"trueSideViews":false,"cubismMoc3":false,"identityVerified":false,"reviewRequired":true,"neutralSourceExact":true,"sourceAlphaPreserved":true,"hiddenPatchGenerated":false,"generator":"planar-v1"}}"""
        return linkedMapOf("source.png" to source, "textures/$textureSha.png" to source, "asset.json" to assetChange(asset).toByteArray(), "definition.json" to definitionText.toByteArray())
    }
    fun archive(files: Map<String, ByteArray> = files(), format: String = "rig2d-planar-package-1", manifestOverride: String? = null): ByteArray {
        val hashes = files.entries.joinToString(",") { "\"${it.key}\":\"${sha(it.value)}\"" }
        val manifest = manifestOverride ?: "{\"format\":\"$format\",\"files\":{$hashes}}"
        val result = ByteArrayOutputStream()
        ZipOutputStream(result).use { zip ->
            (files + ("manifest.json" to manifest.toByteArray())).forEach { (path, raw) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(raw); zip.closeEntry()
            }
        }
        return result.toByteArray()
    }
}
