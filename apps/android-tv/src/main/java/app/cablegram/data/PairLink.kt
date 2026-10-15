package app.cablegram.data

import java.net.URLEncoder

/**
 * What the TV's pairing QR code holds (CAB-62): a web address, so any phone camera can open it. The web remote pairs
 * from the browser (iPhone, or Android without the app), and the phone app takes the link when it is installed. The
 * PIN is the one also shown on screen; [tvName] lets the phone say which TV it is connecting.
 */
fun pairLink(webAppBase: String, pin: String, tvName: String): String =
    "${webAppBase.trimEnd('/')}/?pair=$pin&tv=${URLEncoder.encode(tvName, "UTF-8")}"
