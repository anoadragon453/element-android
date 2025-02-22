/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.settings

import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import im.vector.app.R
import im.vector.app.core.extensions.registerStartForActivityResult
import im.vector.app.core.preference.VectorPreference
import im.vector.app.core.utils.toast
import im.vector.app.features.navigation.Navigator
import im.vector.app.features.notifications.NotificationDrawerManager
import im.vector.app.features.pin.PinCodeStore
import im.vector.app.features.pin.PinMode
import im.vector.app.features.pin.lockscreen.biometrics.BiometricHelper
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.orFalse
import timber.log.Timber
import javax.inject.Inject

class VectorSettingsPinFragment @Inject constructor(
        private val pinCodeStore: PinCodeStore,
        private val navigator: Navigator,
        private val notificationDrawerManager: NotificationDrawerManager,
        private val biometricHelper: BiometricHelper,
) : VectorSettingsBaseFragment() {

    override var titleRes = R.string.settings_security_application_protection_screen_title
    override val preferenceXmlRes = R.xml.vector_settings_pin

    private val usePinCodePref by lazy {
        findPreference<SwitchPreference>(VectorPreferences.SETTINGS_SECURITY_USE_PIN_CODE_FLAG)!!
    }

    private val changePinCodePref by lazy {
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_SECURITY_CHANGE_PIN_CODE_FLAG)!!
    }

    private val useCompleteNotificationPref by lazy {
        findPreference<SwitchPreference>(VectorPreferences.SETTINGS_SECURITY_USE_COMPLETE_NOTIFICATIONS_FLAG)!!
    }

    private val useBiometricPref by lazy {
        findPreference<SwitchPreference>(VectorPreferences.SETTINGS_SECURITY_USE_BIOMETRICS_FLAG)!!
    }

    private fun shouldCheckBiometricPref(isPinCodeChecked: Boolean): Boolean {
        return isPinCodeChecked && // Biometric auth depends on PIN auth
                biometricHelper.isSystemAuthEnabledAndValid &&
                biometricHelper.isSystemKeyValid
    }

    override fun onResume() {
        super.onResume()

        useBiometricPref.isEnabled = usePinCodePref.isChecked
        useBiometricPref.isChecked = shouldCheckBiometricPref(usePinCodePref.isChecked)
    }

    override fun bindPref() {
        refreshPinCodeStatus()

        usePinCodePref.setOnPreferenceChangeListener { _, value ->
            val isChecked = (value as? Boolean).orFalse()
            useBiometricPref.isEnabled = isChecked
            useBiometricPref.isChecked = shouldCheckBiometricPref(isChecked)
            if (!isChecked) {
                disableBiometricAuthentication()
            }
            true
        }

        useCompleteNotificationPref.setOnPreferenceChangeListener { _, _ ->
            // Refresh the drawer for an immediate effect of this change
            notificationDrawerManager.notificationStyleChanged()
            true
        }

        useBiometricPref.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as? Boolean == true) {
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching {
                        // If previous system key existed, delete it
                        if (biometricHelper.hasSystemKey) {
                            biometricHelper.disableAuthentication()
                        }
                        biometricHelper.enableAuthentication(requireActivity()).collect()
                    }.onFailure {
                        showEnableBiometricErrorMessage()
                    }
                    useBiometricPref.isChecked = shouldCheckBiometricPref(usePinCodePref.isChecked)
                }
                false
            } else {
                disableBiometricAuthentication()
                true
            }
        }
    }

    private fun disableBiometricAuthentication() {
        runCatching { biometricHelper.disableAuthentication() }
                .onFailure { Timber.e(it) }
    }

    private fun refreshPinCodeStatus() {
        lifecycleScope.launchWhenResumed {
            val hasPinCode = pinCodeStore.hasEncodedPin()
            usePinCodePref.isChecked = hasPinCode
            usePinCodePref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                if (hasPinCode) {
                    lifecycleScope.launch {
                        pinCodeStore.deletePinCode()
                        refreshPinCodeStatus()
                    }
                } else {
                    navigator.openPinCode(
                            requireContext(),
                            pinActivityResultLauncher,
                            PinMode.CREATE
                    )
                }
                true
            }

            changePinCodePref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                if (hasPinCode) {
                    navigator.openPinCode(
                            requireContext(),
                            pinActivityResultLauncher,
                            PinMode.MODIFY
                    )
                }
                true
            }
        }
    }

    private fun showEnableBiometricErrorMessage() {
        context?.toast(R.string.settings_security_pin_code_use_biometrics_error)
    }

    private val pinActivityResultLauncher = registerStartForActivityResult {
        refreshPinCodeStatus()
    }
}
