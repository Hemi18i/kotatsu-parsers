package org.koitharu.kotatsu.parsers.site.ar

import okhttp3.HttpUrl
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.AbstractMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("DILARTUBE", "Dilar Tube")
internal class DilarTubeParser(context: MangaLoaderContext) : AbstractMangaParser(context, MangaParserSource.DILARTUBE) {

	override val configKeyDomain = ConfigKey.Domain("dilar.tube")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: EnumSet<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override suspend fun getListPage(page: Int, order: SortOrder, tag: MangaTag?): List<Manga> {
		val url = buildString {
			append("https://$domain/")
			when {
				tag != null -> append("genre/${tag.key}/")
				else -> append("manga/")
			}
			append("?page=$page")
			append("&sort=")
			append(when (order) {
				SortOrder.UPDATED -> "updated"
				SortOrder.POPULARITY -> "popular"
				SortOrder.RATING -> "rating"
				SortOrder.NEWEST -> "newest"
				SortOrder.ALPHABETICAL -> "name"
				else -> "updated"
			})
		}

		val doc = webClient.httpGet(url).parseHtml()
		val elements = doc.select(".manga-item, .manga-card, [class*='manga']")
		
		return elements.mapNotNull { element ->
			try {
				val titleElement = element.selectFirst("h3, .title, .manga-title, a")
				val title = titleElement?.text()?.trim() ?: return@mapNotNull null
				
				val linkElement = element.selectFirst("a[href*='/manga/'], a[href*='/series/']") 
					?: titleElement?.takeIf { it.tagName() == "a" }
				val mangaUrl = linkElement?.attr("href") ?: return@mapNotNull null
				
				val coverElement = element.selectFirst("img")
				val coverUrl = coverElement?.attr("src") ?: coverElement?.attr("data-src")
				
				Manga(
					id = generateUid(mangaUrl),
					title = title,
					altTitle = null,
					url = mangaUrl.removePrefix("/"),
					publicUrl = "https://$domain$mangaUrl",
					rating = RATING_UNKNOWN,
					isNsfw = false,
					coverUrl = coverUrl?.let { 
						if (it.startsWith("http")) it 
						else "https://$domain$it" 
					},
					tags = emptySet(),
					state = null,
					author = null,
					source = source,
				)
			} catch (e: Exception) {
				null
			}
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val url = "https://$domain/${manga.url}"
		val doc = webClient.httpGet(url).parseHtml()
		
		val title = doc.selectFirst("h1, .manga-title, .title")?.text()?.trim() 
			?: manga.title
		
		val description = doc.selectFirst(".description, .summary, .manga-description")
			?.text()?.trim()
		
		val coverUrl = doc.selectFirst(".manga-cover img, .cover img")?.let { img ->
			val src = img.attr("src").ifEmpty { img.attr("data-src") }
			if (src.startsWith("http")) src else "https://$domain$src"
		} ?: manga.coverUrl
		
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
		}.toSet()
		
		val chapters = parseChapters(doc, manga.url)
		
		return manga.copy(
			title = title,
			description = description,
			coverUrl = coverUrl,
			tags = tags,
			state = parseState(status),
			author = author,
			chapters = chapters,
		)
	}

	private fun parseChapters(doc: org.jsoup.nodes.Document, mangaUrl: String): List<MangaChapter> {
		val chapterElements = doc.select(".chapter, .chapter-item, .episode, [class*='chapter']")
		
		return chapterElements.mapIndexedNotNull { index, element ->
			try {
				val titleElement = element.selectFirst("a, .chapter-title")
				val title = titleElement?.text()?.trim()
				val chapterUrl = titleElement?.attr("href") ?: return@mapIndexedNotNull null
				
				val number = title?.let { extractChapterNumber(it) } ?: (index + 1).toFloat()
				
				MangaChapter(
					id = generateUid(chapterUrl),
					title = title ?: "الفصل $number",
					number = number,
					volume = 0,
					url = chapterUrl.removePrefix("/"),
					scanlator = null,
					uploadDate = System.currentTimeMillis(),
					branch = null,
					source = source,
				)
			} catch (e: Exception) {
				null
			}
		}.reversed()
	}

	private fun extractChapterNumber(title: String): Float {
		val regex = Regex("""(\d+(?:\.\d+)?)""")
		return regex.find(title)?.value?.toFloatOrNull() ?: 0f
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = "https://$domain/${chapter.url}"
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

	override suspend fun getTags(): Set<MangaTag> {
		return try {
			val doc = webClient.httpGet("https://$domain/genres").parseHtml()
			doc.select(".genre-item, .tag-item, a[href*='/genre/']").mapNotNull { element ->
				val title = element.text().trim()
				val href = element.attr("href")
				if (title.isNotEmpty() && href.isNotEmpty()) {
					val key = href.substringAfterLast("/").substringBefore("?")
					MangaTag(
						title = title,
						key = key,
						source = source,
					)
				} else null
			}.toSet()
		} catch (e: Exception) {
			setOf(
				MangaTag("أكشن", "action", source),
				MangaTag("مغامرة", "adventure", source),
				MangaTag("كوميديا", "comedy", source),
				MangaTag("دراما", "drama", source),
				MangaTag("خيال", "fantasy", source),
				MangaTag("رومانسي", "romance", source),
			)
		}
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val mangaId = link.pathSegments.findLast { it.isNotBlank() } ?: return null
		return try {
			getDetails(Manga(
				id = generateUid(mangaId),
				title = "",
				altTitle = null,
				url = mangaId,
				publicUrl = link.toString(),
				rating = RATING_UNKNOWN,
				isNsfw = false,
				coverUrl = null,
				tags = emptySet(),
				state = null,
				author = null,
				source = source,
			))
		} catch (e: Exception) {
			null
		}
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
}
