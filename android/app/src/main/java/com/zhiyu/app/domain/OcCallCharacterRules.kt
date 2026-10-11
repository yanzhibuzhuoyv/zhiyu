package com.zhiyu.app.domain

/** Resolves a call's display without publishing a new character or changing its saved identity. */
object OcCallCharacterRules {
    fun resolve(saved: OcIdentity?, reference: OcAssetRef?): OcIdentity? {
        if (reference == null) return null
        // The confirmed visual profile is authoritative even without an editor manifest.
        // Stable IDs avoid restarting photo loading on every mouth/breath animation frame.
        val photo = OcIdentityRules.check(OcIdentity(reference.id, reference.visualVersion, "photo_subject",
            reference = reference, photoSource = reference, confirmed = true))
        if (saved != null && saved.confirmed && saved.route == "photo_subject" && saved.reference == reference)
            return OcIdentityRules.check(saved)
        return photo
    }
}
