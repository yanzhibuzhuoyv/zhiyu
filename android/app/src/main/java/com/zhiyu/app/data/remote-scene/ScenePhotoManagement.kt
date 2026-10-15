package com.zhiyu.app.data.remotescene

/** UI receives no credentials, prompts or mutable source metadata. */
data class ScenePhotoRow(val requestId:String,val day:String,val slot:String?,val status:String,
    val canReview:Boolean,val canRetry:Boolean,val canClear:Boolean,val hasPicture:Boolean)
data class ScenePhotoPage(val items:List<ScenePhotoRow>,val nextCursor:String?)
interface ScenePhotoManagement {
    suspend fun page(chat:String,cursor:String?=null):ScenePhotoPage
    suspend fun preview(chat:String,requestId:String):ByteArray
    suspend fun recheck(chat:String,requestId:String)
    suspend fun retryFailed(chat:String,requestId:String)
    suspend fun clear(chat:String,requestId:String)
}
