package com.threadprotection.app.scan

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build

/**
 * The real, OS-reported state of one permission for one installed app.
 *
 * Everything here comes from public `PackageManager` APIs that Android genuinely lets a
 * third-party app read about another package — no root, no reflection, no private APIs:
 *
 * - granted vs denied: `PackageInfo.requestedPermissionsFlags` masked with
 *   `REQUESTED_PERMISSION_GRANTED`, which is the canonical source (`checkPermission()` agrees but
 *   can't distinguish "denied" from "never declared").
 * - runtime vs install-time: `PermissionInfo.getProtection()`. Only `PROTECTION_DANGEROUS`
 *   permissions are user-revocable at all; a `PROTECTION_NORMAL` one is granted at install and
 *   cannot be turned off by the user, this app, or Settings.
 * - policy-restricted: `PackageManager.isPermissionRevokedByPolicy()` — a device-admin/work-profile
 *   block that the user themselves cannot lift from Settings.
 *
 * What Android deliberately does *not* expose, and this app therefore does not claim to know:
 * the one-time-grant ("Only this time") flag and the soft/hard restriction flags both live behind
 * `getPermissionFlags()`, which requires the signature-level GRANT_RUNTIME_PERMISSIONS permission.
 */
enum class PermGrantState {
    /** Dangerous permission, currently granted. The user can revoke it in system Settings. */
    GRANTED,

    /** Dangerous permission the app declares but does not currently hold. */
    DENIED,

    /** Blocked by device policy (work profile / device admin). The user cannot change this. */
    RESTRICTED,

    /** Install-time (normal) permission: granted automatically and not revocable by anyone. */
    ALWAYS_ON,

    /** The app doesn't declare this permission at all. */
    NOT_REQUESTED,

    /** The OS refused to tell us (package vanished mid-read, or an unknown permission name). */
    UNKNOWN;

    /** True only when system Settings actually offers the user a switch for this permission. */
    val userChangeable: Boolean get() = this == GRANTED || this == DENIED

    val label: String
        get() = when (this) {
            GRANTED -> "Granted"
            DENIED -> "Denied"
            RESTRICTED -> "Restricted by policy"
            ALWAYS_ON -> "Always on"
            NOT_REQUESTED -> "Not requested"
            UNKNOWN -> "Unavailable"
        }
}

object PermissionState {

    /**
     * Reads the live state of [permissionName] for the package described by [info].
     *
     * [info] must have been loaded with `GET_PERMISSIONS`, otherwise `requestedPermissions` is null
     * and everything reads as [PermGrantState.NOT_REQUESTED].
     */
    fun of(pm: PackageManager, info: PackageInfo, permissionName: String): PermGrantState {
        val requested = info.requestedPermissions ?: return PermGrantState.NOT_REQUESTED
        val index = requested.indexOf(permissionName)
        if (index < 0) return PermGrantState.NOT_REQUESTED

        val packageName = info.packageName ?: return PermGrantState.UNKNOWN

        // A policy block outranks everything else: the permission may read as granted or denied,
        // but either way the user has no way to change it, and saying "Denied" would wrongly imply
        // they could just switch it back on.
        val revokedByPolicy = runCatching { pm.isPermissionRevokedByPolicy(permissionName, packageName) }
            .getOrDefault(false)
        if (revokedByPolicy) return PermGrantState.RESTRICTED

        val granted = info.requestedPermissionsFlags
            ?.getOrNull(index)
            ?.let { it and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 }
            ?: return PermGrantState.UNKNOWN

        return when (protectionOf(pm, permissionName)) {
            PermissionInfo.PROTECTION_DANGEROUS -> if (granted) PermGrantState.GRANTED else PermGrantState.DENIED
            null -> PermGrantState.UNKNOWN
            // Normal / signature / internal permissions aren't user-facing switches. If it's held,
            // it's held permanently; if it isn't, the app simply never qualified for it.
            else -> if (granted) PermGrantState.ALWAYS_ON else PermGrantState.NOT_REQUESTED
        }
    }

    /** Base protection level, or null if the OS doesn't recognise the permission name. */
    private fun protectionOf(pm: PackageManager, permissionName: String): Int? {
        val info = runCatching { pm.getPermissionInfo(permissionName, 0) }.getOrNull() ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.protection
        } else {
            @Suppress("DEPRECATION")
            info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        }
    }
}
