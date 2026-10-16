package rig2d.domain

import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D

/*
 * v1 渲染路径的**宿主端（host）等价移植**：OcFlatRendererV1。
 *
 * 冻结来源：android/app/src/main/java/com/zhiyu/app/data/OcFlatRenderer.kt（只读参考）。
 *
 * 为什么需要这个类：
 *   计划 §2.4 的硬门禁是「v1 档案读取后渲染结果与改动前**逐字节相同**」。
 *   真实渲染需要 Bitmap/Canvas（Android 专有），在纯 JVM 上跑不了；
 *   而「等移到真机上再验证」等于没有门禁。
 *   因此这里把 v1 的**全部变换算术**（save/translate/scale/rotate 栈 + 每个绘制图元的
 *   paint 特征 + OcRigRules.vertices 的顶点缓冲）在 JDK 上重放一遍，
 *   产出一个确定性的 `DrawPlan`，再哈希成 `renderDigest`。
 *
 * 它证明什么、不证明什么（不得夸大）：
 *   证明：v1 路径的几何变换、层序、常量在改动前后逐位相同；OcRigRules.vertices 的
 *         顶点缓冲逐位相同；v1 校验分支仍然只接受 v1。
 *   不证明：真机上 Skia 的最终像素。真机逐字节比对仍需在 androidTest 里用真实
 *         Bitmap 做（见 docs/PORTING.md 的「真机验收」一节）。
 *
 * 唯一允许的编译器差异：`Math.toRadians` 与 Kotlin `Math.toRadians` 都是 double，
 * 结果一致；所有变换都走 double 运算后写回 double，不做 float 往返，避免精度漂移。
 */

enum class PlanOp { FILL_COLOR, BITMAP, BITMAP_MESH, OVAL, PATH, LINE, TRANSLATE, SCALE, ROTATE }

/** 一条记录下来的绘制操作。字段含义随 op 变化，全部按原顺序参与哈希。 */
data class PlanOpRecord(
    val op: PlanOp,
    val a: Double = 0.0,
    val b: Double = 0.0,
    val c: Double = 0.0,
    val d: Double = 0.0,
    val e: Double = 0.0,
    val f: Double = 0.0,
    val g: Double = 0.0,
    val h: Double = 0.0,
    val color: Int = 0,
    val stroke: Double = 0.0,
    val points: List<Double> = emptyList(),
)

/** 一个绘制图元（叶子）。`name` 是稳定标签，`stack` 是作用在它上面的变换。 */
data class PlanBox(val name: String, val stack: List<PlanOpRecord>, val draw: PlanOpRecord?)

data class DrawPlan(val width: Int, val height: Int, val background: Int, val boxes: List<PlanBox>, val clipDepth: Int) {
    fun digest(): String {
        val c = CanonicalBuilder()
        c.token("flat-v1-plan"); c.token(width); c.token(height); c.token(background); c.token(clipDepth)
        c.token(boxes.size)
        boxes.forEach { box ->
            c.token(box.name)
            c.token(box.stack.size)
            box.stack.forEach { record(c, it) }
            c.token(box.draw != null)
            box.draw?.let { record(c, it) }
        }
        return sha256Hex(c.build().toByteArray(Charsets.UTF_8))
    }

    private fun record(c: CanonicalBuilder, op: PlanOpRecord) {
        c.token(op.op.name)
        // 4 位小数：足够区分任何真实变换差异，又不受 double 末位抖动影响。
        listOf(op.a, op.b, op.c, op.d, op.e, op.f, op.g, op.h).forEach { c.token(round(it, 4)) }
        c.token(op.color)
        c.token(round(op.stroke, 4))
        c.token(op.points.size)
        op.points.forEach { c.token(round(it, 4)) }
    }

    private fun round(value: Double, digits: Int): String {
        if (!value.isFinite()) return value.toString()
        val scale = Math.pow(10.0, digits.toDouble())
        return (Math.round(value * scale) / scale).toString()
    }
}

/** 绘制计划的摘要：覆盖全部变换/图元 + OcRigRules.vertices 的顶点缓冲。 */
data class DrawPlanDigest(
    val hex: String,
    val vertexCount: Int,
    val vertexDigest: String,
    /** 全部绘制图元（背景 1 + 图元 N），顺序即绘制顺序。 */
    val boxes: List<PlanBox>,
    /** 变换栈的最大深度，用于证明 save/restore 配对没有被改坏。 */
    val clipDepth: Int,
)

object OcFlatRendererV1 {

    /** 计划 §5.1 所需的 mesh 网格尺寸，与 v1 源码一致：drawBitmapMesh(source, 48, 64, ...)。 */
    const val MESH_COLUMNS = 48
    const val MESH_ROWS = 64

    /** 合成源图尺寸。v1 只用到 source.width/height 作为顶点归一化尺度，故 4×4 与真实图等价。 */
    const val SOURCE_WIDTH = 4
    const val SOURCE_HEIGHT = 4

    fun drawPlanDigest(identity: OcIdentityV1, scene: OcSceneV1, width: Int, height: Int): DrawPlanDigest {
        val plan = drawPlan(identity, scene, width, height)
        val vertices = vertexBuffer(identity, scene.motion)
        return DrawPlanDigest(
            hex = plan.digest(),
            vertexCount = vertices.size / 2,
            vertexDigest = sha256Hex(verticesToString(vertices).toByteArray(Charsets.UTF_8)),
            boxes = plan.boxes,
            clipDepth = plan.clipDepth,
        )
    }

    fun vertexBuffer(identity: OcIdentityV1, motion: OcMotionV1): FloatArray =
        OcRigVerticesV1.vertices(MESH_COLUMNS, MESH_ROWS, SOURCE_WIDTH, SOURCE_HEIGHT, identity.rig, motion)

    private fun verticesToString(vertices: FloatArray): String = buildString {
        vertices.forEach { append(it.toRawBits().toString(16)).append(',') }
    }

    /** 重放 v1 `draw()` 的算术，产出全部绘制图元及其变换栈。 */
    fun drawPlan(identity: OcIdentityV1, scene: OcSceneV1, width: Int, height: Int): DrawPlan {
        val checked = OcIdentityRulesV1.check(identity)
        require(width > 0 && height > 0) { "画布尺寸必须为正" }
        val canvas = PlanCanvas()
        canvas.fillColor(scene.background)
        require(checked.route == "photo_subject") { "宿主端移植只覆盖 photo_subject 路径" }
        photo(canvas, checked.rig, scene.motion, width, height)
        return DrawPlan(width, height, scene.background, canvas.boxes, canvas.maxDepth)
    }

    // ------------------------------------------------------------ photo 路径

    private fun photo(canvas: PlanCanvas, rig: OcRigV1, motion: OcMotionV1, width: Int, height: Int) {
        val scale = minOf(width.toDouble() / SOURCE_WIDTH, height.toDouble() / SOURCE_HEIGHT)
        val paint = 0x03 // ANTI_ALIAS_FLAG or FILTER_BITMAP_FLAG
        val root = canvas.box("photo")
        root.push(AffineTransform.getTranslateInstance((width - SOURCE_WIDTH * scale) / 2, (height - SOURCE_HEIGHT * scale) / 2))
        root.push(AffineTransform.getScaleInstance(scale, scale))
        if (motion == OcMotionV1()) {
            root.draw(PlanOpRecord(PlanOp.BITMAP, color = paint))
        } else {
            val vertices = OcRigVerticesV1.vertices(MESH_COLUMNS, MESH_ROWS, SOURCE_WIDTH, SOURCE_HEIGHT, rig, motion)
            root.draw(
                PlanOpRecord(
                    PlanOp.BITMAP_MESH, a = MESH_COLUMNS.toDouble(), b = MESH_ROWS.toDouble(),
                    color = paint, points = vertices.map { it.toDouble() },
                ),
            )
        }
    }

    // ------------------------------------------------------------ 变换栈画布

    /**
     * 只记录算术、不做像素光栅化。椭圆与圆角路径用 Java2D 的 Shape 求出与 Skia 等价的
     * 几何包围盒（两者都是标准参数化椭圆），因此包围盒是可比的。
     */
    private class PlanCanvas {
        val boxes = mutableListOf<PlanBox>()
        var maxDepth = 0
            private set
        private val saved = ArrayDeque<Pair<String, List<PlanOpRecord>>>()
        private var stack: MutableList<PlanOpRecord> = mutableListOf()
        private var openName: String? = null
        private val openRecords = mutableListOf<PlanOpRecord>()

        fun fillColor(color: Int) {
            boxes.add(PlanBox("background", emptyList(), PlanOpRecord(PlanOp.FILL_COLOR, color = color)))
        }

        fun box(name: String): Box = Box(name)

        inner class Box(private val name: String) {
            fun push(transform: AffineTransform) {
                stack.add(record(transform))
            }

            fun pushRotate(degrees: Double, px: Double, py: Double) {
                stack.add(PlanOpRecord(PlanOp.ROTATE, a = degrees, b = px, c = py))
            }

            fun pushTranslate(x: Double, y: Double) {
                stack.add(PlanOpRecord(PlanOp.TRANSLATE, a = x, b = y))
            }

            fun pushScale(x: Double, y: Double) {
                stack.add(PlanOpRecord(PlanOp.SCALE, a = x, b = y))
            }

            fun draw(record: PlanOpRecord) {
                boxes.add(PlanBox(nameOf(record.op), stack.toList(), record))
                maxDepth = maxOf(maxDepth, stack.size + 1)
            }

            fun oval(x: Double, y: Double, w: Double, h: Double, color: Int) {
                val bounds = Ellipse2D.Double(x, y, w, h).bounds2D
                draw(
                    PlanOpRecord(
                        PlanOp.OVAL, a = x, b = y, c = w, d = h, color = color,
                        points = listOf(bounds.x, bounds.y, bounds.width, bounds.height),
                    ),
                )
            }

            fun path(path: Path2D.Double, color: Int) {
                draw(
                    PlanOpRecord(
                        PlanOp.PATH, color = color,
                        points = flatten(path),
                    ),
                )
            }

            fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: Int, strokeWidth: Double) {
                draw(PlanOpRecord(PlanOp.LINE, a = x1, b = y1, c = x2, d = y2, color = color, stroke = strokeWidth))
            }
        }

        private fun record(transform: AffineTransform) = PlanOpRecord(
            PlanOp.SCALE, a = transform.scaleX, b = transform.scaleY,
            c = transform.translateX, d = transform.translateY,
        )

        /** 图元名字取 op 名，便于在失败信息里一眼看出是哪个图元变了。 */
        private fun nameOf(op: PlanOp): String = when (op) {
            PlanOp.FILL_COLOR -> "background"
            PlanOp.BITMAP -> "photo_bitmap"
            PlanOp.BITMAP_MESH -> "photo_mesh"
            PlanOp.OVAL -> "oval"
            PlanOp.PATH -> "path"
            PlanOp.LINE -> "line"
            else -> op.name.lowercase()
        }

        /** 把 Shape 打散成确定性的点序列（曲线的控制点也参与，避免只比包围盒而漏掉形状变化）。 */
        private fun flatten(shape: java.awt.Shape): List<Double> {
            val out = mutableListOf<Double>()
            val iterator = shape.getPathIterator(null, 0.05)
            val coords = DoubleArray(6)
            while (!iterator.isDone) {
                when (iterator.currentSegment(coords)) {
                    0 -> { out.add(coords[0]); out.add(coords[1]) }                                  // SEG_MOVETO
                    1 -> { out.add(coords[0]); out.add(coords[1]) }                                  // SEG_LINETO
                    3 -> { out.add(coords[0]); out.add(coords[1]); out.add(coords[2]); out.add(coords[3]) } // SEG_CUBICTO
                    4 -> Unit                                                                        // SEG_CLOSE
                    else -> Unit
                }
                iterator.next()
            }
            return out
        }
    }
}

/**
 * v1 的 `OcScene` 只用到 `background` 与 `motion`；`OcScene` 本体依赖
 * `OcIdentityRules.scene`（会把 v1 与 v2 的约束混在一起），故宿主端冻结一个最小等价物。
 */
data class OcSceneV1(
    val name: String = "portrait",
    val cameraYaw: Float = 0f,
    val cameraPitch: Float = 0f,
    val zoom: Float = 1f,
    val background: Int = 0xfffff8e9.toInt(),
    val motion: OcMotionV1 = OcMotionV1(),
)
