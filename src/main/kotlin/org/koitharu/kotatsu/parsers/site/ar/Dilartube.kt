package org.koitharu.kotatsu.parsers.site.all

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.AbstractMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQueryCapabilities
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria.*
import org.koitharu.kotatsu.parsers.model.search.SearchCapability
import org.koitharu.kotatsu.parsers.model.search.SearchableField
import org.koitharu.kotatsu.parsers.model.search.SearchableField.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

private const val PAGE_SIZE = 20
private const val CHAPTERS_FIRST_PAGE_SIZE = 100
private const val CHAPTERS_MAX_PAGE_SIZE = 300
private const val CHAPTERS_PARALLELISM = 2
private const val LOCALE_FALLBACK = "ar"

@MangaSourceParser("DILARTUBE", "Dilar Tube")
internal class DilarTubeParser(context: MangaLoaderContext) : AbstractMangaParser(context, MangaParserSource.DILARTUBE) {

	override val configKeyDomain = ConfigKey.Domain("dilar.tube")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: EnumSet<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.UPDATED_ASC,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_ASC,
		SortOrder.RATING,
		SortOrder.RATING_ASC,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.ADDED,
		SortOrder.ADDED_ASC,
	)

	override val searchQueryCapabilities: MangaSearchQueryCapabilities
		get() = MangaSearchQueryCapabilities(
			SearchCapability(
				field = TAG,
				criteriaTypes = setOf(Include::class, Exclude::class),
				isMultiple = true,
			),
			SearchCapability(
				field = TITLE_NAME,
				criteriaTypes = setOf(Match::class),
				isMultiple = false,
			),
			SearchCapability(
				field = STATE,
				criteriaTypes = setOf(Include::class),
				isMultiple = true,
			),
			SearchCapability(
				field = AUTHOR,
				criteriaTypes = setOf(Include::class),
				isMultiple = true,
			),
			SearchCapability(
				field = CONTENT_TYPE,
				criteriaTypes = setOf(Include::class),
				isMultiple = true,
			),
			SearchCapability(
				field = DEMOGRAPHIC,
				criteriaTypes = setOf(Include::class),
				isMultiple = true,
			),
			SearchCapability(
				field = PUBLICATION_YEAR,
				criteriaTypes = setOf(Match::class),
				isMultiple = false,
			),
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions = coroutineScope {
		val tagsDeferred = async { fetchAvailableTags() }
		MangaListFilterOptions(
			availableTags = tagsDeferred.await(),
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.PAUSED,
				MangaState.ABANDONED,
			),
			availableContentRating = EnumSet.of(
				ContentRating.SAFE,
				ContentRating.SUGGESTIVE,
			),
			availableDemographics = EnumSet.of(
				Demographic.SHOUNEN,
				Demographic.SHOUJO,
				Demographic.SEINEN,
				Demographic.JOSEI,
				Demographic.NONE,
			),
			availableLocales = setOf(Locale("ar")), // Arabic only
		)
	}

	private fun SearchableField.toParamName(): String = when (this) {
		TITLE_NAME -> "title"
		TAG -> "genre[]"
		AUTHOR -> "author[]"
		STATE -> "status[]"
		CONTENT_TYPE -> "type[]"
		DEMOGRAPHIC -> "demographic[]"
		PUBLICATION_YEAR -> "year"
		else -> ""
	}

	private fun Any?.toQueryParam(): String = when (this) {
		is String -> urlEncoded()
		is MangaTag -> key
		is MangaState -> when (this) {
			MangaState.ONGOING -> "مستمر"
			MangaState.FINISHED -> "مكتمل"
			MangaState.ABANDONED -> "ملغي"
			MangaState.PAUSED -> "متوقف"
			else -> ""
		}
		is Demographic -> when (this) {
			Demographic.SHOUNEN -> "شونين"
			Demographic.SHOUJO -> "شوجو"
			Demographic.SEINEN -> "سين"
			Demographic.JOSEI -> "جوسي"
			Demographic.NONE -> "بلا تصنيف"
			else -> ""
		}
		is SortOrder -> when (this) {
			SortOrder.UPDATED -> "updated_desc"
			SortOrder.UPDATED_ASC -> "updated_asc"
			SortOrder.RATING -> "rating_desc"
			SortOrder.RATING_ASC -> "rating_asc"
			SortOrder.ALPHABETICAL -> "title_asc"
			SortOrder.ALPHABETICAL_DESC -> "title_desc"
			SortOrder.NEWEST -> "year_desc"
			SortOrder.NEWEST_ASC -> "year_asc"
			SortOrder.POPULARITY -> "views_desc"
			SortOrder.POPULARITY_ASC -> "views_asc"
			SortOrder.ADDED -> "created_desc"
			SortOrder.ADDED_ASC -> "created_asc"
			else -> "updated_desc"
		}
		else -> this.toString().urlEncoded()
	}

	private fun StringBuilder.appendCriterion(field: SearchableField, value: Any?, paramName: String? = null) {
		val param = paramName ?: field.toParamName()
		if (param.isNotBlank()) {
			append("&$param=")
			append(value.toQueryParam())
		}
	}

	override suspend fun getList(query: MangaSearchQuery): List<Manga> {
		val url = buildString {
			append("https://api.$domain/mangas?limit=$PAGE_SIZE&offset=${query.offset}")

			query.criteria.forEach { criterion ->
				when (criterion) {
					is Include<*> -> {
						criterion.values.forEach { appendCriterion(criterion.field, it) }
					}
					is Exclude<*> -> {
						criterion.values.forEach { appendCriterion(criterion.field, it, "exclude_${criterion.field.toParamName()}") }
					}
					is Match<*> -> {
						appendCriterion(criterion.field, criterion.value)
					}
					else -> {
						// Not supported
					}
				}
			}

			append("&sort=")
			append((query.order ?: defaultSortOrder).toQueryParam())
		}

		return try {
			val json = webClient.httpGet(url).parseJson()
			val data = json.optJSONArray("data") ?: json.optJSONArray("mangas") ?: JSONArray()
			data.mapJSON { jo -> jo.fetchManga(null) }
		} catch (e: Exception) {
			// Fallback to HTML parsing if API is not available
			parseHtmlList(query)
		}
	}

	private suspend fun parseHtmlList(query: MangaSearchQuery): List<Manga> {
		val url = buildString {
			append("https://$domain/mangas?page=${(query.offset / PAGE_SIZE) + 1}")
			
			// Add search parameters for HTML version
			query.criteria.forEach { criterion ->
				when (criterion) {
					is Match<*> -> {
						if (criterion.field == TITLE_NAME) {
							append("&search=")
							append(criterion.value.toString().urlEncoded())
						}
					}
					else -> {
						// Other criteria can be added as needed
					}
				}
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		val mangaElements = doc.select(".manga-item, .manga-card, .manga-list-item")
		
		return mangaElements.mapNotNull { element ->
			try {
				val titleElement = element.selectFirst("h3, .title, .manga-title")
				val title = titleElement?.text()?.trim() ?: return@mapNotNull null
				
				val linkElement = element.selectFirst("a[href*='/manga/'], a[href*='/series/']")
				val url = linkElement?.attr("href") ?: return@mapNotNull null
				
				val coverElement = element.selectFirst("img")
				val coverUrl = coverElement?.attr("src") ?: coverElement?.attr("data-src")
				
				val statusElement = element.selectFirst(".status, .manga-status")
				val status = statusElement?.text()?.trim()
				
				Manga(
					id = generateUid(url),
					title = title,
					altTitle = null,
					url = url.removePrefix("/"),
					publicUrl = "https://$domain$url",
					rating = RATING_UNKNOWN,
					isNsfw = false,
					coverUrl = coverUrl?.let { if (it.startsWith("http")) it else "https://$domain$it" },
					tags = emptySet<MangaTag>(),
					state = parseState(status),
					author = null,
					description = null,
					chapters = null,
					source = source,
				)
			} catch (e: Exception) {
				null
			}
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaId = manga.url.removePrefix("/")
		return getDetails(mangaId)
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val mangaId = link.pathSegments.findLast { it.isNotBlank() } ?: return null
		return try {
			getDetails(mangaId)
		} catch (e: Exception) {
			null
		}
	}

	private suspend fun getDetails(mangaId: String): Manga = coroutineScope {
		return@coroutineScope try {
			// Try API first
			val jsonDeferred = async {
				webClient.httpGet("https://api.$domain/manga/$mangaId").parseJson()
			}
			val feedDeferred = async { loadChapters(mangaId) }
			val manga = jsonDeferred.await().fetchManga(mapChapters(feedDeferred.await()))
			manga
		} catch (e: Exception) {
			// Fallback to HTML parsing
			parseHtmlDetails(mangaId)
		}
	}

	private suspend fun parseHtmlDetails(mangaId: String): Manga {
		val url = if (mangaId.startsWith("http")) mangaId else "https://$domain/manga/$mangaId"
		val doc = webClient.httpGet(url).parseHtml()
		
		val title = doc.selectFirst("h1, .manga-title, .title")?.text()?.trim()
			?: throw ParseException("Title not found", url)
		
		val description = doc.selectFirst(".description, .summary, .manga-description")?.text()?.trim()
		
		val coverUrl = doc.selectFirst(".manga-cover img, .cover img")?.let { img ->
			img.attr("src").ifEmpty { img.attr("data-src") }
		}?.let { src ->
			if (src.startsWith("http")) src else "https://$domain$src"
		}
		
		val status = doc.selectFirst(".status, .manga-status")?.text()?.trim()
		val author = doc.selectFirst(".author, .manga-author")?.text()?.trim()
		
		val tags = doc.select(".genre, .tag, .manga-genre").mapNotNull { element ->
			val tagText = element.text().trim()
			if (tagText.isNotEmpty()) {
				MangaTag(
					title = tagText,
					key = tagText.lowercase(),
					source = source,
				)
			} else null
		}.toSet<MangaTag>()
		
		val chapters = parseHtmlChapters(doc, mangaId)
		
		return Manga(
			id = generateUid(mangaId),
			title = title,
			altTitle = null,
			url = mangaId,
			publicUrl = url,
			rating = RATING_UNKNOWN,
			isNsfw = false,
			coverUrl = coverUrl,
			tags = tags,
			state = parseState(status),
			author = author,
			description = description,
			chapters = chapters,
			source = source,
		)
	}

	private fun parseHtmlChapters(doc: org.jsoup.nodes.Document, mangaId: String): List<MangaChapter> {
		val chapterElements = doc.select(".chapter, .chapter-item, .episode")
		val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
		
		return chapterElements.mapIndexedNotNull { index, element ->
			try {
				val titleElement = element.selectFirst("a, .chapter-title")
				val title = titleElement?.text()?.trim()
				val chapterUrl = titleElement?.attr("href") ?: return@mapIndexedNotNull null
				
				val numberText = title?.let { extractChapterNumber(it) } ?: (index + 1).toFloat()
				
				val dateElement = element.selectFirst(".date, .chapter-date")
				val dateText = dateElement?.text()?.trim()
				val uploadDate = dateText?.let { dateFormat.tryParse(it) }
				
				MangaChapter(
					id = generateUid(chapterUrl),
					title = title,
					number = numberText,
					volume = 0,
					url = chapterUrl.removePrefix("/"),
					scanlator = null,
					uploadDate = uploadDate ?: System.currentTimeMillis(),
					branch = null,
					source = source,
				)
			} catch (e: Exception) {
				null
			}
		}.reversed() // Usually chapters are in reverse order
	}

	private fun extractChapterNumber(title: String): Float {
		val regex = Regex("""(\d+(?:\.\d+)?)""")
		return regex.find(title)?.value?.toFloatOrNull() ?: 0f
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = if (chapter.url.startsWith("http")) chapter.url else "https://$domain/${chapter.url}"
		
		return try {
			// Try API first
			val json = webClient.httpGet("https://api.$domain/chapter/${chapter.url}").parseJson()
			val pages = json.optJSONArray("pages") ?: json.optJSONArray("images") ?: JSONArray()
			
			List(pages.length()) { i ->
				val pageUrl = pages.getString(i)
				val fullUrl = if (pageUrl.startsWith("http")) pageUrl else "https://$domain$pageUrl"
				
				MangaPage(
					id = generateUid(fullUrl),
					url = fullUrl,
					preview = null,
					source = source,
				)
			}
		} catch (e: Exception) {
			// Fallback to HTML parsing
			parseHtmlPages(chapterUrl)
		}
	}

	private suspend fun parseHtmlPages(chapterUrl: String): List<MangaPage> {
		val doc = webClient.httpGet(chapterUrl).parseHtml()
		val pageElements = doc.select("img.page, .page img, .chapter-image img, img[data-src*='chapter']")
		
		return pageElements.mapIndexedNotNull { index, img ->
			val src = img.attr("src").ifEmpty { img.attr("data-src") }
			if (src.isNotEmpty()) {
				val fullUrl = if (src.startsWith("http")) src else "https://$domain$src"
				MangaPage(
					id = generateUid(fullUrl),
					url = fullUrl,
					preview = null,
					source = source,
				)
			} else null
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		return try {
			val json = webClient.httpGet("https://api.$domain/genres").parseJson()
			val tags = json.optJSONArray("data") ?: json.optJSONArray("genres") ?: JSONArray()
			tags.mapJSONToSet { jo ->
				MangaTag(
					title = jo.optString("name", "").ifEmpty { jo.optString("title", "") },
					key = jo.optString("id", "").ifEmpty { jo.optString("slug", "") },
					source = source,
				)
			}
		} catch (e: Exception) {
			// Fallback tags in Arabic
			setOf(
				MangaTag("أكشن", "action", source),
				MangaTag("مغامرة", "adventure", source),
				MangaTag("كوميديا", "comedy", source),
				MangaTag("دراما", "drama", source),
				MangaTag("خيال", "fantasy", source),
				MangaTag("رعب", "horror", source),
				MangaTag("رومانسي", "romance", source),
				MangaTag("خيال علمي", "sci-fi", source),
				MangaTag("مدرسي", "school", source),
				MangaTag("شريحة من الحياة", "slice-of-life", source),
				MangaTag("خارق للطبيعة", "supernatural", source),
			)
		}
	}

	private fun JSONObject.fetchManga(chapters: List<MangaChapter>?): Manga {
		val id = optString("id", "").ifEmpty { optString("slug", "") }
		val title = optString("title", "").ifEmpty { optString("name", "") }
		val description = optString("description", "").ifEmpty { optString("summary", "") }
		val coverUrl = optString("cover", "").ifEmpty { optString("image", "") }
		val status = optString("status", "")
		val author = optString("author", "")
		
		val tagsArray = optJSONArray("genres") ?: optJSONArray("tags") ?: JSONArray()
		val tags = tagsArray.mapJSONToSet { tag ->
			MangaTag(
				title = tag.optString("name", "").ifEmpty { tag.optString("title", "") },
				key = tag.optString("id", "").ifEmpty { tag.optString("slug", "") },
				source = source,
			)
		}
		
		return Manga(
			id = generateUid(id),
			title = title.ifEmpty { "عنوان غير معروف" },
			altTitle = null,
			url = id,
			publicUrl = "https://$domain/manga/$id",
			rating = RATING_UNKNOWN,
			isNsfw = false,
			coverUrl = if (coverUrl.startsWith("http")) coverUrl else "https://$domain$coverUrl",
			tags = tags,
			state = parseState(status),
			author = if (author.isNotEmpty()) author else null,
			description = description.ifEmpty { null },
			chapters = chapters,
			source = source,
		)
	}

	private fun parseState(status: String?): MangaState? {
		return when (status?.lowercase()) {
			"مستمر", "ongoing", "publishing" -> MangaState.ONGOING
			"مكتمل", "completed", "finished" -> MangaState.FINISHED
			"متوقف", "hiatus", "paused" -> MangaState.PAUSED
			"ملغي", "cancelled", "abandoned" -> MangaState.ABANDONED
			else -> null
		}
	}

	private suspend fun loadChapters(mangaId: String): List<JSONObject> {
		return try {
			val json = webClient.httpGet("https://api.$domain/manga/$mangaId/chapters").parseJson()
			val chapters = json.optJSONArray("data") ?: json.optJSONArray("chapters") ?: JSONArray()
			chapters.asTypedList<JSONObject>()
		} catch (e: Exception) {
			emptyList()
		}
	}

	private fun mapChapters(list: List<JSONObject>): List<MangaChapter> {
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
		val chaptersBuilder = ChaptersListBuilder(list.size)
		
		for (jo in list) {
			val id = jo.optString("id", "")
			val title = jo.optString("title", "").ifEmpty { jo.optString("name", "") }
			val number = jo.optDouble("number", 0.0).toFloat()
			val volume = jo.optInt("volume", 0)
			val dateString = jo.optString("created_at", "").ifEmpty { jo.optString("date", "") }
			val uploadDate = dateFormat.tryParse(dateString)
			
			val chapter = MangaChapter(
				id = generateUid(id),
				title = title.ifEmpty { "الفصل $number" },
				number = number,
				volume = volume,
				url = id,
				scanlator = jo.optString("scanlator", "").ifEmpty { null },
				uploadDate = uploadDate ?: System.currentTimeMillis(),
				branch = null,
				source = source,
			)
			chaptersBuilder.add(chapter)
		}
		
		return chaptersBuilder.toList()
	}
}
