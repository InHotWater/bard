package com.stan.libbylight

import android.app.Application
import com.stan.libbylight.library.LocalBookRepository
import com.stan.libbylight.library.AudiobookProgressStore
import com.stan.libbylight.library.RssFeedRepository
import com.stan.libbylight.library.RssDownloadManager
import com.stan.libbylight.player.LocalPlaybackController
import com.stan.libbylight.player.BardMediaSessionManager
import com.stan.libbylight.player.LockScreenControlsOverlay

class LibbyLightApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LibbyWebPlayer.init(this)
        AudiobookProgressStore.init(this)
        RssFeedRepository.init(this)
        RssDownloadManager.init(this)
        LocalBookRepository.init(this)
        LocalPlaybackController.init(this)
        LockScreenControlsOverlay.init(this)
        BardMediaSessionManager.init(this)
    }
}
