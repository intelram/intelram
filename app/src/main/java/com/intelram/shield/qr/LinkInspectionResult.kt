package com.intelram.shield.qr

enum class LinkVerdict { SAFE, CAUTION, UNSAFE, NOT_A_LINK }

data class LinkInspectionResult(
    val originalUrl: String,
    val finalUrl: String,
    val redirectHops: List<String>,
    val verdict: LinkVerdict,
    val reasons: List<String>,
    val safeBrowsingChecked: Boolean,
    val urlhausChecked: Boolean,
    val certificateValid: Boolean?,
)
