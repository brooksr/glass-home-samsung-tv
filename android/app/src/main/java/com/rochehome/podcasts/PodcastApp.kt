package com.rochehome.podcasts

import android.app.Application

class PodcastApp : Application() {
    val repo: Repo by lazy { Repo(this) }
    val news: News by lazy { News(this) }
    val radio: Radio by lazy { Radio(this) }
}
