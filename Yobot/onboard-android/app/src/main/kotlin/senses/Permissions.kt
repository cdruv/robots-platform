package com.vadymsidorov.yobot.senses

import android.content.Context
import android.content.pm.PackageManager

internal fun Context.hasPermission(permission: String) =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
