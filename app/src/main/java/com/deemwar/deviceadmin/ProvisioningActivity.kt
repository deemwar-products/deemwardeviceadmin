package com.deemwar.deviceadmin

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Handles the Android 12+ provisioning flow. Both actions are mandatory for a
 * DPC to be provisionable on Android 12+ (the legacy
 * ACTION_PROVISION_MANAGED_DEVICE start no longer works there).
 *
 * GET_PROVISIONING_MODE: we only ever want full device ownership, so we
 * return PROVISIONING_MODE_FULLY_MANAGED_DEVICE (1) if the wizard allows it.
 *
 * ADMIN_POLICY_COMPLIANCE: replaces PROFILE_PROVISIONING_COMPLETE; policies
 * would be applied here. We have none beyond the receiver defaults, so we
 * just acknowledge.
 */
class ProvisioningActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            "android.app.action.GET_PROVISIONING_MODE" -> {
                val allowed = intent.getIntArrayExtra("android.app.extra.PROVISIONING_ALLOWED_PROVISIONING_MODES")
                val result = Intent().putExtra(
                    "android.app.extra.PROVISIONING_MODE",
                    android.app.admin.DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
                )
                // If fully-managed isn't among the allowed modes, fail honestly rather
                // than return a mode the wizard didn't offer.
                if (allowed != null && !allowed.contains(
                        android.app.admin.DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE)) {
                    setResult(RESULT_CANCELED)
                } else {
                    setResult(RESULT_OK, result)
                }
                finish()
            }
            "android.app.action.ADMIN_POLICY_COMPLIANCE" -> {
                setResult(RESULT_OK)
                finish()
            }
            else -> finish()
        }
    }
}
