package eu.kanade.tachiyomi.animeextension.fr.franime

import aniyomi.lib.sendvidextractor.SendvidExtractor
import aniyomi.lib.sibnetextractor.SibnetExtractor
import aniyomi.lib.vidmolyextractor.VidMolyExtractor
import aniyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.animeextension.fr.franime.dto.Anime
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import keiyoushi.utils.parallelCatchingFlatMap
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.TimeUnit

class FrAnime : AnimeHttpSource() {

    override val name = "FRAnime"
    
    // Domain and URL configuration
    private val domain = "franime.fr"
    override val baseUrl = "https://$domain"
    
    // API endpoints - separated for clarity
    private val baseApiUrl = "https://api.$domain/api"
    private val baseApiAnimeUrl = "$baseApiUrl/anime"
    
    override val lang = "fr"
    override val supportsLatest = true

    /**
     * Default headers for all requests
     * Adds Referer and Origin headers to mimic browser behavior
     */
    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // JSON parser instance injected via dependency injection
    private val json: Json by injectLazy()

    // Cache timestamp for database refresh logic
    private var lastDatabaseFetch: Long = 0
    private var cachedDatabase: List<Anime>? = null
    private val cacheValidityDuration = TimeUnit.MINUTES.toMillis(30) // 30 minutes cache

    /**
     * Gets the anime database, either from cache or by fetching from API
     * Uses caching to reduce API calls and improve performance
     */
    private suspend fun getDatabase(): List<Anime> {
        val currentTime = System.currentTimeMillis()
        
        // Return cached data if still valid
        if (cachedDatabase != null && (currentTime - lastDatabaseFetch) < cacheValidityDuration) {
            return cachedDatabase!!
        }
        
        // Fetch fresh data from API
        return try {
            val response = client.newCall(GET("$baseApiUrl/animes/", headers)).await()
            val bodyString = response.body.string()
            val database = json.decodeFromString<List<Anime>>(bodyString)
            
            // Update cache
            cachedDatabase = database
            lastDatabaseFetch = currentTime
            
            database
        } catch (e: Exception) {
            // If fetch fails but we have cached data, use it
            cachedDatabase ?: throw e
        }
    }

    // ============================== Popular ===============================
    
    /**
     * Returns popular anime sorted by rating (note) descending
     * @param page Page number (1-based index)
     * @return AnimesPage containing 50 anime per page
     */
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val database = getDatabase()
        return pagesToAnimesPage(database.sortedByDescending { it.note }, page)
    }

    override fun popularAnimeParse(response: Response) = throw UnsupportedOperationException()
    override fun popularAnimeRequest(page: Int) = throw UnsupportedOperationException()

    // =============================== Latest ===============================
    
    /**
     * Returns latest anime updates (reverse order of database)
     * @param page Page number (1-based index)
     * @return AnimesPage containing 50 anime per page
     */
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val database = getDatabase()
        return pagesToAnimesPage(database.reversed(), page)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    // =============================== Search ===============================
    
    /**
     * Searches anime by query string or URL
     * Supports:
     * - Direct URL parsing (extracts anime ID from URL)
     * - Title search (matches against multiple title fields)
     * @param page Page number
     * @param query Search query or URL
     * @param filters Search filters (unused)
     * @return AnimesPage with matching results
     */
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val database = getDatabase()
        
        // Handle URL input (support for direct anime linking)
        if (query.startsWith("https://") || query.startsWith("http://")) {
            val url = query.toHttpUrl()
            
            // Validate that URL is from our domain
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported URL: Please use a $domain URL")
            }
            
            // Extract anime ID from URL path (format: /anime/{id})
            val id = url.pathSegments.getOrNull(1)
                ?: throw Exception("Invalid anime URL format")
                
            return getSearchAnime(page, id, filters)
        }

        // Text-based search across multiple title fields
        val pages = database.filter { anime ->
            anime.title.contains(query, true) ||
            anime.originalTitle.contains(query, true) ||
            anime.titlesAlt.en?.contains(query, true) == true ||
            anime.titlesAlt.enJp?.contains(query, true) == true ||
            anime.titlesAlt.jaJp?.contains(query, true) == true ||
            titleToUrl(anime.originalTitle).contains(query.lowercase())
        }
        return pagesToAnimesPage(pages, page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    // =========================== Anime Details ============================
    
    /**
     * Returns anime details
     * Since all data is already loaded from database, returns the same object
     * @param anime Anime object with URL set
     * @return The same anime object (already populated)
     */
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================
    
    /**
     * Gets episode list for a specific anime season and language
     * Parses the anime URL to extract season number, language, and anime identifier
     * @param anime Anime with URL containing season and language parameters
     * @return List of episodes with player availability
     */
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val database = getDatabase()
        val url = (baseUrl + anime.url).toHttpUrl()
        
        // Extract URL components
        val stem = url.encodedPathSegments.last() // Anime slug from URL
        val language = url.queryParameter("lang") ?: "vo" // "vo" for original, "vf" for French
        val season = url.queryParameter("s")?.toIntOrNull() ?: 1
        
        // Find matching anime in database
        val animeData = database.first { titleToUrl(it.originalTitle) == stem }
        
        // Generate episode list from season data
        val episodes = animeData.seasons[season - 1].episodes
            .mapIndexedNotNull { index, episode ->
                // Get players based on language selection
                val players = when (language) {
                    "vo" -> episode.languages.vo
                    else -> episode.languages.vf
                }.players

                // Skip episodes without available players
                if (players.isEmpty()) return@mapIndexedNotNull null

                SEpisode.create().apply {
                    setUrlWithoutDomain(anime.url + "&ep=${index + 1}")
                    name = episode.title ?: "Episode ${index + 1}"
                    episode_number = (index + 1).toFloat()
                }
            }
        
        return episodes.sortedByDescending { it.episode_number }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    
    /**
     * Extracts video streaming links for an episode
     * Supports multiple players: Sendvid, Sibnet, VK, VidMoly
     * @param episode Episode with URL containing season, episode, and language info
     * @return List of Video objects with streaming URLs
     */
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val database = getDatabase()
        val url = (baseUrl + episode.url).toHttpUrl()
        
        // Extract episode information from URL
        val seasonNumber = url.queryParameter("s")?.toIntOrNull() ?: 1
        val episodeNumber = url.queryParameter("ep")?.toIntOrNull() ?: 1
        val episodeLang = url.queryParameter("lang") ?: "vo"
        val stem = url.encodedPathSegments.last()
        
        // Find anime and episode data
        val animeData = database.first { titleToUrl(it.originalTitle) == stem }
        val episodeData = animeData.seasons[seasonNumber - 1].episodes[episodeNumber - 1]
        val videoBaseUrl = "$baseApiAnimeUrl/${animeData.id}/${seasonNumber - 1}/${episodeNumber - 1}"

        // Get available players based on language
        val players = if (episodeLang == "vo") {
            episodeData.languages.vo.players
        } else {
            episodeData.languages.vf.players
        }

        // Initialize video extractors lazily (only if needed)
        val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
        val sibnetExtractor by lazy { SibnetExtractor(client) }
        val vkExtractor by lazy { VkExtractor(client, headers) }
        val vidMolyExtractor by lazy { VidMolyExtractor(client, headers) }

        // Extract videos from all players in parallel
        val videos = players.withIndex().parallelCatchingFlatMap { (index, playerName) ->
            try {
                val apiUrl = "$videoBaseUrl/$episodeLang/$index"
                val playerUrl = client.newCall(GET(apiUrl, headers)).await().body.string()
                
                // Use appropriate extractor based on player type
                when (playerName.lowercase()) {
                    "sendvid" -> sendvidExtractor.videosFromUrl(playerUrl)
                    "sibnet" -> sibnetExtractor.videosFromUrl(playerUrl)
                    "vk" -> vkExtractor.videosFromUrl(playerUrl)
                    "vidmoly" -> vidMolyExtractor.videosFromUrl(playerUrl)
                    else -> {
                        // Log unsupported player types for debugging
                        emptyList()
                    }
                }
            } catch (e: Exception) {
                // Log error and continue with other players
                emptyList()
            }
        }
        
        return videos
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    // ============================= Utilities ==============================
    
    /**
     * Converts a list of Anime objects to paginated AnimesPage
     * Each page contains up to 50 anime entries
     * @param pages Full list of anime to paginate
     * @param page Page number to retrieve (1-based)
     * @return AnimesPage with entries for requested page and pagination info
     */
    private fun pagesToAnimesPage(pages: List<Anime>, page: Int): AnimesPage {
        val chunks = pages.chunked(50) // Split into pages of 50
        val hasNextPage = chunks.size > page
        val entries = pageToSAnimes(chunks.getOrNull(page - 1) ?: emptyList())
        return AnimesPage(entries, hasNextPage)
    }

    /**
     * Regular expression to clean titles for URL generation
     * Removes all characters except letters, numbers, and spaces
     */
    private val titleRegex by lazy { Regex("[^A-Za-z0-9 ]") }
    
    /**
     * Converts an anime title to a URL-friendly slug
     * @param title Original anime title
     * @return URL-safe slug (lowercase, hyphens instead of spaces)
     */
    private fun titleToUrl(title: String): String {
        return titleRegex.replace(title, "")
            .trim()
            .replace(Regex("\\s+"), "-") // Replace multiple spaces with single hyphen
            .lowercase()
    }

    /**
     * Converts a page of Anime objects to SAnime entries
     * Creates entries for each season and language variant
     * @param page List of Anime objects
     * @return List of SAnime entries with all available variants
     */
    private fun pageToSAnimes(page: List<Anime>): List<SAnime> {
        return page.flatMap { anime ->
            anime.seasons.flatMapIndexed { seasonIndex, season ->
                val seasonTitle = anime.title + if (anime.seasons.size > 1) " S${seasonIndex + 1}" else ""
                
                // Check which language versions have available players
                val hasVostfr = season.episodes.any { ep -> ep.languages.vo.players.isNotEmpty() }
                val hasVf = season.episodes.any { ep -> ep.languages.vf.players.isNotEmpty() }

                // Create language variants with availability flags
                // Triple format: (Display Name, Language Code, Has Alternative Language)
                val languages = listOfNotNull(
                    if (hasVostfr) Triple("VOSTFR", "vo", hasVf) else null,
                    if (hasVf) Triple("VF", "vf", hasVostfr) else null,
                )

                languages.map { (displayName, langCode, hasAlternative) ->
                    SAnime.create().apply {
                        title = seasonTitle + if (hasAlternative) " ($displayName)" else ""
                        thumbnail_url = anime.poster
                        genre = anime.genres.joinToString()
                        status = parseStatus(anime.status, anime.seasons.size, seasonIndex + 1)
                        description = anime.description
                        setUrlWithoutDomain("/anime/${titleToUrl(anime.originalTitle)}?lang=$langCode&s=${seasonIndex + 1}")
                        initialized = true
                    }
                }
            }
        }
    }

    /**
     * Parses anime status string to SAnime status constant
     * @param statusString Status from API ("EN COURS", "TERMINÉ", etc.)
     * @param seasonCount Total number of seasons
     * @param currentSeason Current season number
     * @return SAnime status constant (ONGOING, COMPLETED, or UNKNOWN)
     */
    private fun parseStatus(statusString: String?, seasonCount: Int = 1, currentSeason: Int = 1): Int {
        // If not on the last season, mark as completed (all episodes available)
        if (currentSeason < seasonCount) return SAnime.COMPLETED
        
        // Parse the status string for the current/last season
        return when (statusString?.trim()?.uppercase()) {
            "EN COURS" -> SAnime.ONGOING
            "TERMINÉ" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }
}
