package io.github.labushuya.emberlight.core.media.permission

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Prüft, ob eine Berechtigung gewährt ist. Im Repository austauschbar, damit Tests ohne Gerät laufen. */
fun interface MediaPermissionChecker {
    fun isGranted(permission: String): Boolean
}

/** Dünner Android-Adapter über `ContextCompat.checkSelfPermission`. */
internal class ContextMediaPermissionChecker
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : MediaPermissionChecker {
        override fun isGranted(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
