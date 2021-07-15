/*
 * ************************************************************************
 *  MediaSessionBrowser.kt
 * *************************************************************************
 *  Copyright © 2016-2020 VLC authors and VideoLAN
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
 *
 *  *************************************************************************
 */
package org.videolan.vlc.media

import android.content.*
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import androidx.annotation.WorkerThread
import androidx.core.net.toUri
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.resources.*
import org.videolan.resources.AppContextProvider.appContext
import org.videolan.tools.*
import org.videolan.vlc.ArtworkProvider
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.R
import org.videolan.vlc.extensions.ExtensionManagerService
import org.videolan.vlc.extensions.ExtensionManagerService.ExtensionManagerActivity
import org.videolan.vlc.extensions.ExtensionsManager
import org.videolan.vlc.extensions.api.VLCExtensionItem
import org.videolan.vlc.gui.helpers.MediaComparators
import org.videolan.vlc.gui.helpers.MediaComparators.formatArticles
import org.videolan.vlc.gui.helpers.UiTools.getDefaultAudioDrawable
import org.videolan.vlc.gui.helpers.getBitmapFromDrawable
import org.videolan.vlc.isPathValid
import org.videolan.vlc.media.MediaUtils.getMediaAlbum
import org.videolan.vlc.media.MediaUtils.getMediaArtist
import org.videolan.vlc.media.MediaUtils.getMediaDescription
import org.videolan.vlc.media.MediaUtils.getMediaSubtitle
import org.videolan.vlc.util.ThumbnailsProvider
import org.videolan.vlc.util.isSchemeStreaming
import java.util.*
import java.util.concurrent.Semaphore

/**
 * The mediaId used in the media session browser is defined as an opaque string token which is left
 * up to the application developer to define. In practicality, mediaIds from multiple applications
 * may be combined into a single data structure, so we use a valid uri, and have have intentionally
 * prefixed it with a namespace. The value is stored as a string to avoid repeated type conversion;
 * however, it may be parsed by the uri class as needed. The uri starts with two forward slashes to
 * disambiguate the authority from the path, per RFC 3986, section 3.
 *
 * The mediaId structure is documented below for reference. The first (or second) letter of each
 * section is used in lieu of the entire word in order to shorten the id throughout the library.
 * The reduction of space consumed by the mediaId enables an increased number of records per page.
 *
 * Root node
 * //org.videolan.vlc/{r}oot
 * Root menu
 * //org.videolan.vlc/{r}oot/home
 * //org.videolan.vlc/{r}oot/playlist/<id>
 * //org.videolan.vlc/{r}oot/{l}ib
 * //org.videolan.vlc/{r}oot/stream
 * Home menu
 * //org.videolan.vlc/{r}oot/home/shuffle_all
 * //org.videolan.vlc/{r}oot/home/last_added[?{i}ndex=<track num>]
 * //org.videolan.vlc/{r}oot/home/history[?{i}ndex=<track num>]
 * Library menu
 * //org.videolan.vlc/{r}oot/{l}ib/a{r}tist[?{p}age=<page num>]
 * //org.videolan.vlc/{r}oot/{l}ib/a{r}tist/<id>
 * //org.videolan.vlc/{r}oot/{l}ib/a{l}bum[?{p}age=<page num>]
 * //org.videolan.vlc/{r}oot/{l}ib/a{l}bum/<id>
 * //org.videolan.vlc/{r}oot/{l}ib/{t}rack[?{p}age=<page num>]
 * //org.videolan.vlc/{r}oot/{l}ib/{t}rack[?{p}age=<page num>][&{i}ndex=<track num>]
 * //org.videolan.vlc/{r}oot/{l}ib/{g}enre[?{p}age=<page num>]
 * //org.videolan.vlc/{r}oot/{l}ib/{g}enre/<id>
 * Media
 * //org.videolan.vlc/media/<id>
 * Errors
 * //org.videolan.vlc/error/media
 * //org.videolan.vlc/error/playlist
 * Search
 * //org.videolan.vlc/search?query=<query>
 */
class MediaSessionBrowser : ExtensionManagerActivity {
    override fun displayExtensionItems(extensionId: Int, title: String, items: List<VLCExtensionItem>, showParams: Boolean, isRefresh: Boolean) {
        if (showParams && items.size == 1 && items[0].getType() == VLCExtensionItem.TYPE_DIRECTORY) {
            extensionManagerService?.browse(items[0].stringId)
            return
        }
        var mediaItem: MediaDescriptionCompat.Builder
        var extensionItem: VLCExtensionItem
        for ((i, extensionItem) in items.withIndex()) {
            if (extensionItem.getType() != VLCExtensionItem.TYPE_AUDIO && extensionItem.getType() != VLCExtensionItem.TYPE_DIRECTORY) continue
            mediaItem = MediaDescriptionCompat.Builder()
            val coverUri = extensionItem.getImageUri()
            if (coverUri == null) mediaItem.setIconBitmap(getDefaultAudioDrawable(appContext).bitmap)
            else mediaItem.setIconUri(coverUri)
            mediaItem.setTitle(extensionItem.getTitle())
            mediaItem.setSubtitle(extensionItem.getSubTitle())
            val playable = extensionItem.getType() == VLCExtensionItem.TYPE_AUDIO
            if (playable) {
                mediaItem.setMediaId("${ExtensionsManager.EXTENSION_PREFIX}_${extensionId}_${extensionItem.getLink()}")
                extensionItems.add(MediaBrowserCompat.MediaItem(mediaItem.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
            } else {
                mediaItem.setMediaId("${ExtensionsManager.EXTENSION_PREFIX}_${extensionId}_${extensionItem.stringId}")
                extensionItems.add(MediaBrowserCompat.MediaItem(mediaItem.build(), MediaBrowserCompat.MediaItem.FLAG_BROWSABLE))
            }
            if (i == MAX_EXTENSION_SIZE - 1) break
        }
        extensionLock.release()
    }

    companion object {
        private const val TAG = "VLC/MediaSessionBrowser"
        private const val BASE_DRAWABLE_URI = "android.resource://${BuildConfig.APP_ID}/drawable"
        private val MENU_AUDIO_ICON = "${BASE_DRAWABLE_URI}/${R.drawable.ic_auto_audio}".toUri()
        private val MENU_ALBUM_ICON = "${BASE_DRAWABLE_URI}/${R.drawable.ic_auto_album}".toUri()
        private val MENU_GENRE_ICON = "${BASE_DRAWABLE_URI}/${R.drawable.ic_auto_genre}".toUri()
        private val MENU_ARTIST_ICON = "${BASE_DRAWABLE_URI}/${R.drawable.ic_auto_artist}".toUri()
        private val DEFAULT_ALBUM_ICON = "${BASE_DRAWABLE_URI}/${R.drawable.ic_auto_album_unknown}".toUri()
        private val DEFAULT_ARTIST_ICON = "${BASE_DRAWABLE_URI}/${R.drawable.ic_auto_artist_unknown}".toUri()
        private val DEFAULT_STREAM_ICON = "${BASE_DRAWABLE_URI}/${R.drawable.ic_auto_stream_unknown}".toUri()
        private val DEFAULT_PLAYLIST_ICON = "${BASE_DRAWABLE_URI}/${R.drawable.ic_auto_playlist_unknown}".toUri()
        private val DEFAULT_PLAYALL_ICON = "${BASE_DRAWABLE_URI}/${R.drawable.ic_auto_playall}".toUri()
        val DEFAULT_TRACK_ICON = "${BASE_DRAWABLE_URI}/${R.drawable.ic_auto_nothumb}".toUri()
        private val instance = MediaSessionBrowser()

        // Root item
        // MediaIds are all strings. Maintain in uri parsable format.
        const val ID_ROOT = "//${BuildConfig.APP_ID}/r"
        const val ID_MEDIA = "$ID_ROOT/media"
        const val ID_SEARCH = "$ID_ROOT/search"
        const val ID_NO_MEDIA = "$ID_ROOT/error/media"
        const val ID_NO_PLAYLIST = "$ID_ROOT/error/playlist"

        // Top-level menu
        private const val ID_HOME = "$ID_ROOT/home"
        const val ID_PLAYLIST = "$ID_ROOT/playlist"
        private const val ID_LIBRARY = "$ID_ROOT/l"
        const val ID_STREAM = "$ID_ROOT/stream"

        // Home menu
        const val ID_SHUFFLE_ALL = "$ID_HOME/shuffle_all"
        const val ID_LAST_ADDED = "$ID_HOME/last_added"
        const val ID_HISTORY = "$ID_HOME/history"

        // Library menu
        const val ID_ARTIST = "$ID_LIBRARY/r"
        const val ID_ALBUM = "$ID_LIBRARY/l"
        const val ID_TRACK = "$ID_LIBRARY/t"
        const val ID_GENRE = "$ID_LIBRARY/g"
        const val MAX_HISTORY_SIZE = 100
        const val MAX_COVER_ART_ITEMS = 50
        private const val MAX_EXTENSION_SIZE = 100
        const val MAX_RESULT_SIZE = 800

        // Extensions management
        private var extensionServiceConnection: ServiceConnection? = null
        private var extensionManagerService: ExtensionManagerService? = null
        private val extensionItems: ArrayList<MediaBrowserCompat.MediaItem> = ArrayList()
        private val extensionLock = Semaphore(0)

        @WorkerThread
        fun browse(context: Context, parentId: String): List<MediaBrowserCompat.MediaItem>? {
            var results: ArrayList<MediaBrowserCompat.MediaItem> = ArrayList()
            var list: Array<out MediaLibraryItem>? = null
            var limitSize = false
            val res = context.resources
            //Extensions
            if (parentId.startsWith(ExtensionsManager.EXTENSION_PREFIX)) {
                if (extensionServiceConnection == null) {
                    createExtensionServiceConnection(context)
                    try {
                        extensionLock.acquire()
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                }
                if (extensionServiceConnection == null) return null
                val data = parentId.split("_").toTypedArray()
                val index = Integer.valueOf(data[1])
                extensionItems.clear()
                if (data.size == 2) {
                    //case extension root
                    extensionManagerService?.connectService(index)
                } else {
                    //case sub-directory
                    val stringId = parentId.replace("${ExtensionsManager.EXTENSION_PREFIX}_${index}_", "")
                    extensionManagerService?.browse(stringId)
                }
                try {
                    extensionLock.acquire()
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
                results = extensionItems
            } else {
                val ml = Medialibrary.getInstance()
                val parentIdUri = parentId.toUri()
                val page = parentIdUri.getQueryParameter("p")
                val pageOffset = page?.toInt()?.times(MAX_RESULT_SIZE) ?: 0
                when (parentIdUri.removeQuery().toString()) {
                    ID_ROOT -> {
                        //List of Extensions
                        val extensions = ExtensionsManager.getInstance().getExtensions(context, true)
                        for ((i, extension) in extensions.withIndex()) {
                            val item = MediaDescriptionCompat.Builder()
                            if (extension.androidAutoEnabled()
                                    && Settings.getInstance(context).getBoolean(ExtensionsManager.EXTENSION_PREFIX + "_" + extension.componentName().packageName + "_" + ExtensionsManager.ANDROID_AUTO_SUFFIX, false)) {
                                item.setMediaId(ExtensionsManager.EXTENSION_PREFIX + "_" + i)
                                        .setTitle(extension.title())
                                val iconRes = extension.menuIcon()
                                var b: Bitmap? = null
                                var extensionRes: Resources?
                                if (iconRes != 0) {
                                    try {
                                        extensionRes = context.packageManager
                                                .getResourcesForApplication(extension.componentName().packageName)
                                        b = BitmapFactory.decodeResource(extensionRes, iconRes)
                                    } catch (ignored: PackageManager.NameNotFoundException) {
                                    }
                                }
                                if (b != null) item.setIconBitmap(b) else try {
                                    b = (context.packageManager.getApplicationIcon(extension.componentName().packageName) as BitmapDrawable).bitmap
                                    item.setIconBitmap(b)
                                } catch (e: PackageManager.NameNotFoundException) {
                                    b = context.getBitmapFromDrawable(R.drawable.icon)
                                    item.setIconBitmap(b)
                                }
                                results.add(MediaBrowserCompat.MediaItem(item.build(), MediaBrowserCompat.MediaItem.FLAG_BROWSABLE))
                            }
                        }
                        //Home
                        val homeMediaDesc = MediaDescriptionCompat.Builder()
                                .setMediaId(ID_HOME)
                                .setTitle(res.getString(R.string.auto_home))
                                .setIconUri("${BASE_DRAWABLE_URI}/${R.drawable.ic_auto_home}".toUri())
                                .setExtras(getContentStyle(CONTENT_STYLE_GRID_ITEM_HINT_VALUE, CONTENT_STYLE_GRID_ITEM_HINT_VALUE))
                                .build()
                        results.add(MediaBrowserCompat.MediaItem(homeMediaDesc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE))
                        //Playlists
                        val playlistMediaDesc = MediaDescriptionCompat.Builder()
                                .setMediaId(ID_PLAYLIST)
                                .setTitle(res.getString(R.string.playlists))
                                .setIconUri("${BASE_DRAWABLE_URI}/${R.drawable.ic_auto_playlist}".toUri())
                                .setExtras(getContentStyle(CONTENT_STYLE_GRID_ITEM_HINT_VALUE, CONTENT_STYLE_GRID_ITEM_HINT_VALUE))
                                .build()
                        results.add(MediaBrowserCompat.MediaItem(playlistMediaDesc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE))
                        //My library
                        val libraryMediaDesc = MediaDescriptionCompat.Builder()
                                .setMediaId(ID_LIBRARY)
                                .setTitle(res.getString(R.string.auto_my_library))
                                .setIconUri(MENU_AUDIO_ICON)
                                .build()
                        results.add(MediaBrowserCompat.MediaItem(libraryMediaDesc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE))
                        //Streams
                        val streamsMediaDesc = MediaDescriptionCompat.Builder()
                                .setMediaId(ID_STREAM)
                                .setTitle(res.getString(R.string.streams))
                                .setIconUri("${BASE_DRAWABLE_URI}/${R.drawable.ic_auto_stream}".toUri())
                                .build()
                        results.add(MediaBrowserCompat.MediaItem(streamsMediaDesc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE))
                        return results
                    }
                    ID_HOME -> {
                        /* Shuffle All */
                        val audioCount = ml.audioCount
                        val shuffleAllPath = if (audioCount > 0) {
                            Uri.Builder()
                                    .appendPath(ArtworkProvider.SHUFFLE_ALL)
                                    .appendPath(ArtworkProvider.computeExpiration())
                                    .appendPath("$audioCount")
                                    .build()
                        } else null
                        val shuffleAllMediaDesc = getPlayAllBuilder(res, ID_SHUFFLE_ALL, audioCount, shuffleAllPath)
                                .setTitle(res.getString(R.string.shuffle_all_title))
                                .build()
                        results.add(MediaBrowserCompat.MediaItem(shuffleAllMediaDesc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
                        /* Last Added */
                        val recentAudio = ml.getPagedAudio(Medialibrary.SORT_INSERTIONDATE, true, false, MAX_HISTORY_SIZE, 0)
                        val recentAudioSize = recentAudio.size
                        val lastAddedPath = if (recentAudioSize > 0) {
                            Uri.Builder()
                                    .appendPath(ArtworkProvider.LAST_ADDED)
                                    .appendPath("${ArtworkProvider.computeChecksum(recentAudio.toList())}")
                                    .appendPath("$recentAudioSize")
                                    .build()
                        } else null
                        val lastAddedMediaDesc = getPlayAllBuilder(res, ID_LAST_ADDED, recentAudioSize, lastAddedPath)
                                .setTitle(res.getString(R.string.auto_last_added_media))
                                .build()
                        results.add(MediaBrowserCompat.MediaItem(lastAddedMediaDesc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE))
                        /* History */
                        if (Settings.getInstance(context).getBoolean(PLAYBACK_HISTORY, true)) {
                            val lastMediaPlayed = ml.lastMediaPlayed()?.toList()?.filter { isMediaAudio(it) }
                            if (!lastMediaPlayed.isNullOrEmpty()) {
                                val lastMediaSize = lastMediaPlayed.size.coerceAtMost(MAX_HISTORY_SIZE)
                                val historyPath = Uri.Builder()
                                        .appendPath(ArtworkProvider.HISTORY)
                                        .appendPath("${ArtworkProvider.computeChecksum(lastMediaPlayed)}")
                                        .appendPath("$lastMediaSize")
                                        .build()
                                val historyMediaDesc = getPlayAllBuilder(res, ID_HISTORY, lastMediaSize, historyPath)
                                        .setTitle(res.getString(R.string.history))
                                        .build()
                                results.add(MediaBrowserCompat.MediaItem(historyMediaDesc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE))
                            }
                        }
                        return results
                    }
                    ID_LIBRARY -> {
                        //Artists
                        val artistsMediaDesc = MediaDescriptionCompat.Builder()
                                .setMediaId(ID_ARTIST)
                                .setTitle(res.getString(R.string.artists))
                                .setIconUri(MENU_ARTIST_ICON)
                                .build()
                        results.add(MediaBrowserCompat.MediaItem(artistsMediaDesc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE))
                        //Albums
                        val albumsMediaDesc = MediaDescriptionCompat.Builder()
                                .setMediaId(ID_ALBUM)
                                .setTitle(res.getString(R.string.albums))
                                .setIconUri(MENU_ALBUM_ICON)
                                .setExtras(if (ml.albumsCount <= MAX_RESULT_SIZE) getContentStyle(CONTENT_STYLE_GRID_ITEM_HINT_VALUE, CONTENT_STYLE_LIST_ITEM_HINT_VALUE) else null)
                                .build()
                        results.add(MediaBrowserCompat.MediaItem(albumsMediaDesc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE))
                        //Tracks
                        val tracksMediaDesc = MediaDescriptionCompat.Builder()
                                .setMediaId(ID_TRACK)
                                .setTitle(res.getString(R.string.tracks))
                                .setIconUri(MENU_AUDIO_ICON)
                                .build()
                        results.add(MediaBrowserCompat.MediaItem(tracksMediaDesc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE))
                        //Genres
                        val genresMediaDesc = MediaDescriptionCompat.Builder()
                                .setMediaId(ID_GENRE)
                                .setTitle(res.getString(R.string.genres))
                                .setIconUri(MENU_GENRE_ICON)
                                .build()
                        results.add(MediaBrowserCompat.MediaItem(genresMediaDesc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE))
                        return results
                    }
                    ID_ARTIST -> {
                        val artistsShowAll = Settings.getInstance(context).getBoolean(KEY_ARTISTS_SHOW_ALL, false)
                        val artists = ml.getArtists(artistsShowAll, Medialibrary.SORT_ALPHA, false, false)
                        artists.sortWith(MediaComparators.ANDROID_AUTO)
                        if (page == null && artists.size > MAX_RESULT_SIZE) return paginateLibrary(artists, parentIdUri, MENU_ARTIST_ICON)
                        list = artists.copyOfRange(pageOffset.coerceAtMost(artists.size), (pageOffset + MAX_RESULT_SIZE).coerceAtMost(artists.size))
                    }
                    ID_ALBUM -> {
                        val albums = ml.getAlbums(Medialibrary.SORT_ALPHA, false, false)
                        albums.sortWith(MediaComparators.ANDROID_AUTO)
                        if (page == null && albums.size > MAX_RESULT_SIZE) return paginateLibrary(albums, parentIdUri, MENU_ALBUM_ICON,
                                    getContentStyle(CONTENT_STYLE_GRID_ITEM_HINT_VALUE, CONTENT_STYLE_LIST_ITEM_HINT_VALUE))
                        list = albums.copyOfRange(pageOffset.coerceAtMost(albums.size), (pageOffset + MAX_RESULT_SIZE).coerceAtMost(albums.size))
                    }
                    ID_TRACK -> {
                        val tracks = ml.getAudio(Medialibrary.SORT_ALPHA, false, false)
                        tracks.sortWith(MediaComparators.ANDROID_AUTO)
                        if (page == null && tracks.size > MAX_RESULT_SIZE) return paginateLibrary(tracks, parentIdUri, MENU_AUDIO_ICON)
                        list = tracks.copyOfRange(pageOffset.coerceAtMost(tracks.size), (pageOffset + MAX_RESULT_SIZE).coerceAtMost(tracks.size))
                    }
                    ID_GENRE -> {
                        val genres = ml.getGenres(Medialibrary.SORT_ALPHA, false, false)
                        genres.sortWith(MediaComparators.ANDROID_AUTO)
                        if (page == null && genres.size > MAX_RESULT_SIZE) return paginateLibrary(genres, parentIdUri, MENU_GENRE_ICON)
                        list = genres.copyOfRange(pageOffset.coerceAtMost(genres.size), (pageOffset + MAX_RESULT_SIZE).coerceAtMost(genres.size))
                    }
                    ID_PLAYLIST -> {
                        list = ml.playlists
                        list.sortWith(MediaComparators.ANDROID_AUTO)
                    }
                    ID_STREAM -> {
                        list = ml.lastStreamsPlayed()
                        list.sortWith(MediaComparators.ANDROID_AUTO)
                    }
                    ID_LAST_ADDED -> {
                        limitSize = true
                        list = ml.getPagedAudio(Medialibrary.SORT_INSERTIONDATE, true, false, MAX_HISTORY_SIZE, 0)
                    }
                    ID_HISTORY -> {
                        limitSize = true
                        list = ml.lastMediaPlayed()?.toList()?.filter { isMediaAudio(it) }?.toTypedArray()
                    }
                    else -> {
                        val id = ContentUris.parseId(parentIdUri)
                        when (parentIdUri.retrieveParent().toString()) {
                            ID_ALBUM -> list = ml.getAlbum(id).tracks
                            ID_ARTIST -> {
                                val artist = ml.getArtist(id)
                                list = artist.albums
                                if (list != null && list.size > 1) {
                                    val hasArtwork = list.any { !it.artworkMrl.isNullOrEmpty() && isPathValid(it.artworkMrl) }
                                    val playAllPath = if (hasArtwork) {
                                        Uri.Builder()
                                                .appendPath(ArtworkProvider.PLAY_ALL)
                                                .appendPath(ArtworkProvider.ARTIST)
                                                .appendPath("${artist.tracksCount}")
                                                .appendPath("$id")
                                                .build()
                                    } else null
                                    val playAllMediaDesc = getPlayAllBuilder(res, parentId, artist.tracksCount, playAllPath).build()
                                    results.add(MediaBrowserCompat.MediaItem(playAllMediaDesc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
                                }
                            }
                            ID_GENRE -> {
                                val genre = ml.getGenre(id)
                                list = genre.albums
                                val tracksCount = list.sumOf { it.tracksCount }
                                if (list != null && list.size > 1) {
                                    val playAllPath = Uri.Builder()
                                            .appendPath(ArtworkProvider.PLAY_ALL)
                                            .appendPath(ArtworkProvider.GENRE)
                                            .appendPath("$tracksCount")
                                            .appendPath("$id")
                                            .build()
                                    val playAllMediaDesc = getPlayAllBuilder(res, parentId, tracksCount, playAllPath).build()
                                    results.add(MediaBrowserCompat.MediaItem(playAllMediaDesc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
                                }
                            }
                        }
                    }
                }
            }
            results.addAll(buildMediaItems(context, parentId, list, null, limitSize))
            if (results.isEmpty()) {
                val emptyMediaDesc = MediaDescriptionCompat.Builder()
                        .setMediaId(ID_NO_MEDIA)
                        .setIconUri(DEFAULT_TRACK_ICON)
                        .setTitle(context.getString(R.string.search_no_result))
                when (parentId) {
                    ID_ARTIST -> emptyMediaDesc.setIconUri(DEFAULT_ARTIST_ICON)
                    ID_ALBUM -> emptyMediaDesc.setIconUri(DEFAULT_ALBUM_ICON)
                    ID_GENRE -> emptyMediaDesc.setIconUri(null)
                    ID_PLAYLIST -> {
                        emptyMediaDesc.setMediaId(ID_NO_PLAYLIST)
                        emptyMediaDesc.setTitle(context.getString(R.string.noplaylist))
                    }
                    ID_STREAM -> emptyMediaDesc.setIconUri(DEFAULT_STREAM_ICON)
                }
                results.add(MediaBrowserCompat.MediaItem(emptyMediaDesc.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
            }
            return results
        }

        /**
         * The search method is passed a simple query string absent metadata indicating
         * the user's intent to load a playlist, album, artist, or song. This is slightly different
         * than PlaybackService.onPlayFromSearch (which is also invoked by voice search) and allows
         * the user to navigate to other content via on-screen menus.
         */
        @WorkerThread
        fun search(context: Context, query: String): List<MediaBrowserCompat.MediaItem> {
            val res = context.resources
            val results: MutableList<MediaBrowserCompat.MediaItem> = ArrayList()
            val searchAggregate = Medialibrary.getInstance().search(query, false)
            val searchMediaId = ID_SEARCH.toUri().buildUpon().appendQueryParameter("query", query).toString()
            results.addAll(buildMediaItems(context, ID_PLAYLIST, searchAggregate.playlists, res.getString(R.string.playlists)))
            results.addAll(buildMediaItems(context, ID_ARTIST, searchAggregate.artists, res.getString(R.string.artists)))
            results.addAll(buildMediaItems(context, ID_ALBUM, searchAggregate.albums, res.getString(R.string.albums)))
            results.addAll(buildMediaItems(context, searchMediaId, searchAggregate.tracks, res.getString(R.string.tracks)))
            if (results.isEmpty()) {
                val emptyMediaDesc = MediaDescriptionCompat.Builder()
                        .setMediaId(ID_NO_MEDIA)
                        .setIconUri(DEFAULT_TRACK_ICON)
                        .setTitle(context.getString(R.string.search_no_result))
                        .build()
                results.add(MediaBrowserCompat.MediaItem(emptyMediaDesc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
            }
            return results
        }

        /**
         * This function constructs a collection of MediaBrowserCompat.MediaItems for each applicable
         * array element in the MediaLibraryItems list passed from either the browse or search methods.
         *
         * @param context Application context to resolve string resources
         * @param parentId Identifies the position in the menu hierarchy. The browse function
         * will pass the argument from the calling application. The search function will use a
         * placeholder value to act as if the user navigated to the location.
         * @param list MediaLibraryItems to process into MediaBrowserCompat.MediaItems
         * @param groupTitle Common heading to group items (unused if null)
         * @param limitSize Limit the number of items returned (default is false)
         * @return List containing fully constructed MediaBrowser MediaItem
         */
        private fun buildMediaItems(context: Context, parentId: String, list: Array<out MediaLibraryItem>?, groupTitle: String?, limitSize: Boolean = false): List<MediaBrowserCompat.MediaItem> {
            if (list.isNullOrEmpty()) return emptyList()
            val res = context.resources
            val artworkToUriCache = HashMap<String, Uri>()
            val results: ArrayList<MediaBrowserCompat.MediaItem> = ArrayList()
            results.ensureCapacity(list.size.coerceAtMost(MAX_RESULT_SIZE))
            /* Iterate over list */
            val parentIdUri = parentId.toUri()
            for ((index, libraryItem) in list.withIndex()) {
                if (libraryItem.itemType == MediaLibraryItem.TYPE_MEDIA
                        && ((libraryItem as MediaWrapper).type == MediaWrapper.TYPE_STREAM || isSchemeStreaming(libraryItem.uri.scheme))) {
                    libraryItem.type = MediaWrapper.TYPE_STREAM
                } else if (libraryItem.itemType == MediaLibraryItem.TYPE_MEDIA && (libraryItem as MediaWrapper).type != MediaWrapper.TYPE_AUDIO)
                    continue

                /* Media ID */
                val mediaId = when (libraryItem.itemType) {
                    MediaLibraryItem.TYPE_MEDIA -> parentIdUri.buildUpon().appendQueryParameter("i", "$index").toString()
                    else -> generateMediaId(libraryItem)
                }

                /* Subtitle */
                val subtitle = when (libraryItem.itemType) {
                    MediaLibraryItem.TYPE_MEDIA -> {
                        val media = libraryItem as MediaWrapper
                        when {
                            media.type == MediaWrapper.TYPE_STREAM -> media.uri.toString()
                            parentId.startsWith(ID_ALBUM) -> getMediaSubtitle(media)
                            else -> getMediaDescription(getMediaArtist(context, media), getMediaAlbum(context, media))
                        }
                    }
                    MediaLibraryItem.TYPE_PLAYLIST -> res.getString(R.string.track_number, libraryItem.tracksCount)
                    MediaLibraryItem.TYPE_ARTIST -> {
                        val albumsCount = Medialibrary.getInstance().getArtist(libraryItem.id).albumsCount
                        res.getQuantityString(R.plurals.albums_quantity, albumsCount, albumsCount)
                    }
                    MediaLibraryItem.TYPE_GENRE -> {
                        val albumsCount = Medialibrary.getInstance().getGenre(libraryItem.id).albumsCount
                        res.getQuantityString(R.plurals.albums_quantity, albumsCount, albumsCount)
                    }
                    MediaLibraryItem.TYPE_ALBUM -> {
                        if (parentId.startsWith(ID_ARTIST))
                            res.getString(R.string.track_number, libraryItem.tracksCount)
                        else
                            libraryItem.description
                    }
                    else -> libraryItem.description
                }

                /* Extras */
                val extras = when (libraryItem.itemType) {
                    MediaLibraryItem.TYPE_ARTIST, MediaLibraryItem.TYPE_GENRE -> getContentStyle(CONTENT_STYLE_GRID_ITEM_HINT_VALUE, CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
                    else -> Bundle()
                }
                if (groupTitle != null) extras.putString(EXTRA_CONTENT_STYLE_GROUP_TITLE_HINT, groupTitle)

                /* Icon */
                val iconUri = if (libraryItem.itemType != MediaLibraryItem.TYPE_PLAYLIST && !libraryItem.artworkMrl.isNullOrEmpty() && isPathValid(libraryItem.artworkMrl)) {
                    val iconUri = Uri.Builder()
                    when (libraryItem.itemType) {
                        MediaLibraryItem.TYPE_ARTIST ->{
                            iconUri.appendPath(ArtworkProvider.ARTIST)
                            iconUri.appendPath("${libraryItem.tracksCount}")
                        }
                        MediaLibraryItem.TYPE_ALBUM -> {
                            iconUri.appendPath(ArtworkProvider.ALBUM)
                            iconUri.appendPath("${libraryItem.tracksCount}")
                        }
                        else -> {
                            iconUri.appendPath(ArtworkProvider.MEDIA)
                            (libraryItem as? MediaWrapper)?.let { iconUri.appendPath("${it.lastModified}") }
                        }
                    }
                    iconUri.appendPath("${libraryItem.id}")
                    artworkToUriCache.getOrPut(libraryItem.artworkMrl, { ArtworkProvider.buildUri(iconUri.build()) })
                } else if (libraryItem.itemType == MediaLibraryItem.TYPE_MEDIA && (libraryItem as MediaWrapper).type == MediaWrapper.TYPE_STREAM)
                    DEFAULT_STREAM_ICON
                else {
                    when (libraryItem.itemType) {
                        MediaLibraryItem.TYPE_ARTIST -> DEFAULT_ARTIST_ICON
                        MediaLibraryItem.TYPE_ALBUM -> DEFAULT_ALBUM_ICON
                        MediaLibraryItem.TYPE_GENRE -> null
                        MediaLibraryItem.TYPE_PLAYLIST -> {
                            val trackList = libraryItem.tracks.toList()
                            val hasArtwork = trackList.any { (ThumbnailsProvider.isMediaVideo(it) || (!it.artworkMrl.isNullOrEmpty() && isPathValid(it.artworkMrl))) }
                            if (!hasArtwork) DEFAULT_PLAYLIST_ICON else {
                                val playAllPlaylist = Uri.Builder()
                                        .appendPath(ArtworkProvider.PLAY_ALL)
                                        .appendPath(ArtworkProvider.PLAYLIST)
                                        .appendPath("${ArtworkProvider.computeChecksum(trackList, true)}")
                                        .appendPath("${libraryItem.tracksCount}")
                                        .appendPath("${libraryItem.id}")
                                        .build()
                                ArtworkProvider.buildUri(playAllPlaylist)
                            }
                        }
                        else -> DEFAULT_TRACK_ICON
                    }
                }

                /**
                 * Media Description
                 * The media URI not used in the browser and takes up a significant number of bytes.
                 */
                val description = MediaDescriptionCompat.Builder()
                        .setTitle(libraryItem.title)
                        .setSubtitle(subtitle)
                        .setIconUri(iconUri)
                        .setMediaId(mediaId)
                        .setExtras(extras)
                        .build()

                /* Set Flags */
                val flags = when (libraryItem.itemType) {
                    MediaLibraryItem.TYPE_MEDIA, MediaLibraryItem.TYPE_PLAYLIST -> MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                    else -> MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                }
                results.add(MediaBrowserCompat.MediaItem(description, flags))
                if ((limitSize && results.size == MAX_HISTORY_SIZE) || results.size == MAX_RESULT_SIZE) break
            }
            artworkToUriCache.clear()
            return results
        }

        fun getContentStyle(browsableHint: Int, playableHint: Int): Bundle {
            return Bundle().apply {
                putBoolean(CONTENT_STYLE_SUPPORTED, true)
                putInt(CONTENT_STYLE_BROWSABLE_HINT, browsableHint)
                putInt(CONTENT_STYLE_PLAYABLE_HINT, playableHint)
            }
        }

        fun generateMediaId(libraryItem: MediaLibraryItem): String {
            val prefix = when (libraryItem.itemType) {
                MediaLibraryItem.TYPE_ALBUM -> ID_ALBUM
                MediaLibraryItem.TYPE_ARTIST -> ID_ARTIST
                MediaLibraryItem.TYPE_GENRE -> ID_GENRE
                MediaLibraryItem.TYPE_PLAYLIST -> ID_PLAYLIST
                else -> ID_MEDIA
            }
            return "${prefix}/${libraryItem.id}"
        }

        fun isMediaAudio(libraryItem: MediaLibraryItem): Boolean {
            return libraryItem.itemType == MediaLibraryItem.TYPE_MEDIA && (libraryItem as MediaWrapper).type == MediaWrapper.TYPE_AUDIO
        }

        /**
         * At present Android Auto has no ability to directly handle paging so we must limit the size of the result
         * to avoid returning a parcel which exceeds the size limitations. We break the results into another
         * layer of browsable drill-downs labeled "start - finish" for each entry type.
         */
        private fun paginateLibrary(mediaList: Array<out MediaLibraryItem>, parentIdUri: Uri, iconUri: Uri, extras: Bundle? = null): List<MediaBrowserCompat.MediaItem> {
            val results: MutableList<MediaBrowserCompat.MediaItem> = ArrayList()
            /* Build menu items per group */
            for (page in 0..(mediaList.size / MAX_RESULT_SIZE)) {
                val offset = (page * MAX_RESULT_SIZE)
                val lastOffset = (offset + MAX_RESULT_SIZE - 1).coerceAtMost(mediaList.size - 1)
                if (offset >= lastOffset) break
                val mediaDesc = MediaDescriptionCompat.Builder()
                        .setTitle(buildRangeLabel(mediaList[offset].title, mediaList[lastOffset].title))
                        .setMediaId(parentIdUri.buildUpon().appendQueryParameter("p", "$page").toString())
                        .setIconUri(iconUri)
                        .setExtras(extras)
                        .build()
                results.add(MediaBrowserCompat.MediaItem(mediaDesc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE))
                if (results.size == MAX_RESULT_SIZE) break
            }
            return results
        }

        private fun buildRangeLabel(firstTitle: String, lastTitle: String): String {
            val beginTitle = formatArticles(firstTitle, true)
            val endTitle = formatArticles(lastTitle, true)
            var beginTitleSize = beginTitle.length
            var endTitleSize = endTitle.length
            val halfLabelSize = 10
            val maxLabelSize = 20
            if (beginTitleSize > halfLabelSize && endTitleSize > halfLabelSize) {
                beginTitleSize = halfLabelSize
                endTitleSize = halfLabelSize
            } else if (beginTitleSize > halfLabelSize) {
                beginTitleSize = (maxLabelSize - endTitleSize).coerceAtMost(beginTitleSize)
            } else if (endTitleSize > halfLabelSize) {
                endTitleSize = (maxLabelSize - beginTitleSize).coerceAtMost(endTitleSize)
            }
            return "${beginTitle.abbreviate(beginTitleSize).markBidi()} ⋅ ${endTitle.abbreviate(endTitleSize).markBidi()}"
        }

        private fun getPlayAllBuilder(res: Resources, mediaId: String, trackCount: Int, uri: Uri? = null): MediaDescriptionCompat.Builder {
            return MediaDescriptionCompat.Builder()
                    .setMediaId(mediaId)
                    .setTitle(res.getString(R.string.play_all))
                    .setSubtitle(res.getString(R.string.track_number, trackCount))
                    .setIconUri(if (uri != null) ArtworkProvider.buildUri(uri) else DEFAULT_PLAYALL_ICON)
        }

        private fun createExtensionServiceConnection(context: Context) {
            extensionServiceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    extensionManagerService = (service as ExtensionManagerService.LocalBinder).service.apply {
                        setExtensionManagerActivity(instance)
                    }
                    extensionLock.release()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    context.unbindService(extensionServiceConnection!!)
                    extensionServiceConnection = null
                    extensionManagerService!!.stopSelf()
                }
            }
            extensionServiceConnection?.let {
                val intent = Intent(context, ExtensionManagerService::class.java)
                if (!context.bindService(intent, it, Context.BIND_AUTO_CREATE)) extensionServiceConnection = null
            }
        }

        fun unbindExtensionConnection() {
            extensionManagerService?.disconnect()
        }
    }
}
