package org.koitharu.kotatsu.parsers.site.madara.ar

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat

@MangaSourceParser("ROCKSMANGA", "RocksManga", "ar")
internal class RocksManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.ROCKSMANGA, "rockscans.org") {

	override val configKeyDomain = ConfigKey.Domain("rockscans.org")
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)
	
	// المحددات الأساسية
	override val selectChapter = "ul.main li.wp-manga-chapter, .listing-chapters_wrap .wp-manga-chapter, ul#chapter-list li.chapter-item"
	override val datePattern = "d MMMM yyyy"
	override val selectDate = ".chapter-release-date i, .chapter-date, .ch-post-time"
	override val selectBodyPage = "div.reading-content, .wp-manga-chapter-img, .page-image"
	override val selectPage = "img"
	override val selectDesc = ".summary__content, .post-content_item .summary, .description, .story"

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	// تحميل الفصول مع تحسينات
	override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		
		// البحث عن الفصول بطرق متعددة
		val chapterElements = document.select(selectChapter).ifEmpty {
			document.select(".wp-manga-chapter, .chapter-item, .version-chap")
		}.ifEmpty {
			document.select("li:has(a[href*=chapter]), a[href*=chapter]").filter { 
				it.selectFirst("a")?.attr("href")?.contains("chapter") == true 
			}
		}
		
		if (chapterElements.isEmpty()) {
			// محاولة AJAX كبديل
			return loadChaptersViaAjax(mangaUrl, document)
		}

		return chapterElements.mapChapters(reversed = true) { i, element ->
			val a = element.selectFirst("a") ?: element.takeIf { it.tagName() == "a" }
			val href = a?.attrAsRelativeUrlOrNull("href") ?: element.parseFailed("رابط الفصل مفقود")
			
			// التأكد من وجود stylePage
			val link = if (href.contains("?style=") || stylePage.isBlank()) {
				href
			} else {
				href + stylePage
			}
			
			// البحث عن التاريخ
			val dateText = element.selectFirst("a.c-new-tag")?.attr("title")
				?: element.selectFirst(selectDate)?.text()
				?: element.selectFirst(".date, span.date")?.text()
			
			// البحث عن اسم الفصل
			val chapterName = a?.selectFirst(".ch-title")?.text()
				?: a?.ownText()?.trim()?.takeIf { it.isNotBlank() }
				?: a?.text()?.trim()?.takeIf { it.isNotBlank() }
				?: "الفصل ${i + 1}"

			MangaChapter(
				id = generateUid(href),
				url = link,
				title = chapterName,
				number = i + 1f,
				volume = 0,
				branch = null,
				uploadDate = parseChapterDate(dateFormat, dateText),
				scanlator = null,
				source = source,
			)
		}
	}

	// تحميل الفصول عبر AJAX
	private suspend fun loadChaptersViaAjax(mangaUrl: String, document: Document): List<MangaChapter> {
		return try {
			// البحث عن معرف المانجا
			val postId = document.selectFirst("#manga-chapters-holder")?.attr("data-id")
				?: document.selectFirst("input[name=wp-manga-current-id]")?.attr("value")
				?: extractPostIdFromScript(document)
				?: return emptyList()

			val ajaxUrl = "https://$domain/wp-admin/admin-ajax.php"
			val response = webClient.httpPost(ajaxUrl, mapOf(
				"action" to "manga_get_chapters",
				"manga" to postId
			))

			val ajaxDoc = response.parseHtml()
			val chapters = ajaxDoc.select(".wp-manga-chapter, .chapter-item")
			
			chapters.mapChapters(reversed = true) { i, element ->
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

	// استخراج معرف المانجا من الجافا سكريبت
	private fun extractPostIdFromScript(document: Document): String? {
		val scripts = document.select("script:not([src])")
		for (script in scripts) {
			val content = script.html()
			if (content.contains("manga_id") || content.contains("post_id")) {
				val regex = Regex("""["'](?:manga_id|post_id)["']\s*:\s*["']?(\d+)["']?""")
				val match = regex.find(content)
				if (match != null) {
					return match.groupValues[1]
				}
			}
		}
		return null
	}

	// تحسين تحميل الصفحات
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		
		// البحث عن الصور
		val images = doc.select(selectBodyPage).select(selectPage).ifEmpty {
			doc.select(".wp-manga-chapter-img img, .reading-content img")
		}.ifEmpty {
			doc.select("div.text-center img, .entry-content img")
		}.filter { img ->
			val src = img.attr("src")
			src.isNotBlank() && (src.contains("wp-content") || src.contains("uploads") || src.startsWith("http"))
		}

		return images.mapIndexedNotNull { index, img ->
			val src = img.src()?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
			val url = src.toRelativeUrl(domain)
			
			MangaPage(
				id = generateUid("$url-$index"),
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
			?: root.selectFirst(".summary__content p")?.html()
			?: root.selectFirst(".post-content_item .summary")?.html()
		
		// استخراج التاجات
		val tags = root.select(".genres-content a, .post-content_item .summary-heading:contains(النوع) + .summary-content a").mapToSet { a ->
			MangaTag(
				key = a.attr("href").substringAfterLast('/').substringBefore('?'),
				title = a.text().trim(),
				source = source,
			)
		}
		
		// استخراج المؤلفين
		val authors = root.select(".author-content a, .post-content_item .summary-heading:contains(المؤلف) + .summary-content a").mapToSet { a ->
			MangaTag(
				key = a.attr("href").substringAfterLast('/').substringBefore('?'),
				title = a.text().trim(),
				source = source,
			)
		}
		
		// استخراج الحالة
		val stateText = root.selectFirst(".post-status .post-content_item, .summary-heading:contains(الحالة) + .summary-content")?.text()?.lowercase(sourceLocale)
		val state = when {
			stateText?.contains("مستمر") == true || stateText?.contains("ongoing") == true -> MangaState.ONGOING
			stateText?.contains("مكتمل") == true || stateText?.contains("completed") == true -> MangaState.FINISHED
			stateText?.contains("متوقف") == true || stateText?.contains("dropped") == true -> MangaState.ABANDONED
			else -> null
		}

		// استخراج العناوين البديلة
		val altTitles = root.selectFirst(".post-content_item .summary-heading:contains(البديل) + .summary-content")
			?.text()
			?.split(",", ";")
			?.mapNotNull { it.trim().takeIf { title -> title.isNotBlank() } }
			?.toSet()
			?: emptySet()

		return manga.copy(
			description = description,
			altTitles = altTitles,
			tags = tags,
			authors = authors,
			state = state,
			chapters = loadChapters(manga.url, doc),
		)
	}
}
