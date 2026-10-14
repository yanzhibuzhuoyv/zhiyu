package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import java.time.Instant

/** A stop/resume never revives an old pending message or an in-flight delivery revision. */
internal object ContactDeliveryGate {
    fun reason(store:ContactPauseStore,chat:String,expected:ContactPauseState,message:Message?=null):String? {
        val current=store.read(chat)
        if(current.paused)return "user_paused"
        if(current.revision!=expected.revision)return "contact_state_changed"
        if(message!=null && current.changedAtMillis>0 && Instant.parse(message.createdAt).toEpochMilli()<=current.changedAtMillis)
            return "contact_stale"
        return null
    }
    fun blocked(reason:String,now:Instant)=ProactiveCheck(false,null,now.plusSeconds(if(reason=="user_paused")86400 else 30).toString(),reason)
}
