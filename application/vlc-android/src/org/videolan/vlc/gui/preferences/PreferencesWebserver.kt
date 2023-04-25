/*
 * *************************************************************************
 *  PreferencesWebserver.kt
 * **************************************************************************
 *  Copyright © 2018 VLC authors and VideoLAN
 *  Author: Geoffrey Métais
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *  ***************************************************************************
 */
package org.videolan.vlc.gui.preferences

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.EditTextPreference.OnBindEditTextListener
import androidx.preference.Preference
import androidx.preference.Preference.SummaryProvider
import androidx.preference.PreferenceManager
import org.videolan.resources.ACTION_RESTART_SERVER
import org.videolan.resources.util.startWebserver
import org.videolan.resources.util.stopWebserver
import org.videolan.tools.KEY_ENABLE_WEB_SERVER
import org.videolan.tools.KEY_WEB_SERVER_PASSWORD
import org.videolan.tools.password
import org.videolan.vlc.R
import org.videolan.vlc.StartActivity


class PreferencesWebserver : BasePreferenceFragment(), SharedPreferences.OnSharedPreferenceChangeListener {

    override fun getTitleId() = R.string.web_server

    override fun getXml() = R.xml.preferences_webserver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceScreen.sharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onCreatePreferences(bundle: Bundle?, s: String?) {
        super.onCreatePreferences(bundle, s)
        val preference: EditTextPreference? = findPreference(KEY_WEB_SERVER_PASSWORD)

        if (preference != null) {
            preference.summaryProvider = SummaryProvider<EditTextPreference> {
                val password: String = PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_WEB_SERVER_PASSWORD, "")!!
                if (password.isEmpty()) {
                    getString(androidx.preference.R.string.not_set)
                } else {
                    password.password()
                }
            }

            preference.setOnBindEditTextListener(
                    OnBindEditTextListener { editText ->
                        editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        preference.summaryProvider = SummaryProvider<EditTextPreference> {
                            editText.text.toString().password()
                        }
                    })
        }
    }

    override fun onPreferenceTreeClick(preference: Preference?): Boolean {
        if (preference?.key == "web_server_status") {
            requireActivity().startActivity(Intent(requireActivity(), StartActivity::class.java).apply { action = "vlc.webserver.share" })
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            KEY_ENABLE_WEB_SERVER -> {
                val serverEnabled = sharedPreferences?.getBoolean(KEY_ENABLE_WEB_SERVER, false) ?: false
                if (serverEnabled) {
                    requireActivity().startWebserver()
                } else {
                    requireActivity().stopWebserver()
                }
            }
            else -> {
                requireActivity().sendBroadcast(Intent(ACTION_RESTART_SERVER))
            }
        }
    }
}