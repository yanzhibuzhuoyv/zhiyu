package rig2d.parts

import rig2d.parts.demo.replayPartFixture
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test

class PartFixtureReplayTest {
    @Test fun hostReplayExportsTheCoreRecompositionAndRetainedLayerWithoutChangingSource() {
        val parent=Files.createTempDirectory("rig2d-parts-replay-")
        try {
            val out=parent.resolve("replay")
            val report=replayPartFixture(Path.of("artifacts/parts-fixture-2026-10-13-r1"),out)
            assertTrue(report.contains("SYNTHETIC_NOT_MODEL_NOT_REAL_OC"))
            assertTrue(report.contains("\"missed_foreground\":0"))
            val original=ImageIO.read(Path.of("artifacts/parts-fixture-2026-10-13-r1/preview.png").toFile())
            val restored=ImageIO.read(out.resolve("recomposed.png").toFile())
            assertArrayEquals(original.getRGB(0,0,256,256,null,0,256),restored.getRGB(0,0,256,256,null,0,256))
            assertTrue(Files.isRegularFile(out.resolve("retained.png")))
            assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
                replayPartFixture(Path.of("artifacts/parts-fixture-2026-10-13-r1"),out)
            }
        } finally {
            require(parent.toRealPath().startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()))
            Files.walk(parent).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
