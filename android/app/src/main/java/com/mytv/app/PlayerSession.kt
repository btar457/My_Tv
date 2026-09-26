package com.mytv.app

/** The list the player can zap through (the list the user tapped in). */
object PlayerSession {
    var channels: List<Channel> = emptyList()
    var index: Int = 0
}
