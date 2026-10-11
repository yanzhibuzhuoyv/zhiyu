package com.zhiyu.app.domain

data class LocalCorrectionSpan(val start:Int,val end:Int,val text:String,val requiresSupplement:Boolean)

/** Caller verifies the immutable source and annotation SHA before selecting a continuous excerpt. */
object LocalCorrectionSpanRules {
    fun select(source:String,annotationStart:Int,annotationEnd:Int,available:LocalCorrectionSpan?=null,
        maximum:Int=12000):LocalCorrectionSpan {
        require(maximum in 2..12000 && annotationStart >= 0 && annotationEnd in annotationStart + 1..source.length)
        require(boundary(source,annotationStart) && boundary(source,annotationEnd))
        if (available != null) {
            require(available.start >= 0 && available.end in available.start + 1..source.length &&
                boundary(source,available.start) && boundary(source,available.end) &&
                source.substring(available.start,available.end) == available.text)
        }
        // A narrow denial is only an annotation pointer, not the complete corrected utterance.
        if (source.length <= maximum) return LocalCorrectionSpan(0,source.length,source,false)
        if (available != null && available.text.length <= maximum &&
            available.start < annotationEnd && available.end > annotationStart) {
            return available.copy(requiresSupplement=true)
        }
        // Preserve adjacent context as one original span. A partial long source cannot establish completeness.
        val width = annotationEnd - annotationStart
        val before = minOf(annotationStart,maxOf(0,maximum - width) / 4)
        var start = annotationStart - before
        var end = minOf(source.length,start + maximum)
        if (!boundary(source,start)) start++
        if (!boundary(source,end)) end--
        check(end > start)
        return LocalCorrectionSpan(start,end,source.substring(start,end),true)
    }
    private fun boundary(text:String,index:Int) = index == 0 || index == text.length ||
        !Character.isHighSurrogate(text[index - 1]) || !Character.isLowSurrogate(text[index])
}
