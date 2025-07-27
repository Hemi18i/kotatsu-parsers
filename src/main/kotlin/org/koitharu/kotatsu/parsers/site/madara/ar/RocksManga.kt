package org.koitharu.kotatsu.parsers.site.madara.ar

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("ROCKSMANGA", "RocksManga", "ar")
internal class RocksManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.ROCKSMANGA, "rockscans.org") {

	override val configKeyDomain = ConfigKey.Domain("rockscans.org")
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)
	
	// تحديث المحددات للموقع
	override val selectChapter = "ul.main li.wp-manga-chapter, .listing-chapters_wrap .wp-manga-chapter"
	override val datePattern = "d MMMM yyyy"
	override val selectDate = ".chapter-release-date i, .chapter-date"
	override val selectBodyPage = "div.reading-content, .wp-manga-chapter-img, .page-image, .wp-manga-chapter-img img"
	override val selectPage = "img"
	override val selectDesc = ".summary__content, .post-content_item .summary, .description, .summary"
	override val selectState = ".post-status .post-content_item, .summary-heading:contains(الحالة) + .summary-content"
	override val selectAlt = ".post-content_item .summary-heading:contains(البديل) + .summary-content"
	override val selectArtist = ".artist-content a, .post-content_item .summary-heading:contains(الرسام) + .summary-content a"
	override val selectAuthor = ".author-content a, .post-content_item .summary-heading:contains(المؤلف) + .summary-content a"
	override val selectTag = ".genres-content a, .post-content_item .summary-heading:contains(النوع) + .summary-content a"

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	// تحسين قائمة المانجا
	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			when {
				!filter.query.isNullOrEmpty() -> {
					append("/?s=")
					append(filter.query.urlEncoded())
					append("&post_type=wp-manga")
					if (page > 1) {
						append("&paged=")
						append(page)
					}
				}
				filter.tags.isNotEmpty() -> {
					val tag = filter.tags.oneOrThrowIfMany()
					append("/manga-genre/")
					append(tag?.key.orEmpty())
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
							append("/manga/")
							if (page > 1) {
								append("page/")
								append(page)
								append("/")
							}
							append("?m_orderby=views")
						}
						SortOrder.UPDATED -> {
							append("/manga/")
							if (page > 1) {
								append("page/")
								append(page)
								append("/")
							}
							append("?m_orderby=latest")
						}
						else -> {
							append("/manga/")
							if (page > 1) {
								append("page/")
								append(page)
								append("/")
							}
						}
					}
				}
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		
		// محاولة إيجاد المانجا بطرق مختلفة
		return doc.select(".page-item-detail, .post-title, .c-tabs-item__content, .manga-item").mapNotNull { div ->
			try {
				val a = div.selectFirst("a") ?: div.selectFirst("h3 a") ?: div.selectFirst(".post-title a")
				val href = a?.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
				
				val img = div.selectFirst("img")
				val coverUrl = img?.src()?.takeIf { it.isNotBlank() }
				
				val title = a.text().takeIf { it.isNotBlank() } 
					?: img?.attr("alt")?.takeIf { it.isNotBlank() }
					?: div.selectFirst(".post-title")?.text()
					?: "بدون عنوان"

				Manga(
					id = generateUid(href),
					title = title.trim(),
					altTitles = emptySet(),
					url = href,
					publicUrl = href.toAbsoluteUrl(domain),
					rating = RATING_UNKNOWN,
					contentRating = null,
					coverUrl = coverUrl,
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					source = source,
				)
			} catch (e: Exception) {
				null
			}
		}
	}

	// تحسين تحميل الفصول
	override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		
		// البحث عن الفصول في أماكن مختلفة
		val chapterElements = document.select(selectChapter).ifEmpty {
			document.select(".wp-manga-chapter, .chapter-item, .listing-chapters_wrap li, .version-chap li")
		}.ifEmpty {
			document.select("li:has(a[href*=chapter]), .chapter-li")
		}
		
		if (chapterElements.isEmpty()) {
			// محاولة البحث في قائمة الفصول عبر AJAX
			return loadChaptersFromAjax(mangaUrl, document)
		}

		return chapterElements.mapChapters(reversed = true) { i, element ->
			val a = element.selectFirst("a") ?: element.takeIf { it.tagName() == "a" }
			val href = a?.attrAsRelativeUrlOrNull("href") ?: element.parseFailed("رابط الفصل مفقود")
			
			// إضافة stylePage إذا لم يكن موجوداً
			val link = if (href.contains("?style=") || stylePage.isEmpty()) {
				href
			} else {
				href + stylePage
			}
			
			// البحث عن التاريخ
			val dateText = element.selectFirst(".chapter-release-date")?.text()
				?: element.selectFirst(selectDate)?.text()
				?: element.selectFirst("span.chapter-date")?.text()
				?: a?.selectFirst("span")?.text()
			
			// البحث عن اسم الفصل
			val chapterTitle = a?.text()?.trim()?.takeIf { it.isNotBlank() }
				?: element.selectFirst(".chapter-title")?.text()
				?: "الفصل ${i + 1}"

			MangaChapter(
				id = generateUid(href),
				url = link,
				title = chapterTitle,
				number = i + 1f,
				volume = 0,
				branch = null,
				uploadDate = parseChapterDate(dateFormat, dateText),
				scanlator = null,
				source = source,
			)
		}
	}

	// تحميل الفصول من AJAX كنسخة احتياطية
	private suspend fun loadChaptersFromAjax(mangaUrl: String, document: Document): List<MangaChapter> {
		return try {
			val postId = document.selectFirst("#manga-chapters-holder")?.attr("data-id")
				?: document.html().substringAfter("\"manga_id\":\"").substringBefore("\"").takeIf { it.isNotBlank() }
				?: return emptyList()

			val ajaxUrl = "https://$domain/wp-admin/admin-ajax.php"
			val response = webClient.httpPost(ajaxUrl, mapOf(
				"action" to "manga_get_chapters",
				"manga" to postId
			))

			val ajaxDoc = response.parseHtml()
			ajaxDoc.select(".wp-manga-chapter").mapChapters(reversed = true) { i, element ->
				val a = element.selectFirst("a") ?: element.parseFailed("رابط الفصل مفقود")
				val href = a.attrAsRelativeUrlOrNull("href") ?: element.parseFailed("رابط الفصل مفقود")
				
				MangaChapter(
					id = generateUid(href),
					url = href + stylePage,
					title = a.text().trim().takeIf { it.isNotBlank() } ?: "الفصل ${i + 1}",
					number = i + 1f,
					volume = 0,
					branch = null,
					uploadDate = 0,
					scanlator = null,
					source = source,
				)
			}
		} catch (e: Exception) {
			emptyList()
		}
	}

	// تحسين تحميل الصفحات
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		
		// البحث عن الصور في أماكن مختلفة
		val images = doc.select(selectBodyPage).select(selectPage).ifEmpty {
			doc.select(".wp-manga-chapter-img img, .reading-content img, .page-image img")
		}.ifEmpty {
			doc.select("div.text-center img, .entry-content img[src*=wp-content]")
		}

		return images.mapNotNull { img ->
			val src = img.src()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
			val url = src.toRelativeUrl(domain)
			
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	// تحسين استخراج تفاصيل المانجا
	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		
		val root = doc.body()
		
		// استخراج الوصف
		val description = root.selectFirst(selectDesc)?.html()
			?: root.selectFirst(".summary")?.html()
			?: root.selectFirst(".description")?.html()
		
		// استخراج التاجات
		val tags = root.select(selectTag).mapToSet { a ->
			MangaTag(
				key = a.attr("href").substringAfterLast('/').substringBefore('?'),
				title = a.text().trim(),
				source = source,
			)
		}
		
		// استخراج المؤلف
		val authors = root.select(selectAuthor).mapToSet { a ->
			MangaTag(
				key = a.attr("href").substringAfterLast('/').substringBefore('?'),
				title = a.text().trim(),
				source = source,
			)
		}
		
		// استخراج الحالة
		val stateText = root.selectFirst(selectState)?.text()?.lowercase()
		val state = when {
			stateText?.contains("مستمر") == true || stateText?.contains("ongoing") == true -> MangaState.ONGOING
			stateText?.contains("مكتمل") == true || stateText?.contains("completed") == true -> MangaState.FINISHED
			stateText?.contains("متوقف") == true || stateText?.contains("dropped") == true -> MangaState.ABANDONED
			else -> null
		}

		return manga.copy(
			description = description,
			tags = tags,
			authors = authors,
			state = state,
			chapters = loadChapters(manga.url, doc),
		)
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = emptySet(), // سيتم تحديثها لاحقاً
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.ABANDONED),
			availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHUA, ContentType.MANHWA),
		)
	}
}
