package com.example.wdghud

/** Android 13+ notifications are optional for starting the foreground service. */
internal object DriveNotificationPermissionPolicy {
    fun shouldRequest(sdkInt: Int, granted: Boolean, alreadyRequested: Boolean): Boolean =
        sdkInt >= 33 && !granted && !alreadyRequested
}
