package org.graphite.musicdb

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Hilt entry point for the Astrea service, as AmbientMusicMod's Application was. */
@HiltAndroidApp
class MusicDbApplication : Application()
