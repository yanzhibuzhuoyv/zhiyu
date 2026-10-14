package com.zhiyu.app.domain.planar

/** A fixed scene never responds to the character animation channels. */
enum class PlanarMotionMode { STATIC, EXPRESSION, CHARACTER }

/** Hidden or occluded features can be disabled by the person reviewing the source image. */
data class PlanarFeatureVisibility(
    val eyeLeft: Boolean = true,
    val eyeRight: Boolean = true,
    val mouth: Boolean = true,
)
