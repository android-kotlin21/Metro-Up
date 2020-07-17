package code.name.monkey.retromusic.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Spanned
import android.transition.Slide
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import code.name.monkey.appthemehelper.util.ATHUtil
import code.name.monkey.appthemehelper.util.MaterialUtil
import code.name.monkey.retromusic.App
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.base.AbsSlidingMusicPanelActivity
import code.name.monkey.retromusic.adapter.album.HorizontalAlbumAdapter
import code.name.monkey.retromusic.adapter.song.SimpleSongAdapter
import code.name.monkey.retromusic.dialogs.AddToPlaylistDialog
import code.name.monkey.retromusic.extensions.ripAlpha
import code.name.monkey.retromusic.extensions.show
import code.name.monkey.retromusic.extensions.toCommonData
import code.name.monkey.retromusic.glide.ArtistGlideRequest
import code.name.monkey.retromusic.glide.RetroMusicColoredTarget
import code.name.monkey.retromusic.glide.palette.BitmapPaletteTranscoder
import code.name.monkey.retromusic.glide.palette.BitmapPaletteWrapper
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.interfaces.CabHolder
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.model.CommonData
import code.name.monkey.retromusic.mvp.presenter.ArtistDetailsPresenter
import code.name.monkey.retromusic.mvp.presenter.ArtistDetailsView
import code.name.monkey.retromusic.rest.lastfm.model.LastFmArtist
import code.name.monkey.retromusic.util.*
import com.afollestad.materialcab.MaterialCab
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.android.synthetic.main.activity_artist_content.*
import kotlinx.android.synthetic.main.activity_artist_details.*
import java.util.*
import javax.inject.Inject
import kotlin.collections.ArrayList

class ArtistDetailActivity : AbsSlidingMusicPanelActivity(), ArtistDetailsView, CabHolder {
    override fun openCab(menuRes: Int, callback: MaterialCab.Callback): MaterialCab {
        cab?.let {
            if (it.isActive) it.finish()
        }
        cab = MaterialCab(this, R.id.cab_stub)
                .setMenu(menuRes)
                .setCloseDrawableRes(R.drawable.ic_close_white_24dp)
                .setBackgroundColor(
                        RetroColorUtil.shiftBackgroundColorForLightText(
                                ATHUtil.resolveColor(
                                        this,
                                        R.attr.colorSurface
                                )
                        )
                )
                .start(callback)
        return cab as MaterialCab
    }

    private var cab: MaterialCab? = null
    private var biography: Spanned? = null
    private lateinit var commonData: CommonData
    private lateinit var artist: Artist
    private lateinit var songAdapter: SimpleSongAdapter
    private lateinit var albumAdapter: HorizontalAlbumAdapter
    private var forceDownload: Boolean = false

    override fun createContentView(): View {
        return wrapSlidingMusicPanel(R.layout.activity_artist_details)
    }

    @Inject
    lateinit var artistDetailsPresenter: ArtistDetailsPresenter

    private fun windowEnterTransition() {
        val slide = Slide()
        slide.excludeTarget(R.id.appBarLayout, true)
        slide.excludeTarget(R.id.status_bar, true)
        slide.excludeTarget(android.R.id.statusBarBackground, true)
        slide.excludeTarget(android.R.id.navigationBarBackground, true)
        window.enterTransition = slide
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setDrawUnderStatusBar()
        super.onCreate(savedInstanceState)
        toggleBottomNavigationView(true)
        setStatusbarColorAuto()
        setNavigationbarColorAuto()
        setTaskDescriptionColorAuto()
        setLightNavigationBar(true)
        window.sharedElementsUseOverlay = true

        App.musicComponent.inject(this)
        artistDetailsPresenter.attachView(this)

        commonData = intent.getParcelableExtra(EXTRA_COMMON_DATA)
        if (!commonData.localArtist()) {
            artist(commonData)
        }
        reload()
        windowEnterTransition()
        ActivityCompat.postponeEnterTransition(this)
        setupRecyclerView()

        playAction.apply {
            setOnClickListener {
                if (commonData.localArtist()) {
                    MusicPlayerRemote.openQueue(
                            this@ArtistDetailActivity,
                            commonData.getLocalArtist().songs.toCommonData(),
                            0,
                            true
                    )
                } else if (commonData.cloudArtist()) {
                    MusicPlayerRemote.openQueue(
                            this@ArtistDetailActivity,
                            commonData.getCloudArtist().songs.toCommonData(),
                            0,
                            true
                    )
                }
            }
        }
        shuffleAction.apply {
            setOnClickListener {
                if (commonData.localArtist()) {
                    MusicPlayerRemote.openAndShuffleQueue(
                            this@ArtistDetailActivity,
                            commonData.getLocalArtist().songs.toCommonData(),
                            true
                    )
                } else if (commonData.cloudArtist()) {
                    MusicPlayerRemote.openAndShuffleQueue(
                            this@ArtistDetailActivity,
                            commonData.getCloudArtist().songs.toCommonData(),
                            true
                    )
                }
            }
        }
        biographyText.setOnClickListener {
            if (biographyText.maxLines == 4) {
                biographyText.maxLines = Integer.MAX_VALUE
            } else {
                biographyText.maxLines = 4
            }
        }
        complete()
    }

    override fun onDestroy() {
        super.onDestroy()
        artistDetailsPresenter.detachView()
    }

    private fun setupRecyclerView() {
        albumAdapter = HorizontalAlbumAdapter(this, ArrayList(), null)
        albumRecyclerView.apply {
            itemAnimator = DefaultItemAnimator()
            layoutManager = GridLayoutManager(this.context, 1, GridLayoutManager.HORIZONTAL, false)
            adapter = albumAdapter
        }
        songAdapter = SimpleSongAdapter(this, ArrayList(), R.layout.item_song, this)
        recyclerView.apply {
            itemAnimator = DefaultItemAnimator()
            layoutManager = LinearLayoutManager(this.context)
            adapter = songAdapter
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_SELECT_IMAGE -> if (resultCode == Activity.RESULT_OK) {
                if (commonData.localArtist()) {
                    data?.data?.let {
                        CustomArtistImageUtil.getInstance(this).setCustomArtistImage(artist, it)
                    }
                }
            }
            else -> if (resultCode == Activity.RESULT_OK) {
                reload()
            }
        }
    }

    override fun showEmptyView() {
    }

    override fun complete() {
        ActivityCompat.startPostponedEnterTransition(this)
    }

    override fun artist(commonData: CommonData) {
        this.commonData = commonData
        if (commonData.localArtist()) {
            val artist = commonData.getLocalArtist()
            if (artist.songCount <= 0) {
                finish()
            }
            this.artist = artist
            loadArtistImage()
            artistTitle.text = artist.name
            text.text = String.format(
                    "%s • %s",
                    MusicUtil.getArtistInfoString(this, artist),
                    MusicUtil.getReadableDurationString(MusicUtil.getTotalDuration(artist.songs))
            )
            if (RetroUtil.isAllowedToDownloadMetadata(this)) {
                loadBiography(artist.name)
            }
            songAdapter.swapDataSet(artist.songs.toCommonData())
            albumAdapter.swapDataSet(artist.albums!!)
        } else if (commonData.cloudArtist()) {
            val artist = commonData.getCloudArtist()
            text.text = String.format(
                    "%s • %s",
                    MusicUtil.getSongString(this, artist.singer.tracksCount),
                    MusicUtil.getReadableDurationString(MusicUtil.getTotalCurSongsDuration(artist.songs))
            )
            songAdapter.swapDataSet(artist.songs.toCommonData())
        } else if (commonData.cloudArtistPlayList()) {
            val artist = commonData.getCloudArtistPlaylist()
            Glide.with(this)
                    .load(artist.img)
                    .asBitmap()
                    .transcode(BitmapPaletteTranscoder(this), BitmapPaletteWrapper::class.java)
                    .diskCacheStrategy(DiskCacheStrategy.ALL).skipMemoryCache(false)
                    .into(object : RetroMusicColoredTarget(image) {
                        override fun onColorReady(color: Int) {
                            setColors(color)
                        }
                    })
            artistTitle.text = artist.title
            albumTitle.visibility = View.GONE
            if (RetroUtil.isAllowedToDownloadMetadata(this)) {
                loadBiography(artist.title)
            }
        }
    }

    private fun loadBiography(
            name: String,
            lang: String? = Locale.getDefault().language
    ) {
        biography = null
        this.lang = lang
        artistDetailsPresenter.loadBiography(name, lang, null)
    }

    override fun artistInfo(lastFmArtist: LastFmArtist?) {
        if (lastFmArtist != null && lastFmArtist.artist != null) {
            val bioContent = lastFmArtist.artist.bio.content
            if (bioContent != null && bioContent.trim { it <= ' ' }.isNotEmpty()) {
                biographyText.visibility = View.VISIBLE
                biographyTitle.visibility = View.VISIBLE
                biography = HtmlCompat.fromHtml(bioContent, HtmlCompat.FROM_HTML_MODE_LEGACY)
                biographyText.text = biography
                if (lastFmArtist.artist.stats.listeners.isNotEmpty()) {
                    listeners.show()
                    listenersLabel.show()
                    scrobbles.show()
                    scrobblesLabel.show()

                    listeners.text =
                            RetroUtil.formatValue(lastFmArtist.artist.stats.listeners.toFloat())
                    scrobbles.text =
                            RetroUtil.formatValue(lastFmArtist.artist.stats.playcount.toFloat())
                }
            }
        }

        // If the "lang" parameter is set and no biography is given, retry with default language
        if (biography == null && lang != null) {
            if (commonData.localArtist()) {
                loadBiography(artist.name, null)
            } else if (commonData.cloudArtist()) {
                loadBiography(commonData.getCloudArtist().singer.title, null)
            }
        }
    }

    private var lang: String? = null

    private fun loadArtistImage() {
        ArtistGlideRequest.Builder.from(Glide.with(this), artist).generatePalette(this).build()
                .dontAnimate().into(object : RetroMusicColoredTarget(image) {
                    override fun onColorReady(color: Int) {
                        setColors(color)
                    }
                })
    }

    private fun setColors(color: Int) {
        val textColor = if (PreferenceUtil.getInstance(this).adaptiveColor)
            color.ripAlpha()
        else
            ATHUtil.resolveColor(this, android.R.attr.textColorPrimary)

        albumTitle.setTextColor(textColor)
        songTitle.setTextColor(textColor)
        biographyTitle.setTextColor(textColor)

        val buttonColor = if (PreferenceUtil.getInstance(this).adaptiveColor)
            color.ripAlpha()
        else
            ATHUtil.resolveColor(this, R.attr.colorSurface)

        MaterialUtil.setTint(button = shuffleAction, color = buttonColor)
        MaterialUtil.setTint(button = playAction, color = buttonColor)

        val toolbarColor = ATHUtil.resolveColor(this, R.attr.colorSurface)
        toolbar.setBackgroundColor(toolbarColor)
        setSupportActionBar(toolbar)
        supportActionBar?.title = null
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return handleSortOrderMenuItem(item)
    }

    private fun handleSortOrderMenuItem(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                super.onBackPressed()
                return true
            }
            R.id.action_play_next -> {
                if (commonData.localArtist()) {
                    val songs = artist.songs
                    MusicPlayerRemote.playNext(songs.toCommonData())
                }
                return true
            }
            R.id.action_add_to_current_playing -> {
                if (commonData.localArtist()) {
                    val songs = artist.songs
                    MusicPlayerRemote.enqueue(songs.toCommonData())
                }
                return true
            }
            R.id.action_add_to_playlist -> {
                if (commonData.localArtist()) {
                    val songs = artist.songs
                    AddToPlaylistDialog.create(songs.toCommonData())
                            .show(supportFragmentManager, "ADD_PLAYLIST")
                }
                return true
            }
//            R.id.action_set_artist_image -> {
//                if (commonData.localArtist()) {
//                    val intent = Intent(Intent.ACTION_GET_CONTENT)
//                    intent.type = "image/*"
//                    startActivityForResult(
//                            Intent.createChooser(intent, getString(R.string.pick_from_local_storage)),
//                            REQUEST_CODE_SELECT_IMAGE
//                    )
//                }
//                return true
//            }
//            R.id.action_reset_artist_image -> {
//                if (commonData.localArtist()) {
//                    Toast.makeText(
//                            this@ArtistDetailActivity,
//                            resources.getString(R.string.updating),
//                            Toast.LENGTH_SHORT
//                    ).show()
//                    CustomArtistImageUtil.getInstance(this@ArtistDetailActivity)
//                            .resetCustomArtistImage(artist)
//                    forceDownload = true
//                }
//                return true
//            }
        }
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_artist_detail, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onMediaStoreChanged() {
        super.onMediaStoreChanged()
        reload()
    }

    private fun reload() {
        if (intent.extras!!.containsKey(EXTRA_ARTIST_ID)) {
            intent.extras?.getInt(EXTRA_ARTIST_ID)?.let {
                intent.extras?.getBoolean(EXTRA_TYPE_LOCAL, true)?.let { local ->
                    artistDetailsPresenter.loadArtist(it, local)
                }
                val name = "${getString(R.string.transition_artist_image)}_$it"
                artistCoverContainer?.transitionName = name
            }
        } else {
            finish()
        }
    }

    override fun onBackPressed() {
        if (cab != null && cab!!.isActive) {
            cab?.finish()
        } else {
            super.onBackPressed()
        }
    }

    companion object {

        const val EXTRA_ARTIST_ID = "extra_artist_id"
        const val EXTRA_TYPE_LOCAL = "extra_type_local"
        const val EXTRA_COMMON_DATA = "extra_common_data"
        const val REQUEST_CODE_SELECT_IMAGE = 9003
    }
}
