package com.example.wdghud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveNotificationPermissionPolicyTest {
    @Test fun noRuntimePromptBeforeAndroid13() {
        assertFalse(DriveNotificationPermissionPolicy.shouldRequest(32, false, false))
    }

    @Test fun promptsOnceOnAndroid13WhenMissing() {
        assertTrue(DriveNotificationPermissionPolicy.shouldRequest(33, false, false))
        assertFalse(DriveNotificationPermissionPolicy.shouldRequest(33, false, true))
    }

    @Test fun doesNotPromptWhenAlreadyGranted() {
        assertFalse(DriveNotificationPermissionPolicy.shouldRequest(33, true, false))
        assertFalse(DriveNotificationPermissionPolicy.shouldRequest(37, true, false))
    }

    @Test fun modernAndroidStillPromptsWhenNeverAsked() {
        assertTrue(DriveNotificationPermissionPolicy.shouldRequest(37, false, false))
    }
}
