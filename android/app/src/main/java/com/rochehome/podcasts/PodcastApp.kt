package com.rochehome.podcasts

import android.app.Application

class PodcastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AdFree.init(this)
    }

    val repo: Repo by lazy { Repo(this) }
    val news: News by lazy { News(this) }
    val radio: Radio by lazy { Radio(this) }
    val watch: Watch by lazy { Watch(this) }
}
