package rig2d.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * GOLDEN 值生成器（**只在夹具变更时才手动运行**）。
 *
 * 纪律（重要，别用错）：
 *   这个测试**不参与常规验收**。它的存在只是为了让 golden 值可复现、可审计：
 *   它打印出冻结副本（OcIdentityV1 / OcRigVerticesV1 / OcFlatRendererV1）产生的
 *   canonical 明文与摘要，人工核对后写进 src/test/resources/golden/ 下的 JSON。
 *
 *   冻结副本**不读取任何 v2 代码**，所以它给出的值天然是「改动前」的值——
 *   这正是 v1 逐字节回归门禁需要的参照物。
 *   反过来，绝不允许用「跑一遍新代码、把输出写进 golden」的方式生成参照物：
 *   那样门禁会退化成自证。
 *
 * 运行方式：
 *   gradlew test --tests "rig2d.domain.GoldenGeneratorTest"
 *   （输出在标准输出里，testLogging 已打开 showStandardStreams）
 *
 * 生成后把 JSON 写进 src/test/resources/golden/v1_identity_photo_subject.json，字段为
 * canonical / sha256 / renderDigest / vertexCount / vertexDigest / planBoxCount / clipDepth。
 */
class GoldenGeneratorTest {

    @Test
    fun `打印 v1 golden 值供人工写入 resources`() {
        val identity = Fixtures.v1Identity()
        val scene = OcSceneV1(motion = Fixtures.v1Motion())
        val canonical = OcPackageSha.v1Canonical(identity)
        val digest = OcFlatRendererV1.drawPlanDigest(identity, scene, 768, 1024)
        val json = buildString {
            appendLine("{")
            appendLine("  \"name\": \"v1_identity_photo_subject\",")
            appendLine("  \"note\": \"GOLDEN: 由冻结的 v1 副本产生，用于 v1 逐字节回归门禁。不是从 v2 代码采样。\",")
            appendLine("  \"canonical\": \"${canonical.replace("\\", "\\\\").replace("\"", "\\\"")}\",")
            appendLine("  \"sha256\": \"${OcPackageSha.v1(identity)}\",")
            appendLine("  \"renderDigest\": \"${digest.hex}\",")
            appendLine("  \"vertexCount\": ${digest.vertexCount},")
            appendLine("  \"vertexDigest\": \"${digest.vertexDigest}\",")
            appendLine("  \"planBoxCount\": ${digest.boxes.size},")
            appendLine("  \"clipDepth\": ${digest.clipDepth}")
            appendLine("}")
        }
        println("=== GOLDEN v1_identity_photo_subject ===")
        println(json)
        println("=== END GOLDEN ===")
        // 自检：重复计算结果必须一致（确定性）。
        assertEquals(canonical, OcPackageSha.v1Canonical(Fixtures.v1Identity()))
        assertEquals(digest.hex, OcFlatRendererV1.drawPlanDigest(identity, scene, 768, 1024).hex)
    }
}
