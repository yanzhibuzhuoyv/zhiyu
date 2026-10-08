package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import org.json.JSONObject

internal fun JSONObject.mediaAttachment() = MediaRules.check(MediaAttachment(
    getString("id"), getString("kind"), getString("url"), getString("mime_type"), getString("sha256"),
    if (isNull("day_id")) null else getString("day_id"), optInt("visual_version", 0), optString("source_label"),
))
internal fun JSONObject.visualProfile(): VisualProfile {
    val quota = getJSONObject("generation")
    val reference = if (isNull("reference_id")) null else getString("reference_id")
    val url = if (isNull("reference_url")) null else getString("reference_url")
    require((reference == null && url == null) || reference != null && url == MediaRules.path(reference)) { "OC 参考地址无效" }
    return VisualProfile(getString("mode"), optString("description"), getString("gender"), getString("hair"),
        getInt("visual_version"), reference, url, GenerationQuota(quota.getBoolean("configured"),
            quota.getInt("used"), quota.getInt("pending"), quota.getInt("remaining"), quota.getInt("limit")))
}
internal fun JSONObject.visualTemplate(): VisualTemplate {
    val id = getString("template_id")
    val url = getString("reference_url")
    require(url == MediaRules.templatePath(id)) { "外观样例地址无效" }
    return VisualTemplate(id, getString("name"), optString("description"), getString("gender"), getString("hair"),
        url, getString("reference_sha256"), getInt("image_count"))
}
