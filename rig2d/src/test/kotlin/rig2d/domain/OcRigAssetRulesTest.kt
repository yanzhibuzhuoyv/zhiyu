package rig2d.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 计划 §2.3 第一个测试文件：OcRigAssetRulesTest
 *
 * 覆盖：越界 yaw、缺层、重复 viewId、z 冲突、margin 不足、视角带不重叠 → 全部拒绝；
 * bounds 越界；层数/视角数上限。
 *
 * 纪律：所有拒绝都断言**抛出 IllegalArgumentException 且消息非空**，
 * 不接受「静默裁剪」「尽力而为」这类 fail open 行为。
 */
class OcRigAssetRulesTest {

    private fun rejected(block: () -> Unit, why: String) {
        val failure = assertThrows(IllegalArgumentException::class.java, block)
        assertTrue("$why：拒绝原因不能为空", !failure.message.isNullOrBlank())
    }

    // ------------------------------------------------------------ 通过路径

    @Test
    fun `5 视角规范资产通过校验并按 yaw 降序归一化`() {
        val asset = Fixtures.fiveViewAsset()
        val checked = OcRigAssetRules.check(asset)
        assertEquals(OcRigAssetRules.SCHEMA_VERSION, checked.schemaVersion)
        assertEquals(
            listOf("right90", "right45", "front", "left45", "left90"),
            checked.views.map { it.viewId },
        )
        assertEquals(30f, checked.views.first().yawHalfWidth, 0f)
        assertTrue(OcRigAssetRules.isValid(asset))
    }

    @Test
    fun `视图顺序无关——乱序输入归一化后得到同一结果`() {
        val asset = Fixtures.fiveViewAsset()
        val shuffled = asset.copy(views = asset.views.reversed())
        assertEquals(
            OcRigAssetRules.check(asset).views.map { it.viewId },
            OcRigAssetRules.check(shuffled).views.map { it.viewId },
        )
    }

    @Test
    fun `isValid 对非法资产返回 false 而不是抛出`() {
        val broken = Fixtures.fiveViewAsset().copy(sourceSha = "not-a-sha")
        assertFalse(OcRigAssetRules.isValid(broken))
    }

    // ------------------------------------------------------------ schema / 源

    @Test
    fun `schema 版本不是 v2 即拒绝`() {
        rejected({ OcRigAssetRules.check(Fixtures.fiveViewAsset().copy(schemaVersion = 1)) }, "schema v1 走 v2 校验")
        rejected({ OcRigAssetRules.check(Fixtures.fiveViewAsset().copy(schemaVersion = 3)) }, "未知 schema 版本")
    }

    @Test
    fun `sourceSha 形状不符即拒绝`() {
        rejected({ OcRigAssetRules.check(Fixtures.fiveViewAsset().copy(sourceSha = "abc")) }, "太短的 sourceSha")
        rejected({ OcRigAssetRules.check(Fixtures.fiveViewAsset().copy(sourceSha = "Z".repeat(64))) }, "非十六进制 sourceSha")
    }

    @Test
    fun `approvedAt 为负即拒绝`() {
        rejected({ OcRigAssetRules.check(Fixtures.fiveViewAsset().copy(approvedAt = -1L)) }, "负的批准时间")
    }

    // ------------------------------------------------------------ 视角维度

    @Test
    fun `视角数量超出上限即拒绝`() {
        val asset = Fixtures.fiveViewAsset()
        val duplicated = asset.copy(
            views = asset.views + (0 until 6).map { index ->
                Fixtures.view("extra$index", (index + 1) * 7f, 0.2f)
            },
        )
        rejected({ OcRigAssetRules.check(duplicated) }, "视角数超过 8")
    }

    @Test
    fun `视角列表为空即拒绝`() {
        rejected({ OcRigAssetRules.check(Fixtures.fiveViewAsset().copy(views = emptyList())) }, "没有视角")
    }

    @Test
    fun `重复 viewId 即拒绝`() {
        val asset = Fixtures.fiveViewAsset()
        val duplicated = asset.copy(views = asset.views + asset.views.first())
        rejected({ OcRigAssetRules.check(duplicated) }, "重复 viewId")
    }

    @Test
    fun `viewId 形状非法即拒绝`() {
        val asset = Fixtures.fiveViewAsset()
        val views = asset.views.toMutableList()
        views[0] = views[0].copy(viewId = "Right 90!")
        rejected({ OcRigAssetRules.check(asset.copy(views = views)) }, "viewId 含非法字符")
    }

    @Test
    fun `yaw 越界即拒绝`() {
        rejected({ OcRigAssetRules.check(assetWithFirstView { it.copy(yawCenter = 90.5f) }) }, "yaw 超过 +90")
        rejected({ OcRigAssetRules.check(assetWithFirstView { it.copy(yawCenter = -91f) }) }, "yaw 低于 -90")
        rejected({ OcRigAssetRules.check(assetWithFirstView { it.copy(yawCenter = Float.NaN) }) }, "yaw 非有限数")
    }

    @Test
    fun `yawHalfWidth 越界即拒绝`() {
        rejected({ OcRigAssetRules.check(assetWithFirstView { it.copy(yawHalfWidth = 4.9f) }) }, "半宽过小")
        rejected({ OcRigAssetRules.check(assetWithFirstView { it.copy(yawHalfWidth = 45.1f) }) }, "半宽过大")
        rejected({ OcRigAssetRules.check(assetWithFirstView { it.copy(yawHalfWidth = Float.POSITIVE_INFINITY) }) }, "半宽非有限数")
    }

    // ------------------------------------------------------------ 层维度

    @Test
    fun `缺层（视角无层）即拒绝`() {
        rejected({ OcRigAssetRules.check(assetWithFirstView { it.copy(layers = emptyList()) }) }, "空层栈")
    }

    @Test
    fun `层数超出上限即拒绝`() {
        val many = (0 until 65).map { index -> Fixtures.layer("layer_$index", "face", z = index, depth = 0f) }
        rejected({ OcRigAssetRules.check(assetWithFirstView { it.copy(layers = many) }) }, "层数超过 64")
    }

    @Test
    fun `重复层编号即拒绝`() {
        val view = Fixtures.fiveViewAsset().views.first()
        val duplicated = view.copy(layers = view.layers + view.layers.first().copy(z = 99))
        rejected({ OcRigAssetRules.check(assetWithFirstView { duplicated }) }, "重复层 id")
    }

    @Test
    fun `z 冲突即拒绝——层序不可含糊`() {
        val view = Fixtures.fiveViewAsset().views.first()
        val conflict = view.copy(layers = view.layers.map { it.copy(z = 5) })
        rejected({ OcRigAssetRules.check(assetWithFirstView { conflict }) }, "同视角内 z 相同")
    }

    @Test
    fun `z 越界即拒绝`() {
        val view = Fixtures.fiveViewAsset().views.first()
        val layers = view.layers.toMutableList()
        layers[0] = layers[0].copy(z = 129)
        rejected({ OcRigAssetRules.check(assetWithFirstView { view.copy(layers = layers) }) }, "z 超过 128")
    }

    @Test
    fun `未知层类别即拒绝`() {
        val view = Fixtures.fiveViewAsset().views.first()
        val layers = view.layers.toMutableList()
        layers[0] = layers[0].copy(kind = "tentacle")
        rejected({ OcRigAssetRules.check(assetWithFirstView { view.copy(layers = layers) }) }, "未在 layerKinds 里的类别")
    }

    @Test
    fun `层 id 形状非法即拒绝`() {
        val view = Fixtures.fiveViewAsset().views.first()
        val layers = view.layers.toMutableList()
        layers[0] = layers[0].copy(id = "坏 id")
        rejected({ OcRigAssetRules.check(assetWithFirstView { view.copy(layers = layers) }) }, "层 id 含空格与中文")
    }

    // ------------------------------------------------------------ depth / bounds / margin

    @Test
    fun `depth 越界即拒绝`() {
        val view = Fixtures.fiveViewAsset().views.first()
        val layers = view.layers.toMutableList()
        layers[0] = layers[0].copy(depth = 1.5f)
        rejected({ OcRigAssetRules.check(assetWithFirstView { view.copy(layers = layers) }) }, "depth 超过 1")
    }

    @Test
    fun `depth 非有限数即拒绝`() {
        val view = Fixtures.fiveViewAsset().views.first()
        val layers = view.layers.toMutableList()
        layers[0] = layers[0].copy(depth = Float.NaN)
        rejected({ OcRigAssetRules.check(assetWithFirstView { view.copy(layers = layers) }) }, "depth 为 NaN")
    }

    @Test
    fun `bounds 越界即拒绝`() {
        listOf(
            listOf(0.6f, 0.1f, 0.5f, 0.2f) to "x+w 超过 1",
            listOf(0.1f, 0.6f, 0.2f, 0.5f) to "y+h 超过 1",
            listOf(-0.01f, 0.1f, 0.2f, 0.2f) to "x 为负",
            listOf(0.1f, 0.1f, 0f, 0.2f) to "宽度为 0",
            listOf(0.1f, 0.1f, 0.2f) to "不是 4 个数",
        ).forEach { (bounds, why) ->
            rejected({ OcRigAssetRules.check(assetWithFirstView { viewWithBounds(it, bounds) }) }, why)
        }
    }

    @Test
    fun `margin 形状越界即拒绝`() {
        rejected({ OcRigAssetRules.check(assetWithFirstView { viewWithMargin(it, -0.01f) }) }, "margin 为负")
        rejected({ OcRigAssetRules.check(assetWithFirstView { viewWithMargin(it, 1.01f) }) }, "margin 超过 1")
        rejected({ OcRigAssetRules.check(assetWithFirstView { viewWithMargin(it, Float.NaN) }) }, "margin 为 NaN")
    }

    @Test
    fun `层资源 sha 形状不符即拒绝`() {
        val view = Fixtures.fiveViewAsset().views.first()
        val layers = view.layers.toMutableList()
        layers[0] = layers[0].copy(asset = layers[0].asset.copy(sha256 = "xyz"))
        rejected({ OcRigAssetRules.check(assetWithFirstView { view.copy(layers = layers) }) }, "层资源 sha 非法")
    }

    @Test
    fun `层资源外观版本为负即拒绝`() {
        val view = Fixtures.fiveViewAsset().views.first()
        val layers = view.layers.toMutableList()
        layers[0] = layers[0].copy(asset = layers[0].asset.copy(visualVersion = -1))
        rejected({ OcRigAssetRules.check(assetWithFirstView { view.copy(layers = layers) }) }, "外观版本为负")
    }

    // ------------------------------------------------------------ 网格契约

    @Test
    fun `网格顶点数与 columns rows 不符即拒绝`() {
        val view = Fixtures.fiveViewAsset().views.first()
        val bad = view.copy(mesh = view.mesh.copy(vertexCount = 824))
        rejected({ OcRigAssetRules.check(assetWithFirstView { bad }) }, "顶点数少一个")
    }

    @Test
    fun `网格尺寸超出 2到64 即拒绝`() {
        val view = Fixtures.fiveViewAsset().views.first()
        rejected({ OcRigAssetRules.check(assetWithFirstView { view.copy(mesh = OcViewMesh(1, 32, Fixtures.ref("m"), 2 * 33)) }) }, "columns 小于 2")
        rejected({ OcRigAssetRules.check(assetWithFirstView { view.copy(mesh = OcViewMesh(65, 32, Fixtures.ref("m"), 66 * 33)) }) }, "columns 超过 64")
    }

    @Test
    fun `顶点缓冲含非有限数即拒绝`() {
        val mesh = Fixtures.mesh("vertices")
        val ok = FloatArray(mesh.vertexCount * 2) { it.toFloat() }
        OcMeshRules.checkVertices(ok, mesh)
        val bad = ok.copyOf().also { it[7] = Float.NaN }
        rejected({ OcMeshRules.checkVertices(bad, mesh) }, "顶点为 NaN")
        rejected({ OcMeshRules.checkVertices(ok.copyOf(10), mesh) }, "缓冲长度不符")
    }

    // ------------------------------------------------------------ 代理与对称平面

    @Test
    fun `对称平面法线未归一化即拒绝`() {
        val asset = Fixtures.fiveViewAsset()
        val proxy = asset.proxy!!.copy(symmetryPlane = listOf(0f, 0f, 3f, 0f))
        rejected({ OcRigAssetRules.check(asset.copy(proxy = proxy)) }, "法线长度为 3")
    }

    @Test
    fun `对称平面不是 4 个数即拒绝`() {
        val asset = Fixtures.fiveViewAsset()
        val proxy = asset.proxy!!.copy(symmetryPlane = listOf(0f, 0f, 1f))
        rejected({ OcRigAssetRules.check(asset.copy(proxy = proxy)) }, "对称平面缺偏移")
    }

    @Test
    fun `代理资源 sha 非法即拒绝`() {
        val asset = Fixtures.fiveViewAsset()
        val proxy = asset.proxy!!.copy(depth = asset.proxy!!.depth.copy(sha256 = "short"))
        rejected({ OcRigAssetRules.check(asset.copy(proxy = proxy)) }, "深度资源 sha 非法")
    }

    @Test
    fun `无代理的资产仍然合法——代理是可选产出`() {
        val asset = Fixtures.fiveViewAsset(withProxy = false)
        assertEquals(null, OcRigAssetRules.check(asset).proxy)
    }

    // ------------------------------------------------------------ 视角带

    @Test
    fun `视角带不重叠即拒绝`() {
        val asset = Fixtures.fiveViewAsset()
        val views = asset.views.map { if (it.viewId == "front") it.copy(yawHalfWidth = 5f) else it }
        rejected({ OcRigAssetRules.check(asset.copy(views = views)) }, "front 带宽 5 导致与 ±45 之间出现漏角")
    }

    @Test
    fun `只有 front 单视角的资产通过——单视角是合法的退化形态`() {
        val onlyFront = OcRigAsset(views = listOf(Fixtures.view("front", 0f, 0f)), sourceSha = Fixtures.hex("src"), approvedAt = 1L)
        assertEquals(1, OcRigAssetRules.check(onlyFront).views.size)
    }

    @Test
    fun `每视角 rig 区域数超上限即拒绝`() {
        val view = Fixtures.fiveViewAsset().views.first()
        val regions = (0 until 129).map { OcRigRegionV1("r$it", "head") }
        rejected({ OcRigAssetRules.check(assetWithFirstView { view.copy(rig = OcRigV1(regions)) }) }, "区域数超过 128")
    }

    // ------------------------------------------------------------ helpers

    private fun assetWithFirstView(transform: (OcViewLayers) -> OcViewLayers): OcRigAsset {
        val asset = Fixtures.fiveViewAsset()
        return asset.copy(views = listOf(transform(asset.views.first())) + asset.views.drop(1))
    }

    private fun viewWithBounds(view: OcViewLayers, bounds: List<Float>): OcViewLayers =
        view.copy(layers = view.layers.map { it.copy(bounds = bounds) })

    private fun viewWithMargin(view: OcViewLayers, margin: Float): OcViewLayers =
        view.copy(layers = view.layers.map { it.copy(margin = margin) })
}
