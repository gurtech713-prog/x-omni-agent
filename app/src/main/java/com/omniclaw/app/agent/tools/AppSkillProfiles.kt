package com.omniclaw.app.agent.tools

import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-specific skill profiles — per-package-name knowledge that helps the agent
 * interact with apps whose UI varies across OEMs (especially camera apps).
 *
 * When [tap_element]("Shutter") is called, the executor tries in order:
 *   1. Text/content-description match (language-dependent)
 *   2. Known view IDs for the current foreground package (language-independent)
 *   3. VLM-based visual tap (last resort)
 *
 * This registry provides the view IDs for step 2. It's keyed by package name,
 * with a generic fallback for unknown camera apps.
 *
 * To add support for a new OEM's camera app, add an entry here with the
 * package name and the known view ID resource names for the shutter button,
 * mode switcher, flip camera, etc.
 */
@Singleton
class AppSkillProfiles @Inject constructor() {

    /**
     * Per-package view ID mappings. Each entry maps a logical action name
     * (e.g. "shutter", "flip_camera", "mode_switch") to the view ID resource
     * name (e.g. "com.sec.android.app.camera:id/shutter_button").
     */
    private val profiles: Map<String, Map<String, List<String>>> = mapOf(
        // Samsung camera
        "com.sec.android.app.camera" to mapOf(
            "shutter" to listOf("shutter_button", "shutter_button_normal", "thumbnail_aim_button"),
            "flip_camera" to listOf("front_back_switcher", "camera_flip_button"),
            "mode_switch" to listOf("mode_switcher", "mode_list_view"),
            "video_mode" to listOf("video_mode_button", "mode_video"),
            "gallery" to listOf("thumbnail_button", "review_thumbnail"),
        ),
        // Google Pixel camera
        "com.google.android.GoogleCamera" to mapOf(
            "shutter" to listOf("shutter_button", "gcam_shutter_button"),
            "flip_camera" to listOf("front_back_switcher", "camera_flip_button"),
            "mode_switch" to listOf("mode_switcher"),
            "video_mode" to listOf("video_mode_button"),
            "gallery" to listOf("thumbnail_button", "review_thumbnail"),
        ),
        // AOSP / LineageOS camera
        "com.android.camera" to mapOf(
            "shutter" to listOf("shutter_button", "shutter_button_normal"),
            "flip_camera" to listOf("camera_switch"),
            "mode_switch" to listOf("mode_picker"),
            "video_mode" to listOf("video_button"),
            "gallery" to listOf("thumbnail"),
        ),
        "com.android.camera2" to mapOf(
            "shutter" to listOf("shutter_button", "shutter_button_normal"),
            "flip_camera" to listOf("camera_switch"),
            "mode_switch" to listOf("mode_picker"),
            "video_mode" to listOf("video_button"),
            "gallery" to listOf("thumbnail"),
        ),
        // Xiaomi MIUI camera
        "com.android.camera" to mapOf(  // MIUI uses same package, different IDs
            "shutter" to listOf("shutter_button", "v9_shutter_button"),
            "flip_camera" to listOf("camera_switcher"),
            "mode_switch" to listOf("mode_picker"),
            "video_mode" to listOf("video_mode_button"),
            "gallery" to listOf("thumbnail_button"),
        ),
        // OPPO / Realme ColorOS camera
        "com.oppo.camera" to mapOf(
            "shutter" to listOf("shutter_button", "camera_shutter"),
            "flip_camera" to listOf("switch_camera"),
            "mode_switch" to listOf("mode_picker"),
            "video_mode" to listOf("video_mode"),
            "gallery" to listOf("preview_surface", "thumbnail"),
        ),
        "com.coloros.camera" to mapOf(
            "shutter" to listOf("shutter_button", "camera_shutter"),
            "flip_camera" to listOf("switch_camera"),
            "mode_switch" to listOf("mode_picker"),
            "video_mode" to listOf("video_mode"),
            "gallery" to listOf("thumbnail"),
        ),
        // LineageOS camera
        "org.lineageos.camera" to mapOf(
            "shutter" to listOf("shutter_button"),
            "flip_camera" to listOf("camera_switch"),
            "mode_switch" to listOf("mode_picker"),
            "video_mode" to listOf("video_button"),
            "gallery" to listOf("thumbnail"),
        ),
    )

    /**
     * Generic camera fallback — view IDs that are common across many camera
     * apps regardless of OEM. Used when the foreground package isn't in the
     * [profiles] map but looks like a camera (contains "camera" in the name).
     */
    private val genericCameraProfile: Map<String, List<String>> = mapOf(
        "shutter" to listOf("shutter_button", "shutter", "btn_shutter", "camera_shutter"),
        "flip_camera" to listOf("camera_flip", "flip_camera", "switch_camera", "front_back_switcher"),
        "mode_switch" to listOf("mode_switcher", "mode_picker", "mode_button"),
        "video_mode" to listOf("video_mode", "video_button", "mode_video"),
        "gallery" to listOf("thumbnail", "thumbnail_button", "gallery_button"),
    )

    /**
     * Get the view ID resource names for a logical action in a given package.
     *
     * @param packageName the foreground app's package name
     * @param action the logical action (e.g. "shutter", "flip_camera")
     * @return list of view ID resource names to try (without the package prefix),
     *         or empty list if no profile matches
     */
    fun viewIdsFor(packageName: String, action: String): List<String> {
        val profile = profiles[packageName]
        if (profile != null) {
            profile[action]?.let { return it }
        }
        // Generic fallback for camera-like packages
        if (packageName.contains("camera", ignoreCase = true)) {
            return genericCameraProfile[action] ?: emptyList()
        }
        return emptyList()
    }

    /**
     * Check if a package has a skill profile registered.
     */
    fun hasProfile(packageName: String): Boolean = profiles.containsKey(packageName) ||
        packageName.contains("camera", ignoreCase = true)

    /**
     * Get all known logical actions for a package (e.g. shutter, flip_camera).
     */
    fun actionsFor(packageName: String): Set<String> {
        val profile = profiles[packageName]
        if (profile != null) return profile.keys
        if (packageName.contains("camera", ignoreCase = true)) return genericCameraProfile.keys
        return emptySet()
    }
}
