package com.zhiyu.app.domain

import kotlin.math.*

/** Interleaved XYZ/normal/RGBA triangles. Meshes are deterministic and never alter OC parameters. */
data class AvatarMesh(val vertices: FloatArray) { val vertexCount: Int get() = vertices.size / 10 }

object AvatarMeshFactory {
    fun build(identity: OcIdentity, motion: OcMotion = OcMotion()): AvatarMesh {
        OcIdentityRules.check(identity); OcRigRules.motion(motion)
        val mesh = Builder()
        val b = identity.body
        val h = b.height
        mesh.ellipsoid(0f, -.22f * h, 0f, .36f * b.bodyWidth * (1f + motion.breath * .012f),
            .56f * h * (1f + motion.breath * .008f), .22f, b.clothes)
        mesh.ellipsoid(0f, .34f * h, 0f, .12f, .2f, .11f, b.skin)
        val turn = motion.headTurn * .07f
        mesh.ellipsoid(turn, .82f * h, .015f, .32f * b.headWidth, .4f * b.headHeight, .29f, b.skin)
        mesh.hair(b, h, turn)
        for (side in listOf(-1f, 1f)) {
            mesh.ellipsoid(side * .105f * b.eyeSpacing + turn, .86f * h, .284f,
                .06f * b.eyeSize, (.055f * b.eyeSize * (1f - .93f * motion.blink)).coerceAtLeast(.005f), .014f, 0xfffcfbf7.toInt())
            mesh.ellipsoid(side * .105f * b.eyeSpacing + turn, .86f * h, .299f,
                .028f * b.eyeSize, (.038f * b.eyeSize * (1f - .93f * motion.blink)).coerceAtLeast(.003f), .012f, b.eyes)
            mesh.ellipsoid(side * .105f * b.eyeSpacing + turn, .86f * h, .31f,
                .012f * b.eyeSize, (.025f * (1f - .93f * motion.blink)).coerceAtLeast(.002f), .005f, 0xff302c30.toInt())
            mesh.ellipsoid(side * .4f * b.bodyWidth, -.14f * h, 0f, .105f, .43f * h, .1f, b.clothes, side * 8f)
            mesh.ellipsoid(side * .46f * b.bodyWidth, -.56f * h, .015f, .085f, .115f, .08f, b.skin)
            mesh.ellipsoid(side * .16f, -.96f * h, 0f, .125f, .4f * h, .13f, 0xff555965.toInt())
            mesh.ellipsoid(side * .16f, -1.3f * h, .055f, .13f, .12f, .22f, 0xff4d443f.toInt())
        }
        mesh.ellipsoid(turn, .77f * h, .299f, .035f * b.noseSize, .05f * b.noseSize, .028f, b.skin)
        mesh.ellipsoid(turn, .64f * h, .273f, .065f * b.mouthWidth,
            .009f + motion.mouthOpen * .045f, .013f, 0xffad7470.toInt())
        identity.parts.forEach { mesh.accessory(it, motion.accessorySway) }
        return mesh.finish()
    }

    private class Builder {
        private var values = FloatArray(48_000)
        private var size = 0
        fun finish(): AvatarMesh {
            return AvatarMesh(values.copyOf(size))
        }
        private fun add(value: Float) {
            require(size < MAX_FLOATS) { "角色几何超过本机资源上限" }
            if (size == values.size) values = values.copyOf((values.size * 2).coerceAtMost(MAX_FLOATS))
            values[size++] = value
        }
        fun ellipsoid(x: Float, y: Float, z: Float, rx: Float, ry: Float, rz: Float, color: Int,
            angle: Float = 0f, rows: Int = 10, columns: Int = 16) {
            fun point(row: Int, column: Int): FloatArray {
                val latitude = PI * row / rows; val longitude = PI * 2 * column / columns
                val px = (sin(latitude) * cos(longitude) * rx).toFloat()
                val py = (cos(latitude) * ry).toFloat()
                val a = Math.toRadians(angle.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
                return floatArrayOf(x + px * c - py * s, y + px * s + py * c, z + (sin(latitude) * sin(longitude) * rz).toFloat())
            }
            for (row in 0 until rows) for (column in 0 until columns) {
                val a = point(row, column); val b = point(row + 1, column)
                val c = point(row + 1, column + 1); val d = point(row, column + 1)
                triangle(a, b, c, color); triangle(a, c, d, color)
            }
        }
        fun hair(body: OcBody, height: Float, turn: Float) {
            val color = body.hair
            val rx = .337f * body.headWidth; val ry = .416f * body.headHeight
            val y = .82f * height
            // A curved cap follows the skull; the front terminates above the brows.
            fun p(row: Int, col: Int): FloatArray {
                val longitude = col * 2.0 * PI / 20
                val front = sin(longitude).toFloat().coerceAtLeast(0f)
                val maxLat = 1.9 - front * .62
                val lat = row / 7.0 * maxLat
                return floatArrayOf(turn + (sin(lat) * cos(longitude) * rx).toFloat(),
                    y + (cos(lat) * ry).toFloat(), (sin(lat) * sin(longitude) * .305).toFloat())
            }
            for (row in 0 until 7) for (col in 0 until 20) {
                val a = p(row, col); val b = p(row + 1, col); val c = p(row + 1, col + 1); val d = p(row, col + 1)
                triangle(a, b, c, color); triangle(a, c, d, color)
            }
            val length = body.hairLength * .52f
            for (side in listOf(-1f, 1f)) {
                ellipsoid(turn + side * rx * .88f, y - length * .25f, -.05f,
                    .09f, .25f + length * .5f, .18f, color)
            }
            when (body.hairStyle) {
                "side" -> ellipsoid(turn + .18f, y + .27f, .2f, .17f, .15f, .09f, color, -35f)
                "twin" -> for (side in listOf(-1f, 1f)) ellipsoid(turn + side * .4f, y - .05f, -.06f,
                    .13f, .3f + length, .13f, color, side * 15f)
                "short" -> ellipsoid(turn - .06f, y + .32f, .2f, .22f, .11f, .07f, color, 8f)
                else -> ellipsoid(turn - .08f, y + .25f, .235f, .23f, .1f, .07f, color, -12f)
            }
        }
        fun accessory(part: OcPart, sway: Float) {
            val x = (part.x - .5f) * 2.5f; val y = ( .5f - part.y) * 3f
            val w = part.width * 2f; val h = part.height * 2f
            val angle = part.angle + sway * 4f
            when (part.kind) {
                "wing" -> {
                    ellipsoid(x, y, part.z, w * .46f, h * .48f, .055f, part.color, angle, 6, 12)
                    for (i in 0..3) ellipsoid(x + (i - 1.5f) * w * .12f, y - h * .18f,
                        part.z + .018f, w * .09f, h * .45f, .03f, part.color, angle + (i - 1.5f) * 10f, 5, 10)
                }
                "weapon" -> {
                    ellipsoid(x, y, part.z, w * .055f, h * .5f, .03f, part.color, angle, 6, 10)
                    ellipsoid(x, y - h * .26f, part.z + .02f, w * .4f, h * .025f, .045f, 0xffbfa16e.toInt(), angle, 4, 10)
                    ellipsoid(x, y - h * .39f, part.z, w * .07f, h * .1f, .04f, 0xff604c42.toInt(), angle, 4, 10)
                }
                "horn" -> cone(x, y, part.z, w * .25f, h, angle, part.color)
                "tail" -> ellipsoid(x, y, part.z, w * .18f, h * .5f, w * .2f, part.color, angle, 8, 12)
                "ear" -> ellipsoid(x, y, part.z, w * .24f, h * .5f, .06f, part.color, angle, 7, 12)
                "ornament", "custom" -> ellipsoid(x, y, part.z, w * .5f, h * .5f, .07f, part.color, angle, 7, 12)
            }
        }
        private fun cone(x: Float, y: Float, z: Float, r: Float, h: Float, angle: Float, color: Int) {
            val a = Math.toRadians(angle.toDouble()); val sx = -sin(a).toFloat() * h; val sy = cos(a).toFloat() * h
            val top = floatArrayOf(x + sx, y + sy, z)
            for (i in 0 until 16) {
                val t = 2 * PI * i / 16; val n = 2 * PI * (i + 1) / 16
                triangle(floatArrayOf(x + cos(t).toFloat() * r, y, z + sin(t).toFloat() * r),
                    floatArrayOf(x + cos(n).toFloat() * r, y, z + sin(n).toFloat() * r), top, color)
            }
        }
        private fun triangle(a: FloatArray, b: FloatArray, c: FloatArray, color: Int) {
            val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
            val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            val length = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(.00001f)
            val red = ((color shr 16) and 255) / 255f; val green = ((color shr 8) and 255) / 255f; val blue = (color and 255) / 255f
            for (p in listOf(a, b, c)) {
                add(p[0]); add(p[1]); add(p[2])
                add(nx / length); add(ny / length); add(nz / length)
                add(red); add(green); add(blue); add(1f)
            }
        }
        private companion object { const val MAX_FLOATS = 5_000_000 }
    }
}
