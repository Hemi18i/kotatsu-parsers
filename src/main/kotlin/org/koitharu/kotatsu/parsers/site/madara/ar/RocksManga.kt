package org.koitharu.kotatsu.parsers.site.ar

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.LegacyPagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("ROCKSCANS", "RockScans", "ar")
internal class RockScans(context: MangaLoaderContext) :
	LegacyPagedMangaParser(context, MangaParserSource.ROCKSCANS, 12) {

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL
	)
	
	override val configKeyDomain = ConfigKey.Domain("rockscans.org")
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isGenreSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED
		),
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			
			when {
				!filter.query.isNullOrEmpty() -> {
					append("/?s=")
					append(filter.query.urlEncoded())
					if (page > 1) {
						append("&paged=")
						append(page)
					}
				}
				filter.tags.isNotEmpty() -> {
					val tag = filter.tags.first()
					append("/genre/")
					append(tag.key)
					append("/")
					if (page > 1) {
						append("page/")
						append(page)
						append("/")
					}
				}
				else -> {
					when (order) {
						SortOrder.POPULARITY -> {
							append("/most-popular/")
						}
						SortOrder.ALPHABETICAL -> {
							append("/manga-list/")
						}
						else -> {
							append("/")
						}
					}
					if (page > 1) {
						append("page/")
						append(page)
						append("/")
					}
				}
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		
		return doc.select("article, .manga-item, .post").mapNotNull { element ->
			try {
				val linkElement = element.selectFirst("a[href*=manga]") ?: return@mapNotNull null
				val href = linkElement.attrAsAbsoluteUrl("href")
				
				// استخراج العنوان
				val title = linkElement.attr("title").ifEmpty {
					element.selectFirst("h2, h3, .title")?.text() ?: 
					linkElement.text().ifEmpty { "Unknown Title" }
				}
				
				// استخراج صورة الغلاف
				val imgElement = element.selectFirst("img")
				val coverUrl = imgElement?.src()?.toAbsoluteUrl(domain) ?: ""
				
				// استخراج الحالة من النص
				val statusText = element.text()
				val state = when {
					statusText.contains("مستمر") -> MangaState.ONGOING
					statusText.contains("مكتمل") -> MangaState.FINISHED
					statusText.contains("متوقف") -> MangaState.PAUSED
					else -> null
				}

				Manga(
					id = generateUid(href),
					title = title,
					altTitles = emptySet(),
					url = href,
					publicUrl = href,
					rating = RATING_UNKNOWN,
					contentRating = ContentRating.SAFE,
					coverUrl = coverUrl,
					tags = emptySet(),
					state = state,
					authors = emptySet(),
					source = source,
				)
			} catch (e: Exception) {
				null
			}
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		return try {
			val doc = webClient.httpGet("https://$domain/manga-genre/").parseHtml()
			doc.select(".genre-list a, .wp-block-tag-cloud a").mapToSet { tagElement ->
				MangaTag(
					key = tagElement.attr("href").substringAfterLast("/").removeSuffix("/"),
					title = tagElement.text(),
					source = source,
				)
			}
		} catch (e: Exception) {
			// تاجز افتراضية في حالة الفشل
			setOf(
				MangaTag("action", "أكشن", source),
				MangaTag("adventure", "مغامرة", source),
				MangaTag("comedy", "كوميدي", source),
				MangaTag("drama", "دراما", source),
				MangaTag("fantasy", "خيال", source),
				MangaTag("romance", "رومانسي", source),
				MangaTag("horror", "رعب", source),
				MangaTag("mystery", "غموض", source),
				MangaTag("school", "مدرسة", source),
				MangaTag("shounen", "شونين", source),
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val chaptersDeferred = async { loadChapters(manga.url) }
		val doc = webClient.httpGet(manga.url).parseHtml()
		
		// استخراج الوصف
		val description = doc.selectFirst(".summary, .entry-content, .description")?.html() ?: ""
		
		// استخراج التاجز
		val tags = doc.select(".genres a, .genre a, .wp-block-tag-cloud a").mapToSet { tagElement ->
			MangaTag(
				key = tagElement.attr("href").substringAfterLast("/").removeSuffix("/"),
				title = tagElement.text(),
				source = source,
			)
		}
		
		// استخراج الحالة
		val statusElement = doc.selectFirst(".status, .manga-status")
		val state = when (statusElement?.text()?.lowercase()) {
			"ongoing", "مستمر" -> MangaState.ONGOING
			"completed", "مكتمل" -> MangaState.FINISHED
			"paused", "متوقف" -> MangaState.PAUSED
			else -> null
		}
		
		// استخراج المؤلفين
		val authors = doc.select(".author a, .manga-author a").mapToSet { authorElement ->
			MangaAuthor(
				name = authorElement.text(),
				type = MangaAuthor.Type.UNKNOWN,
			)
		}

		manga.copy(
			description = description,
			tags = tags,
			state = state,
			authors = authors,
			chapters = chaptersDeferred.await(),
		)
	}

	private val dateFormat = SimpleDateFormat("yyyy-MM-dd", sourceLocale)

	private suspend fun loadChapters(mangaUrl: String): List<MangaChapter> {
		val doc = webClient.httpGet(mangaUrl).parseHtml()
		
		// البحث عن قائمة الفصول
		val chapterElements = doc.select(
			".chapter-list li a, .wp-manga-chapter a, .version-chap a, .chapter a"
		).ifEmpty {
			doc.select("a[href*=chapter]")
		}
		
		return chapterElements.mapIndexed { index, element ->
			val href = element.attrAsAbsoluteUrl("href")
			val title = element.text().ifEmpty { "الفصل ${index + 1}" }
			
			// محاولة استخراج رقم الفصل من النص أو الرابط
			val chapterNumber = extractChapterNumber(title, href, index)
			
			// محاولة استخراج تاريخ الرفع
			val dateElement = element.parent()?.selectFirst(".chapter-date, .date")
			val uploadDate = dateElement?.text()?.let { dateText ->
				dateFormat.tryParse(dateText) ?: 0
			} ?: 0

			MangaChapter(
				id = generateUid(href),
				url = href,
				title = title,
				number = chapterNumber,
				volume = 0,
				branch = null,
				uploadDate = uploadDate,
				scanlator = null,
				source = source,
			)
		}.reversed() // عكس الترتيب للحصول على الفصول من الأقدم للأحدث
	}

	private fun extractChapterNumber(title: String, url: String, fallbackIndex: Int): Float {
		// محاولة استخراج الرقم من العنوان
		val titleRegex = Regex("""(?:chapter|ch|فصل|الفصل)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
		titleRegex.find(title)?.groupValues?.get(1)?.toFloatOrNull()?.let { return it }
		
		// محاولة استخراج من الرابط
		val urlRegex = Regex("""chapter-?(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
		urlRegex.find(url)?.groupValues?.get(1)?.toFloatOrNull()?.let { return it }
		
		// العودة للفهرس كرقم افتراضي
		return (fallbackIndex + 1).toFloat()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url).parseHtml()
		
		// البحث عن صور الصفحات بطرق مختلفة
		val imageElements = doc.select(
			".page-image img, .wp-manga-chapter-img img, .reading-content img, .chapter-content img"
		).ifEmpty {
			doc.select("img[src*=chapter], img[src*=page]")
		}.ifEmpty {
			doc.select(".entry-content img")
		}
		
		return imageElements.mapNotNull { img ->
			val imageUrl = img.src()?.toAbsoluteUrl(domain)
			if (imageUrl.isNullOrEmpty()) return@mapNotNull null
			
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}
}
