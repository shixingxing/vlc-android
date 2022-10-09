/**
 * **************************************************************************
 * PlaylistFragment.kt
 * ****************************************************************************
 * Copyright © 2018 VLC authors and VideoLAN
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
package org.videolan.vlc.gui

import android.content.Intent
import android.os.Bundle
import android.view.*
import androidx.appcompat.view.ActionMode
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.paging.PagedList
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.resources.CTX_PLAY_ALL
import org.videolan.tools.Settings
import org.videolan.tools.dp
import org.videolan.tools.putSingle
import org.videolan.vlc.R
import org.videolan.vlc.databinding.PlaylistsFragmentBinding
import org.videolan.vlc.gui.audio.AudioBrowserAdapter
import org.videolan.vlc.gui.audio.AudioBrowserFragment
import org.videolan.vlc.gui.audio.BaseAudioBrowser
import org.videolan.vlc.gui.helpers.INavigator
import org.videolan.vlc.gui.view.EmptyLoadingState
import org.videolan.vlc.gui.view.FastScroller
import org.videolan.vlc.gui.view.RecyclerSectionItemDecoration
import org.videolan.vlc.gui.view.RecyclerSectionItemGridDecoration
import org.videolan.vlc.media.MediaUtils
import org.videolan.vlc.providers.medialibrary.MedialibraryProvider
import org.videolan.vlc.reloadLibrary
import org.videolan.vlc.util.getScreenWidth
import org.videolan.vlc.util.onAnyChange
import org.videolan.vlc.viewmodels.mobile.PlaylistsViewModel
import org.videolan.vlc.viewmodels.mobile.getViewModel
import kotlin.math.min

class PlaylistFragment : BaseAudioBrowser<PlaylistsViewModel>(), SwipeRefreshLayout.OnRefreshListener {

    private lateinit var binding: PlaylistsFragmentBinding
    private lateinit var playlists: RecyclerView
    private lateinit var playlistAdapter: AudioBrowserAdapter
    private lateinit var fastScroller: FastScroller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = getViewModel()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = PlaylistsFragmentBinding.inflate(inflater, container, false)
        playlists = binding.swipeLayout.findViewById(R.id.audio_list)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.swipeLayout.setOnRefreshListener(this)


        //size of an item
        val spacing = resources.getDimension(R.dimen.kl_half).toInt()

        val dimension = resources.getDimension(R.dimen.default_content_width)
        val totalWidth = if (dimension > 0) min(requireActivity().getScreenWidth(), dimension.toInt()) else requireActivity().getScreenWidth()

        val itemSize = RecyclerSectionItemGridDecoration.getItemSize(totalWidth - spacing * 2, nbColumns, spacing, 16.dp)

        playlistAdapter = AudioBrowserAdapter(MediaLibraryItem.TYPE_PLAYLIST, this, cardSize = itemSize)
        playlistAdapter.onAnyChange { updateEmptyView() }
        adapter = playlistAdapter

        setupLayoutManager()

        playlists.adapter = playlistAdapter
        fastScroller = view.rootView.findViewById(R.id.songs_fast_scroller_playlist) as FastScroller
        fastScroller.attachToCoordinator(view.rootView.findViewById(R.id.appbar) as AppBarLayout, view.rootView.findViewById(R.id.coordinator) as CoordinatorLayout, view.rootView.findViewById(R.id.fab) as FloatingActionButton)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        viewModel.provider.pagedList.observe(requireActivity()) {
            playlistAdapter.submitList(it as PagedList<MediaLibraryItem>)
            updateEmptyView()
        }
        viewModel.provider.loading.observe(requireActivity()) { loading ->
            setRefreshing(loading) { }
        }

        viewModel.provider.liveHeaders.observe(requireActivity()) {
            playlists.invalidateItemDecorations()
        }

        fastScroller.setRecyclerView(getCurrentRV(), viewModel.provider)

    }

    private fun updateEmptyView() {
        if (!isAdded) return
        swipeRefreshLayout.visibility = if (Medialibrary.getInstance().isInitiated) View.VISIBLE else View.GONE
        binding.emptyLoading.emptyText = viewModel.filterQuery?.let {  getString(R.string.empty_search, it) } ?: getString(R.string.nomedia)
        binding.emptyLoading.state =
                when {
                    viewModel.provider.loading.value == true && empty -> EmptyLoadingState.LOADING
                    empty && viewModel.filterQuery != null -> EmptyLoadingState.EMPTY_SEARCH
                    empty -> EmptyLoadingState.EMPTY
                    else -> EmptyLoadingState.NONE
                }
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        adapter?.itemCount?.let { getMultiHelper()?.toggleActionMode(true, it)}
        mode.menuInflater.inflate(R.menu.action_mode_audio_browser, menu)
        menu.findItem(R.id.action_mode_audio_add_playlist).isVisible = false
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.ml_menu_display_grid).isVisible = !viewModel.providerInCard
        menu.findItem(R.id.ml_menu_display_list).isVisible = viewModel.providerInCard
        super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.ml_menu_display_list, R.id.ml_menu_display_grid -> {
                viewModel.providerInCard = item.itemId == R.id.ml_menu_display_grid
                setupLayoutManager()
                playlists.adapter = adapter
                activity?.invalidateOptionsMenu()
                Settings.getInstance(requireActivity()).putSingle(viewModel.displayModeKey, item.itemId == R.id.ml_menu_display_grid)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupLayoutManager() {
        val spacing = resources.getDimension(R.dimen.kl_half).toInt()

        if (playlists.itemDecorationCount > 0) {
            playlists.removeItemDecorationAt(0)
        }
        when (viewModel.providerInCard) {
            true -> {
                val screenWidth = (requireActivity() as? INavigator)?.getFragmentWidth(requireActivity()) ?: requireActivity().getScreenWidth()
                adapter?.cardSize = RecyclerSectionItemGridDecoration.getItemSize(screenWidth, nbColumns, spacing, 16.dp)
                adapter?.let { adapter -> displayListInGrid(playlists, adapter, viewModel.provider as MedialibraryProvider<MediaLibraryItem>, spacing) }
            }
            else -> {
                adapter?.cardSize = -1
                playlists.addItemDecoration(
                    RecyclerSectionItemDecoration(
                        resources.getDimensionPixelSize(R.dimen.recycler_section_header_height),
                        true,
                        viewModel.provider
                    )
                )
                playlists.layoutManager = LinearLayoutManager(activity)
            }
        }

        val lp = playlists.layoutParams
        val dimension = requireActivity().resources.getDimension(R.dimen.default_content_width)
        lp.width = if (viewModel.providerInCard) ViewGroup.LayoutParams.MATCH_PARENT else {
            dimension.toInt()
        }
        (playlists.parent as View).setBackgroundColor(if (!viewModel.providerInCard && dimension != -1F) backgroundColor else ContextCompat.getColor(requireContext(), R.color.transparent))
        playlists.setBackgroundColor(if (!viewModel.providerInCard && dimension != -1F) listColor else ContextCompat.getColor(requireContext(), R.color.transparent))
    }

    override fun onClick(v: View, position: Int, item: MediaLibraryItem) {
        if (actionMode == null) {
            val i = Intent(activity, HeaderMediaListActivity::class.java)
            i.putExtra(AudioBrowserFragment.TAG_ITEM, item)
            startActivity(i)
        } else super.onClick(v, position, item)
    }

    override fun onCtxAction(position: Int, option: Long) {
        if (option == CTX_PLAY_ALL) MediaUtils.playAll(activity, viewModel.provider as MedialibraryProvider<MediaWrapper>, position, false)
        else super.onCtxAction(position, option)
    }

    override fun onRefresh() {
        activity?.reloadLibrary()
    }

    override fun getTitle(): String = getString(R.string.playlists)

    override fun getCurrentRV(): RecyclerView = playlists

    override fun hasFAB() = false
}
