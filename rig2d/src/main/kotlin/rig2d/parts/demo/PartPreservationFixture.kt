package rig2d.parts.demo

import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.awt.image.BufferedImage
import java.security.MessageDigest
import javax.imageio.ImageIO
import rig2d.parts.*

private class FixtureReader(input: Path) {
    private val root=input.toRealPath()
    val hashes=sortedMapOf<String,String>()
    fun read(name: String, expected: Int): ByteArray {
        val path=root.resolve(name)
        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && path.toRealPath().startsWith(root)) {
            "Fixture input is not a regular file within its directory"
        }
        require(Files.size(path)==expected.toLong()) { "Fixture byte count mismatch" }
        val bytes=Files.newInputStream(path).use { it.readNBytes(expected+1) }
        require(bytes.size==expected) { "Fixture changed while reading" }
        hashes[name]=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
        return bytes
    }
    fun mask(name: String): PixelMask {
        val bytes=read(name,256*256)
        require(bytes.all { it==0.toByte() || it==1.toByte() }) { "Fixture mask must be 0/1" }
        return PixelMask.fromBooleans(256,256,BooleanArray(bytes.size) { bytes[it]==1.toByte() })
    }
    fun source(): PartRaster {
        val bytes=read("source.argb",256*256*4)
        require(hashes["source.argb"]=="c65c870b2570a83c74157b0a2edb9184c66db35a698539cadaccbf626ad8f63a") {
            "This replay only accepts its frozen synthetic source, not user media"
        }
        val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return PartRaster(256,256,IntArray(256*256) { buffer.int })
    }
    fun candidates(): List<PartCandidate> = (1..11).map { number ->
        val id="wing_%02d".format(number)
        PartCandidate(id,"accessory",mask("instances/$id.mask"),(.94-.01*number).toFloat())
    } + listOf("weapon_01" to "accessory","back_hair" to "back_hair","face" to "face",
               "front_hair" to "front_hair","body" to "body").map { (id,kind) ->
        PartCandidate(id,kind,mask("instances/$id.mask"),.91f)
    }
}

/** Fixed desktop replay, not a model adapter, user-media importer, renderer or installer. */
fun replayPartFixture(input: Path, output: Path): String {
    val reader=FixtureReader(input)
    val source=reader.source()
    val subject=reader.mask("subject.mask")
    val draft=PartLayerAssembler.assemble(source,subject,reader.mask("protected.mask"),reader.candidates())
    val truth=reader.mask("foreground.mask")
    val restored=draft.recomposeArgb()
    require(draft.foreground==truth && restored.contentEquals(source.toArgb())) { "Fixture foreground was lost" }
    for (number in 1..12) {
        val wing=reader.mask("ground_truth/wing_%02d.mask".format(number))
        require(wing.count>0 && wing.subtract(draft.foreground).count==0) { "Fixture wing was lost" }
    }
    val report="""{"claim":"SYNTHETIC_NOT_MODEL_NOT_REAL_OC","source_pixel_sha256":"${source.pixelSha256}","foreground":${truth.count},"subject_omitted":${truth.subtract(subject).count},"missed_foreground":0,"ambiguous":${draft.ambiguous.count},"retained":${draft.retained.count},"planes":${draft.planes.size},"wing_masks_covered":12,"identity_verified":false,"live2d_ready":false}"""
    Files.createDirectories(output.toAbsolutePath().parent)
    Files.createDirectory(output)
    savePng(output.resolve("recomposed.png"),restored)
    savePng(output.resolve("retained.png"),source.selectedArgb(draft.retained))
    for (plane in draft.planes) savePng(output.resolve("${plane.id}.png"),source.selectedArgb(plane.mask))
    Files.writeString(output.resolve("result.json"),report+"\n",StandardOpenOption.CREATE_NEW)
    val provenance=reader.hashes.entries.joinToString("\n") { "${it.key}=${it.value}" }+"\n"
    Files.writeString(output.resolve("input-shas.properties"),provenance,StandardOpenOption.CREATE_NEW)
    return report
}

private fun savePng(path: Path, argb: IntArray) {
    val image=BufferedImage(256,256,BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0,0,256,256,argb,0,256)
    Files.newOutputStream(path,StandardOpenOption.CREATE_NEW).use { stream ->
        check(ImageIO.write(image,"png",stream)) { "PNG writer unavailable" }
    }
}

fun main(args: Array<String>) {
    require(args.size==2) { "Expected frozen synthetic fixture directory and new output directory" }
    println(replayPartFixture(Path.of(args[0]),Path.of(args[1])))
}
