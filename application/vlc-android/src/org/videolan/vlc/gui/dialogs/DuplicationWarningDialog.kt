package org.videolan.vlc.gui.dialogs

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.color.MaterialColors
import org.videolan.vlc.R
import org.videolan.vlc.databinding.DialogDuplicationWarningBinding

class DuplicationWarningDialog : VLCBottomSheetDialogFragment(), View.OnClickListener {

    override fun getDefaultState(): Int = STATE_EXPANDED

    override fun needToManageOrientation(): Boolean = false

    private lateinit var binding: DialogDuplicationWarningBinding

    private var duplicatesCount: Int = 0
    private var highlightsCount: Int = 0
    private var playlistTitle: String = ""

    override fun initialFocusedView(): View {
        return if (shouldShowThreeOptions())
                    binding.addNewButton
                else
                    binding.cancelButton
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        highlightsCount = arguments?.getInt(HIGHLIGHT_KEY)!!
        duplicatesCount = arguments?.getInt(DUPLICATION_KEY)!!
        playlistTitle = arguments?.getString(TITLE_KEY)!!
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = DialogDuplicationWarningBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (shouldShowThreeOptions()) {
            binding.addAllButton.setOnClickListener(this)
            binding.addNewButton.setOnClickListener(this)
            binding.cancelButton.setOnClickListener(this)
            val pluralSecondary = R.plurals.duplication_three_options_secondary
            val secondaryMessage = if (duplicatesCount == 1)
                resources.getQuantityString(pluralSecondary, duplicatesCount,playlistTitle)
            else
                resources.getQuantityString(pluralSecondary, duplicatesCount,duplicatesCount,playlistTitle)
            setupSecondaryText(secondaryMessage)
        } else {
            binding.addNewButton.visibility = View.GONE
            binding.addAllButton.text = resources.getString(R.string.add_button)
            binding.addAllButton.setOnClickListener(this)
            binding.cancelButton.setOnClickListener(this)
            val pluralSecondary = R.plurals.duplication_two_options_secondary
            val secondaryMessage = resources.getQuantityString(pluralSecondary, duplicatesCount,playlistTitle)

            setupSecondaryText(secondaryMessage)
        }
    }

    override fun onClick(view: View) {
        val option = when (view.id) {
            R.id.add_all_button -> {
                ADD_ALL
            }
            R.id.add_new_button -> {
                ADD_NEW
            }
            R.id.cancel_button -> {
                CANCEL
            }
            else -> {
                NO_OPTION
            }
        }
        val bundle = bundleOf(OPTION_KEY to option)
        setFragmentResult(REQUEST_KEY, bundle)
        dismiss()
    }

    private fun setupSecondaryText(secondaryMessage: String) {
        val searchTitle = "\"$playlistTitle\""
        val styledText = SpannableString.valueOf(secondaryMessage)
        val startIndex = styledText.indexOf(searchTitle)
        val endIndex = startIndex + searchTitle.length
        styledText.setSpan(StyleSpan(Typeface.BOLD), startIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        styledText.setSpan(ForegroundColorSpan(MaterialColors.getColor(requireContext(), R.attr.font_default, Color.BLACK)), startIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        binding.secondaryTextview.text = styledText
    }

    private fun shouldShowThreeOptions() = duplicatesCount < highlightsCount

    companion object {

        const val REQUEST_KEY = "REQUEST_KEY"
        const val OPTION_KEY = "option"

        const val NO_OPTION = -1
        const val ADD_ALL = 0
        const val ADD_NEW = 1
        const val CANCEL = 2

        private const val HIGHLIGHT_KEY = "highlighted_items_count"
        private const val DUPLICATION_KEY = "duplicate_items_count"
        private const val TITLE_KEY = "playlist_title"

        fun newInstance(highlightedItemsCount: Int, duplicateItemsCount: Int, playlistTitle: String) : DuplicationWarningDialog {
            return DuplicationWarningDialog().apply {
                val args = Bundle()
                args.putInt(HIGHLIGHT_KEY, highlightedItemsCount)
                args.putInt(DUPLICATION_KEY, duplicateItemsCount)
                args.putString(TITLE_KEY, playlistTitle)
                arguments = args
            }
        }
    }
}
