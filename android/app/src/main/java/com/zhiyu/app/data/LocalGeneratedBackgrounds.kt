package com.zhiyu.app.data

import android.content.Context
import android.graphics.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.*

/** Agnes only paints an empty setting. OC pixels/parts are rendered locally afterwards. */
class LocalGeneratedBackgrounds(context: Context, private val client: AgnesImageClient = AgnesImageClient(),
    private val approval: BackgroundImageApproval = BackgroundContentGate({ AgnesCredentialStore(context).read() }),
    private val beforeReserve: suspend () -> Unit = {}) {
    private val keys=AgnesCredentialStore(context)
    private val jobs=LocalImageJobStore(context)
    private val media=LocalMediaStore(context)
    private val approvals=BackgroundApprovalStore(context)
    suspend fun get(chat:String,day:String,version:Int,slot:LocalDailySlot):Bitmap? {
        val key=withContext(Dispatchers.IO) { keys.read() } ?:return null
        val prompt="生成一张自然随手拍风格的空场景，画面有变化和生活感。不要任何人物、人体、脸、翅膀、武器、文字或水印。" +
            "时间${slot.time}，场景主题${slot.activity}，配色自然。画面稍有留白方便在本机合成角色。"
        val hash=LocalMemoryCodec.sha(LocalMemoryCodec.bytes(prompt))
        val request=LocalMemoryCodec.sha("$chat:$day:$version:$hash".toByteArray()).take(32)
        val previous=withContext(Dispatchers.IO) { jobs.get(request) }
        if(previous!=null) {
            if(previous.job.state=="success" && previous.assetId!=null) return cached(previous.assetId)
            return null // Interrupted or rejected requests never launch another charged operation.
        }
        val job=LocalGenerationJob(request,chat,"scene",version,day,hash,"reserved")
        beforeReserve()
        var ownedReservation = false
        try {
            withContext(Dispatchers.IO) { jobs.reserve(job); ownedReservation = true; jobs.finish(request,"running") }
            val image=client.generate(key,prompt)
            if (!checked(image)) throw ApiFailure(422,"这张背景暂时不能使用，已保留本地场景","background_content_rejected")
            return withContext(Dispatchers.IO) {
                val asset=media.ingest("image",image.bytes,"生成的空场景",visualVersion=version,dayId=day,purpose="scene_background")
                jobs.finish(request,"success",asset.id)
                background(media.download(asset.id,asset.sha256))
            }
        } catch(error:Exception) {
            withContext(NonCancellable+Dispatchers.IO) {
                if(ownedReservation && jobs.get(request)?.job?.state in setOf("reserved","running")) {
                    val rejected=(error as? ApiFailure)?.errorCode in setOf("image_auth_failed","image_balance_insufficient",
                        "image_rate_limited","image_request_invalid","image_redirect_rejected","image_service_busy","background_content_rejected")
                    jobs.finish(request,if(rejected)"failed" else "unknown")
                }
            }
            if(error is CancellationException) throw error
            android.util.Log.w("ZhiYuImages","Scene generation unavailable; using a locally prepared background")
            return null
        }
    }

    private suspend fun checked(image: AgnesGeneratedImage): Boolean = approvals.checked(image, approval)

    private suspend fun cached(id: String): Bitmap? = try {
        val image = withContext(Dispatchers.IO) {
            val stored = media.details(id)
            require(stored.purpose == "scene_background" && stored.attachment.kind == "image") { "缓存不是生成的空场景" }
            val asset = stored.attachment
            AgnesGeneratedImage(media.download(id,asset.sha256),asset.mimeType)
        }
        if (checked(image)) withContext(Dispatchers.IO) { background(image.bytes) } else null
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { android.util.Log.w("ZhiYuImages","Scene cache unavailable; using a locally prepared background"); null }

    private fun background(bytes: ByteArray): Bitmap {
        val source = LocalPortraitProcessor.decode(bytes)
        try {
            val result = Bitmap.createBitmap(768, 1024, Bitmap.Config.ARGB_8888)
            val scale = maxOf(result.width.toFloat() / source.width, result.height.toFloat() / source.height)
            val width = source.width * scale; val height = source.height * scale
            Canvas(result).drawBitmap(source, null, RectF((result.width-width)/2, (result.height-height)/2,
                (result.width+width)/2, (result.height+height)/2), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return result
        } finally { source.recycle() }
    }
}
