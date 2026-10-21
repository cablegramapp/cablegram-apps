import Foundation
import Testing
@testable import CablegramCore

// Port of `ChannelHintsTest.kt`.

@Suite struct ChannelHintsTests {
    let chat: Int64 = -1004481882279
    let nickels = "AgADZR4AAh5DeFI"

    func note(_ text: String) -> TgMessage { TgMessage(chatId: chat, messageId: 50, video: nil, text: text) }

    func video(_ unique: String, _ caption: String) -> TgVideo {
        TgVideo(chatId: chat, messageId: 40, file: TgFile(id: 1, size: 100, uniqueId: unique), fileName: "N.2026.mkv", caption: caption, mimeType: "video/x-matroska")
    }

    @Test func aReplyNamingALibraryVideoAndAMovieDescribesThatVideo() {
        let reply = note("✅ Video enrichment completed.\nvideo_id: #\(nickels)\n#movie_1313826")
        #expect(ChannelHints.from(reply, known: [nickels]) == [TgTmdbHint(uniqueId: nickels, tmdbType: "movie", tmdbId: 1313826)])
    }

    @Test func aCardWithTheSameTitleAsATagAndALinkIsOneReference() {
        let card = note("Nickels (2026)\n#movie_1313826\nvideo_id: #\(nickels)\nhttps://www.themoviedb.org/movie/1313826-nickels")
        #expect(ChannelHints.from(card, known: [nickels]) == [TgTmdbHint(uniqueId: nickels, tmdbType: "movie", tmdbId: 1313826)])
    }

    @Test func episodesKeepTheirSeasonAndEpisodeFromATagOrALink() {
        #expect(ChannelHints.from(note("#\(nickels) #tv_42_s02e03"), known: [nickels]) == [TgTmdbHint(uniqueId: nickels, tmdbType: "tv", tmdbId: 42, season: 2, episode: 3)])
        #expect(ChannelHints.from(note("#\(nickels) https://www.themoviedb.org/tv/1399-game-of-thrones/season/1/episode/9"), known: [nickels])
            == [TgTmdbHint(uniqueId: nickels, tmdbType: "tv", tmdbId: 1399, season: 1, episode: 9)])
    }

    @Test func aTMDBReferenceInAVideosOwnCaptionDescribesIt() {
        let captioned = TgMessage(chatId: chat, messageId: 40, video: video(nickels, "Nickels (2026) #movie_1313826"), text: "Nickels (2026) #movie_1313826")
        #expect(ChannelHints.from(captioned, known: []) == [TgTmdbHint(uniqueId: nickels, tmdbType: "movie", tmdbId: 1313826)])
    }

    @Test func nothingWithoutALibraryVideoAReferenceOrWithTwoDifferentTitles() {
        #expect(ChannelHints.from(note("#AgADunknown1 #movie_1"), known: [nickels]) == [])
        #expect(ChannelHints.from(note("⏳ Processing video… video_id: #\(nickels)"), known: [nickels]) == [])
        #expect(ChannelHints.from(note("#\(nickels) #movie_1 #movie_2"), known: [nickels]) == [])
    }
}
