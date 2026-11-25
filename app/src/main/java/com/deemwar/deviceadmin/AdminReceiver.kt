package com.deemwar.deviceadmin

import android.app.admin.DeviceAdminReceiver

/**
 * Minimal device admin receiver. Exists only so `dpm set-device-owner` has a
 * receiver to promote. Holds no extra policies beyond force-lock.
 */
class AdminReceiver : DeviceAdminReceiver()
