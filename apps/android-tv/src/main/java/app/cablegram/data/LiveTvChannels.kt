package app.cablegram.data

import androidx.annotation.DrawableRes
import app.cablegram.R

/**
 * Advertiser-supported or public-broadcaster HLS feeds.
 * These URLs are published by the channels themselves for free viewing.
 */
data class LiveTvChannel(
    val id: String,
    val title: String,
    val category: String,
    val streamUrl: String,
    @DrawableRes val posterRes: Int,
    val overview: String,
) {
    fun toVideo(): Video = Video(
        id = LIVE_ID_PREFIX + id,
        title = title,
        addedAtTimestamp = "1970-01-01T00:00:00Z",
        overview = overview,
        genres = listOf(category, "Live"),
        mediaType = "live",
        resolution = "live",
        matchStatus = "matched",
    )
}

const val LIVE_ID_PREFIX = "live:"

fun isLiveChannelId(videoId: String): Boolean = videoId.startsWith(LIVE_ID_PREFIX)

val FREE_LIVE_TV_CHANNELS: List<LiveTvChannel> = listOf(
    LiveTvChannel(
        id = "nasa-tv",
        title = "NASA TV",
        category = "Science",
        streamUrl = "https://ntv1.akamaized.net/hls/live/2014075/NASA-NTV1-HLS/master.m3u8",
        posterRes = R.drawable.live_nasa_tv,
        overview = "NASA's public channel: launches, ISS coverage, and agency programming.",
    ),
    LiveTvChannel(
        id = "nasa-media",
        title = "NASA Media",
        category = "Science",
        streamUrl = "https://ntv2.akamaized.net/hls/live/2013923/NASA-NTV2-HLS/master.m3u8",
        posterRes = R.drawable.live_nasa_media,
        overview = "NASA Media Channel with clean mission feeds and press events.",
    ),
    LiveTvChannel(
        id = "dw-english",
        title = "DW English",
        category = "News",
        streamUrl = "https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8",
        posterRes = R.drawable.live_dw,
        overview = "Deutsche Welle's English-language news and current affairs.",
    ),
    LiveTvChannel(
        id = "bloomberg",
        title = "Bloomberg TV",
        category = "News",
        streamUrl = "https://www.bloomberg.com/media-manifest/streams/us.m3u8",
        posterRes = R.drawable.live_bloomberg,
        overview = "Live U.S. business and markets coverage from Bloomberg.",
    ),
    LiveTvChannel(
        id = "cbs-news",
        title = "CBS News",
        category = "News",
        streamUrl = "https://cbsn-us.cbsnstream.cbsnews.com/out/v1/55a8648e8f134e82a470f83d562deeca/master.m3u8",
        posterRes = R.drawable.live_cbs,
        overview = "CBS News 24/7 streaming channel.",
    ),
    LiveTvChannel(
        id = "arirang",
        title = "Arirang TV",
        category = "News",
        streamUrl = "https://amdlive-ch01-ctnd-com.akamaized.net/arirang_1ch/smil:arirang_1ch.smil/playlist.m3u8",
        posterRes = R.drawable.live_arirang,
        overview = "South Korea's international English-language public channel.",
    ),
    LiveTvChannel(
        id = "red-bull",
        title = "Red Bull TV",
        category = "Sports",
        streamUrl = "https://rbmn-live.akamaized.net/hls/live/590964/BoRB-AT/master.m3u8",
        posterRes = R.drawable.live_redbull,
        overview = "Free sports, adventure, and event programming from Red Bull.",
    ),
)
