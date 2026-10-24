package tr.com.uslanozan.evritext.captions

/** Caption metadata and timed text needed by the translation pipeline. */
interface CaptionRepository {
    suspend fun probe(videoId: String): CaptionSource.VideoCaptions
    suspend fun download(track: CaptionSource.Track): String
}
