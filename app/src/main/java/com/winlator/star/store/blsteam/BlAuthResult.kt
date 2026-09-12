package com.winlator.star.store.blsteam

/**
 * Result of a BlSteamSession login attempt. Constructor parameter order
 * matches the JNI ctor signature in jni.rs's `build_auth_result`.
 * Do NOT reorder the primary-constructor parameters without updating that
 * file's `auth_result_ctor` signature lookup.
 */
data class BlAuthResult(
    val success: Boolean,
    val errorCode: Int,
    val errorMessage: String,
    val accountName: String,
    val refreshToken: String,
    val accessToken: String,
    val newGuardData: String,
    val steamId: Long,
    val hadRemoteInteraction: Boolean,
    val agreementSessionUrl: String,
)
