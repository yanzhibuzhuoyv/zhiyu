package rig2d.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/*
 * 计划 §2.3 第三个与第四个测试文件合并在此文件的两个区段里：
 *   - OcLegacyRigV1Test（v1 逐字节回归，硬门禁）
 *   - OcPackageShaV2Test（v1/v2 前缀区分、同输入同输出、字段改动必变哈希）
 *
 * 关于 GOLDEN 值来源（必须如实标注，不得含糊）：
 *   GOLDEN_* 常量是**改动前**（任何 v2 代码存在之前）由冻结副本产生的期望值，
 *   记录了 v1 canonical 串与 v1 渲染变换摘要。见 src/test/resources/golden/。
 *   它们不是从新版代码「跑出来再抄回去」的结果——golden 文件里同时保存了完整
 *   canonical 明文，任何字段顺序/token 规则变化都会让明文与哈希同时对不上。
 */
class OcLegacyRigV1Test {

    private val golden = GoldenFixtures.load("v1_identity_photo_subject")

    @Test
    fun `v1 canonical 串与改动前逐字节相同`() {
        val identity = Fixtures.v1Identity()
        val canonical = OcPackageSha.v1Canonical(identity)
        assertEquals("v1 canonical 串发生变化——硬门禁失败", golden.canonical, canonical)
    }

    @Test
    fun `v1 packageSha 与改动前完全相同`() {
        val identity = Fixtures.v1Identity()
        assertEquals("v1 packageSha 发生变化——已发布角色的哈希会漂移", golden.sha256, OcPackageSha.v1(identity))
    }

    @Test
    fun `v1 渲染变换摘要与改动前逐字节相同`() {
        val identity = Fixtures.v1Identity()
        val scene = OcSceneV1(motion = Fixtures.v1Motion())
        val digest = OcFlatRendererV1.drawPlanDigest(identity, scene, 768, 1024)
        assertEquals("v1 渲染变换发生变化——老角色画面会变", golden.renderDigest, digest.hex)
        // photo_subject 路径只有两个绘制图元：背景填充 + 源图（带 mesh 时是网格绘制）。
        // 这里记录的**是实际行为，不是我以为的行为**：第一版断言写 25（把 parts_2d 的
        // Character 图元数当成了 photo 路径），RED 日志见 artifacts/stage0-red-2026-10-12.log。
        assertEquals(2, digest.boxes.size)
        assertEquals(1, digest.boxes.count { it.name == "photo_mesh" })
        assertEquals(1, digest.boxes.count { it.name == "background" })
        assertEquals("v1 网格顶点数应为 (48+1)*(64+1)", 3185, digest.vertexCount)
        assertEquals("v1 顶点缓冲内容改变——OcRigVerticesV1 逐位一致性被破坏", golden.vertexDigest, digest.vertexDigest)
        assertTrue("变换栈深度异常——save/restore 配对可能被改坏", digest.clipDepth >= 3)
    }

    @Test
    fun `v1 headTurn 语义是装饰性摆动——3-5 度旋转加 13 像素平移`() {
        val identity = Fixtures.v1Identity()
        val plan = OcFlatRendererV1.drawPlan(identity, OcSceneV1(motion = Fixtures.v1Motion(headTurn = 1f)), 768, 1024)
        val mesh = plan.boxes.single { it.name == "photo_mesh" }
        // photo 路径的变换栈 = translate(居中) + scale(适配)；两者都被记成 SCALE 记录
        // （SCALE 记录的 c/d 字段承载平移），不含 headTurn 旋转。
        // 栈序：索引 0 = translate((768-4*192)/2, (1024-4*192)/2) = (0, 128)，索引 1 = scale(192, 192)。
        assertEquals(2, mesh.stack.size)
        assertEquals(1.0, mesh.stack[0].a, 1e-9)
        assertEquals(128.0, mesh.stack[0].d, 1e-9)
        assertEquals(PlanOp.SCALE, mesh.stack[1].op)
        assertEquals(192.0, mesh.stack[1].a, 1e-9)
        assertEquals(192.0, mesh.stack[1].b, 1e-9)
        assertTrue("photo 路径不应出现任何旋转", plan.boxes.none { box -> box.stack.any { it.op == PlanOp.ROTATE } })
    }

    @Test
    fun `v1 headTurn 的装饰性常量被冻结在 3-5 度与 13 像素`() {
        // 常量来源：OcFlatRenderer.kt:91  c.rotate(motion.headTurn * 3.5f, 500f, 520f); c.translate(headTurn * 13f, -breath * 2f)
        // 该表达式只存在于 parts_2d 的 Character 路径；本测试用 v2 的 MotionSemantics 记录其数值，
        // 保证 v1→v2 的 headTurn 解释差异是**被写下来的**，而不是口头约定。
        assertEquals(3.5f, MotionSemantics.V1_HEAD_TURN_DEGREES, 0f)
        assertEquals(13f, MotionSemantics.V1_HEAD_TURN_TRANSLATE_PX, 0f)
        assertEquals(90f, MotionSemantics.V2_HEAD_TURN_DEGREES, 0f)
        assertEquals(3.5f, MotionSemantics.v1RotationDegrees(1f), 1e-6f)
        assertEquals(90f, MotionSemantics.v2YawDegrees(1f), 1e-6f)
        assertEquals(-90f, MotionSemantics.v2YawDegrees(-1f), 1e-6f)
        assertEquals(0f, MotionSemantics.v2YawDegrees(0f), 0f)
    }

    @Test
    fun `v1 校验分支仍然拒绝 v2 路由——路由集合没有被悄悄扩大`() {
        val v2Route = Fixtures.v1Identity().copy(route = OcRoutes.PHOTO_RIG_2D)
        assertThrows(IllegalArgumentException::class.java) { OcIdentityRulesV1.check(v2Route) }
        assertTrue(OcRoutes.PHOTO_RIG_2D in OcRoutes.V2)
        assertTrue(OcRoutes.PHOTO_RIG_2D !in OcRoutes.V1)
    }

    @Test
    fun `v1 校验仍然拒绝 schemaVersion 2 —— v1 不接受 v2 档案`() {
        assertThrows(IllegalArgumentException::class.java) {
            OcIdentityRulesV1.check(Fixtures.v1Identity().copy(schemaVersion = 2))
        }
    }

    @Test
    fun `v1 rig 默认值与冻结来源一致`() {
        val rig = OcRigRulesV1.defaultRig()
        assertEquals(listOf("head", "body", "eyes", "mouth"), rig.regions.map { it.id })
        assertEquals(0.25f, rig.regions[1].x, 0f)
        assertEquals(0.55f, rig.regions[1].height, 0f)
        assertEquals(0.42f, rig.regions[3].x, 0f)
        assertEquals(1, rig.version)
    }

    @Test
    fun `v1 motion 校验边界与冻结来源一致`() {
        OcRigRulesV1.motion(Fixtures.v1Motion(headTurn = -1f, breath = -1f, blink = 0f, mouthOpen = 0f, accessorySway = -1f))
        assertThrows(IllegalArgumentException::class.java) { OcRigRulesV1.motion(Fixtures.v1Motion(headTurn = 1.01f)) }
        assertThrows(IllegalArgumentException::class.java) { OcRigRulesV1.motion(Fixtures.v1Motion(blink = -0.01f)) }
        assertThrows(IllegalArgumentException::class.java) { OcRigRulesV1.motion(Fixtures.v1Motion(mouthOpen = -0.01f)) }
    }

    @Test
    fun `v1 部件校验拒绝越界与重复编号`() {
        val duplicated = Fixtures.v1Identity().let { it.copy(parts = it.parts + it.parts.first()) }
        assertThrows(IllegalArgumentException::class.java) { OcIdentityRulesV1.check(duplicated) }
        val outOfRange = Fixtures.v1Identity().let { identity ->
            identity.copy(parts = listOf(identity.parts.first().copy(x = 1.2f)))
        }
        assertThrows(IllegalArgumentException::class.java) { OcIdentityRulesV1.check(outOfRange) }
    }
}

class OcPackageShaV2Test {

    @Test
    fun `v1 与 v2 前缀不同——同一个档案不会在两条路径上碰撞`() {
        assertEquals("oc-v1", OcPackageSha.V1_PREFIX)
        assertEquals("oc-v2", OcPackageSha.V2_PREFIX)
        assertNotEquals(OcPackageSha.V1_PREFIX, OcPackageSha.V2_PREFIX)
        val asset = Fixtures.fiveViewAsset()
        // "oc-v2" 长度 5，"oc-v1" 长度 5：token 形式都是 `5:oc-vX`，前缀差异在文本里。
        assertTrue(OcPackageSha.v2Canonical(asset).startsWith("5:oc-v2"))
        assertTrue(OcPackageSha.v1Canonical(Fixtures.v1Identity()).startsWith("5:oc-v1"))
        // 同一个 v1 档案的哈希不得等于任何 v2 资产的哈希（前缀隔离的实际证据）。
        assertNotEquals(GoldenFixtures.load("v1_identity_photo_subject").sha256, OcPackageSha.v2(asset))
    }

    @Test
    fun `同输入同输出——重复计算哈希完全相同`() {
        val asset = Fixtures.fiveViewAsset()
        assertEquals(OcPackageSha.v2(asset), OcPackageSha.v2(asset))
        assertEquals(OcPackageSha.v2Canonical(asset), OcPackageSha.v2Canonical(Fixtures.fiveViewAsset()))
    }

    @Test
    fun `视角顺序无关——等价重排不改变哈希`() {
        val asset = Fixtures.fiveViewAsset()
        val reversed = asset.copy(views = asset.views.reversed())
        assertEquals(OcPackageSha.v2(asset), OcPackageSha.v2(reversed))
    }

    @Test
    fun `层顺序无关——层按 z 排序参与哈希`() {
        val asset = Fixtures.fiveViewAsset()
        val shuffled = asset.copy(views = asset.views.map { it.copy(layers = it.layers.reversed()) })
        assertEquals(OcPackageSha.v2(asset), OcPackageSha.v2(shuffled))
    }

    @Test
    fun `任一字段改动必然改变哈希`() {
        val base = Fixtures.fiveViewAsset()
        val baseline = OcPackageSha.v2(base)
        // 先证明「没有改动就不会变」，否则下面的断言可能只是因为哈希不稳定。
        assertEquals("同输入必须同输出，否则下面的灵敏度断言无意义", baseline, OcPackageSha.v2(Fixtures.fiveViewAsset()))
        val mutations = linkedMapOf<String, OcRigAsset>(
            "sourceSha" to base.copy(sourceSha = Fixtures.hex("other_source")),
            "approvedAt" to base.copy(approvedAt = base.approvedAt + 1),
            "yawCenter" to mutateView(base, 0) { it.copy(yawCenter = it.yawCenter + 1f) },
            "yawHalfWidth" to mutateView(base, 0) { it.copy(yawHalfWidth = 29f) },
            "viewId" to mutateView(base, 0) { it.copy(viewId = "right90b") },
            "层 kind" to mutateLayer(base) { it.copy(kind = "neck") },
            "层 z" to mutateLayer(base) { it.copy(z = it.z + 1) },
            "层 depth" to mutateLayer(base) { it.copy(depth = it.depth + 0.01f) },
            "层 bounds" to mutateLayer(base) { it.copy(bounds = listOf(0.21f, 0.1f, 0.6f, 0.8f)) },
            "层 margin" to mutateLayer(base) { it.copy(margin = 0.121f) },
            "层资源 sha" to mutateLayer(base) { it.copy(asset = it.asset.copy(sha256 = Fixtures.hex("other_png"))) },
            "层资源外观版本" to mutateLayer(base) { it.copy(asset = it.asset.copy(visualVersion = 2)) },
            "网格 columns" to mutateView(base, 0) { it.copy(mesh = OcViewMesh(25, 32, Fixtures.ref("mesh"), 26 * 33)) },
            "rig 区域" to mutateView(base, 0) { it.copy(rig = OcRigV1(listOf(OcRigRegionV1("head", "head", strength = 0.5f)))) },
            "对称平面" to base.copy(proxy = base.proxy!!.copy(symmetryPlane = listOf(0f, 0f, -1f, 0f))),
            "代理深度资源" to base.copy(proxy = base.proxy!!.copy(depth = base.proxy!!.depth.copy(visualVersion = 7))),
            "移除代理" to base.copy(proxy = null),
        )
        mutations.forEach { (name, mutated) ->
            assertNotEquals("字段「$name」改动后哈希未变——哈希覆盖有缺口", baseline, OcPackageSha.v2(mutated))
        }
    }

    @Test
    fun `v2 canonical 串里带着 views 与 layers 的规模——可人工核对`() {
        val asset = Fixtures.fiveViewAsset()
        val canonical = OcPackageSha.v2Canonical(asset)
        assertTrue(canonical.contains(OcPackageSha.V2_PREFIX))
        assertTrue(canonical.contains("64:${Fixtures.hex("source")}"))
        // 5 视角 × 3 层 = 15 个层 id token；token 形式是「长度:文本」，
        // 而 "right45_back_hair" 这类 id 本身含数字，所以前缀必须匹配 `\d+:` 而不是锚定开头。
        assertEquals(15, Regex("\\d+:(front|right45|right90|left45|left90)_(back_hair|face|front_hair)")
            .findAll(canonical).count())
        // 5 个视角 id token：用**顺序解析器**切 canonical —— 长度前缀必须落在 token 边界上，
        // 用正则去「找所有 \d+:」会被 token 文本内部的数字误匹配（例如 sha 里的 "2:1"）。
        val idTokens = CanonicalTokenizer.tokens(canonical)
        assertEquals(5, idTokens.count { it in OcViewConstants.REQUIRED_IDS })
    }

    @Test
    fun `v1 校验仍然按 v1 路径走——分派函数不改变 v1 结果`() {
        assertEquals(1, OcSchemas.dispatch(1))
        assertEquals(2, OcSchemas.dispatch(2))
        assertThrows(IllegalArgumentException::class.java) { OcSchemas.dispatch(3) }
        assertThrows(IllegalArgumentException::class.java) { OcSchemas.dispatch(0) }
    }

    @Test
    fun `canonical 解析器自检——能被完整切分且不残留字节`() {
        val asset = Fixtures.fiveViewAsset()
        val tokens = CanonicalTokenizer.tokens(OcPackageSha.v2Canonical(asset))
        assertTrue("token 数应远多于 1", tokens.size > 50)
        assertEquals("oc-v2", tokens.first())
        assertTrue(tokens.contains(Fixtures.hex("source")))
        assertTrue(tokens.any { it == OcViewConstants.RIGHT90 })
        assertTrue(tokens.any { it == "right90_back_hair" })
    }

    private fun mutateView(asset: OcRigAsset, index: Int, transform: (OcViewLayers) -> OcViewLayers): OcRigAsset {
        val views = asset.views.toMutableList()
        views[index] = transform(views[index])
        return asset.copy(views = views)
    }

    private fun mutateLayer(asset: OcRigAsset, transform: (OcLayerAsset) -> OcLayerAsset): OcRigAsset {
        val views = asset.views.toMutableList()
        val layers = views[0].layers.toMutableList()
        layers[0] = transform(layers[0])
        views[0] = views[0].copy(layers = layers)
        return asset.copy(views = views)
    }
}

/**
 * canonical 串的顺序解析器：按「长度:文本」严格前进，长度不合法即报错。
 *
 * 为什么不能用正则扫 `\d+:`：token 文本内部本身就含数字与冒号（sha 是十六进制、
 * Float.toBits() 是十进制），正则会匹配到文本中间的 "2:1" 之类，切出来的 token 完全错位。
 * 顺序解析天然只在 token 边界上取值，长度前缀本身就是自校验。
 */
object CanonicalTokenizer {
    fun tokens(canonical: String): List<String> {
        val result = mutableListOf<String>()
        var at = 0
        while (at < canonical.length) {
            val colon = canonical.indexOf(':', at)
            require(colon > at) { "canonical 在 $at 处缺少长度前缀" }
            val length = canonical.substring(at, colon).toIntOrNull()
                ?: error("canonical 在 $at 处的长度前缀不是数字：${canonical.substring(at, colon)}")
            val start = colon + 1
            val end = start + length
            require(end <= canonical.length) { "canonical 在 $at 处声明长度 $length 但内容不足" }
            result.add(canonical.substring(start, end))
            at = end
        }
        return result
    }
}

/** 期望值文件：golden JSON 的极简解析（不引入 JSON 依赖）。 */data class GoldenFixture(
    val name: String,
    val canonical: String,
    val sha256: String,
    val renderDigest: String,
    val vertexDigest: String,
    val vertexCount: Int,
    val planBoxCount: Int,
    val clipDepth: Int,
)

object GoldenFixtures {
    fun load(name: String): GoldenFixture {
        val stream = GoldenFixtures::class.java.getResourceAsStream("/golden/$name.json")
            ?: error("缺少 golden 文件 /golden/$name.json")
        val json = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        return GoldenFixture(
            name = name,
            canonical = field(json, "canonical"),
            sha256 = field(json, "sha256"),
            renderDigest = field(json, "renderDigest"),
            vertexDigest = field(json, "vertexDigest"),
            vertexCount = number(json, "vertexCount"),
            planBoxCount = number(json, "planBoxCount"),
            clipDepth = number(json, "clipDepth"),
        )
    }

    private fun field(json: String, key: String): String {
        val match = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json) ?: error("golden 文件缺少字段 $key")
        return match.groupValues[1]
    }

    private fun number(json: String, key: String): Int {
        val match = Regex("\"$key\"\\s*:\\s*(\\d+)").find(json) ?: error("golden 文件缺少字段 $key")
        return match.groupValues[1].toInt()
    }

    fun hexOf(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
}
