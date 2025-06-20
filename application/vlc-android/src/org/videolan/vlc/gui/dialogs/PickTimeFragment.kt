/**
 * **************************************************************************
 * PickTimeFragment.java
 * ****************************************************************************
 * Copyright © 2015 VLC authors and VideoLAN
 * Author: Geoffrey Métais
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * ***************************************************************************
 */
package org.videolan.vlc.gui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import org.videolan.tools.setGone
import org.videolan.vlc.R
import org.videolan.vlc.databinding.DialogTimePickerBinding
import org.videolan.vlc.gui.helpers.TalkbackUtil

abstract class PickTimeFragment : PlaybackBottomSheetDialogFragment(), View.OnClickListener, View.OnFocusChangeListener {

    lateinit var binding: DialogTimePickerBinding
    private var mTextColor: Int = 0

    var hours = ""
    var minutes = ""
    var seconds = ""
    private var formatTime = ""
    private var pickedRawTime = ""
    var maxTimeSize = 6

    abstract fun showTimeOnly(): Boolean

    abstract fun getTitle(): Int

    open fun showDeleteCurrent() = false

    override fun getDefaultState(): Int {
        return STATE_EXPANDED
    }

    override fun needToManageOrientation(): Boolean {
        return true
    }

    override fun allowRemote() = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        binding = DialogTimePickerBinding.inflate(inflater, container, false)
        binding.timPicTitle.setText(getTitle())
        arrayOf(binding.timPic0, binding.timPic1, binding.timPic2, binding.timPic3, binding.timPic4, binding.timPic5, binding.timPic6, binding.timPic7, binding.timPic8, binding.timPic9, binding.timPic00, binding.timPic30, binding.timPicDelete, binding.timPicOk).forEach {
            it .setOnClickListener(this)
            it .onFocusChangeListener = this

        }

        binding.timPicDeleteCurrent.setOnClickListener(this)
        binding.timPicDeleteCurrent.visibility = if (showDeleteCurrent()) View.VISIBLE else View.GONE
        binding.timPicDeleteCurrent.onFocusChangeListener = this

        mTextColor = binding.timPicTimetojump.currentTextColor
        if (showTimeOnly()) {
            binding.timPicWaitCheckbox.setGone()
            binding.timPicResetCheckbox.setGone()
        }

        return binding.root
    }

    override fun initialFocusedView(): View {
        return binding.timPic1
    }

    override fun onFocusChange(v: View, hasFocus: Boolean) {
        if (v is TextView) {
            v.setTextColor(if (hasFocus) ContextCompat.getColor(requireActivity(), R.color.orange500) else mTextColor)
        }
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.tim_pic_1 -> updateValue("1")
            R.id.tim_pic_2 -> updateValue("2")
            R.id.tim_pic_3 -> updateValue("3")
            R.id.tim_pic_4 -> updateValue("4")
            R.id.tim_pic_5 -> updateValue("5")
            R.id.tim_pic_6 -> updateValue("6")
            R.id.tim_pic_7 -> updateValue("7")
            R.id.tim_pic_8 -> updateValue("8")
            R.id.tim_pic_9 -> updateValue("9")
            R.id.tim_pic_0 -> updateValue("0")
            R.id.tim_pic_00 -> updateValue("00")
            R.id.tim_pic_30 -> updateValue("30")
            R.id.tim_pic_delete -> deleteLastNumber()
            R.id.tim_pic_ok -> executeAction()
        }
    }

    private fun getLastNumbers(rawTime: String): String {
        if (rawTime.isEmpty())
            return ""
        return if (rawTime.length == 1)
            rawTime
        else
            rawTime.substring(rawTime.length - 2)
    }

    private fun removeLastNumbers(rawTime: String): String {
        return if (rawTime.length <= 1) "" else rawTime.substring(0, rawTime.length - 2)
    }

    private fun deleteLastNumber() {
        if (pickedRawTime !== "") {
            pickedRawTime = pickedRawTime.substring(0, pickedRawTime.length - 1)
            updateValue("")
        }
    }

    fun updateValue(value: String) {
        if (pickedRawTime.length >= maxTimeSize)
            return
        pickedRawTime += value
        var tempRawTime = pickedRawTime
        formatTime = ""

        if (maxTimeSize > 4) {
            seconds = getLastNumbers(tempRawTime)
            if (seconds !== "")
                formatTime = seconds + "s"
            tempRawTime = removeLastNumbers(tempRawTime)
        } else
            seconds = ""

        minutes = getLastNumbers(tempRawTime)
        if (minutes !== "")
            formatTime = minutes + "m " + formatTime
        tempRawTime = removeLastNumbers(tempRawTime)

        hours = getLastNumbers(tempRawTime)
        if (hours !== "")
            formatTime = hours + "h " + formatTime

        binding.timPicTimetojump.text = formatTime
        binding.timPicTimetojump.announceForAccessibility(TalkbackUtil.millisToString(requireActivity(), getTimeInMillis() ))
    }

    fun getTimeInMillis(): Long {
        val hours = if (hours != "") java.lang.Long.parseLong(hours) * HOURS_IN_MICROS else 0L
        val minutes = if (minutes != "") java.lang.Long.parseLong(minutes) * MINUTES_IN_MICROS else 0L
        val seconds = if (seconds != "") java.lang.Long.parseLong(seconds) * SECONDS_IN_MICROS else 0L
        return (hours + minutes + seconds) / 1000L
    }

    protected abstract fun executeAction()

    companion object {

        const val TAG = "VLC/PickTimeFragment"

        const val MILLIS_IN_MICROS: Long = 1000
        const val SECONDS_IN_MICROS = 1000 * MILLIS_IN_MICROS
        const val MINUTES_IN_MICROS = 60 * SECONDS_IN_MICROS
        const val HOURS_IN_MICROS = 60 * MINUTES_IN_MICROS
    }
}
