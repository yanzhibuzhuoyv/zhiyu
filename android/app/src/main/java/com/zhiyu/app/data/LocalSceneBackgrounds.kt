package com.zhiyu.app.data

import android.graphics.*
import com.zhiyu.app.domain.LocalDailySlot
import kotlin.random.Random

/** Local scene variation also works without a paid image service. Never draws a different face. */
object LocalSceneBackgrounds {
    fun draw(seed: Int, slot: LocalDailySlot, width: Int = 768, height: Int = 1024): Bitmap {
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image); val random = Random(seed)
        val night = slot.time >= "20:00" || slot.time < "06:00"
        val sky = if (night) Color.rgb(55, 62, 80) else if (slot.time >= "17:00") Color.rgb(247, 204, 173) else Color.rgb(223, 235, 218)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(sky)
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), sky, Color.rgb(247, 239, 216), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint); paint.shader = null
        if ("food" in slot.tags || "object" in slot.tags || night) room(canvas, paint, width, height, random, slot)
        else outside(canvas, paint, width, height, random, "cat" in slot.tags)
        // Fine variations in paper/light make each dated frame distinct while the OC stays fixed.
        repeat(180) { paint.color = Color.argb(12, 120, 116, 90); canvas.drawCircle(random.nextFloat() * width, random.nextFloat() * height, random.nextFloat() * 3 + 1, paint) }
        return image
    }
    private fun room(c: Canvas, p: Paint, w: Int, h: Int, r: Random, slot: LocalDailySlot) {
        val wf = w.toFloat(); val hf = h.toFloat()
        p.color = Color.rgb(186, 198, 174); c.drawRoundRect(wf*.08f,hf*.08f,wf*.78f,hf*.48f,12f,12f,p)
        p.color = Color.rgb(239, 238, 210); c.drawRect(wf*.13f,hf*.13f,wf*.72f,hf*.43f,p)
        p.color = Color.rgb(168, 159, 137); c.drawRect(wf*.41f,hf*.13f,wf*.43f,hf*.43f,p)
        p.color = Color.rgb(155, 122, 94); c.drawRect(0f,hf*.72f,wf,hf,p)
        val x = wf*(.3f+r.nextFloat()*.2f)
        if ("food" in slot.tags) {
            p.color = Color.rgb(247,242,222); c.drawOval(x-wf*.16f,hf*.76f,x+wf*.16f,hf*.9f,p)
            p.color = Color.rgb(214,168,109); c.drawRoundRect(x-wf*.1f,hf*.78f,x+wf*.1f,hf*.86f,20f,20f,p)
        } else {
            p.color = Color.rgb(139,162,130); c.drawRoundRect(x-wf*.14f,hf*.76f,x+wf*.14f,hf*.92f,10f,10f,p)
            p.color = Color.rgb(248,240,217); c.drawRect(x-wf*.1f,hf*.79f,x+wf*.1f,hf*.89f,p)
        }
        p.color = Color.rgb(223,217,197); c.drawRoundRect(wf*.75f,hf*.75f,wf*.9f,hf*.88f,16f,16f,p)
    }
    private fun outside(c: Canvas, p: Paint, w: Int, h: Int, r: Random, cat: Boolean) {
        val wf=w.toFloat(); val hf=h.toFloat()
        p.color=Color.rgb(143,165,126); c.drawOval(-wf*.2f,hf*.35f,wf*1.2f,hf*1.15f,p)
        p.color=Color.rgb(212,199,169)
        val path=Path().apply { moveTo(wf*.48f,hf*.5f); lineTo(wf*.55f,hf*.5f); lineTo(wf*.9f,hf); lineTo(wf*.13f,hf); close() }
        c.drawPath(path,p)
        repeat(8) { val x=r.nextFloat()*wf; p.color=Color.rgb(92,113,80); c.drawOval(x-50,hf*.32f+r.nextFloat()*40,x+50,hf*.58f,p) }
        if(cat) {
            val x=wf*.75f; val y=hf*.78f
            p.color=Color.rgb(183,170,149); c.drawOval(x-48,y-20,x+48,y+60,p); c.drawCircle(x,y-30,34f,p)
            val ears=Path().apply { moveTo(x-32,y-40); lineTo(x-24,y-82); lineTo(x-3,y-55); moveTo(x+32,y-40); lineTo(x+24,y-82); lineTo(x+3,y-55) }
            p.strokeWidth=20f; p.style=Paint.Style.STROKE; c.drawPath(ears,p); p.style=Paint.Style.FILL
        }
    }
}
