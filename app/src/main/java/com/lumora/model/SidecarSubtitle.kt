package com.lumora.model

/**
 * A sidecar subtitle track a source found alongside the stream. Hosts that advertise themselves
 * as hardsubbed don't always burn the subtitles in - some serve the video clean and publish the
 * tracks as separate files, which nothing in the stream itself advertises, so the only way they
 * reach the player is the source handing them over explicitly.
 */
data class SidecarSubtitle(
    val url: String,
    val label: String? = null,
    /** ISO language code if the source knows one - only used to label the track picker. */
    val language: String? = null,
    val isDefault: Boolean = false
)
