package org.koitharu.kotatsu.parsers.site.madara.ar

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrlOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.parseFailed
import java.text.SimpleDateFormat

@MangaSourceParser("ROCKSMANGA", "RocksManga", "ar")
internal class RocksManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.ROCKSMANGA, "rockscans.org") {

	override val selectChapter = "ul#chapter-list li.chapter-item, .wp-manga-chapter, .version-chap"
	override val datePattern = "d MMMM yyyy"
	override val selectDate = ".ch-post-time, .chapter-date"
	override val selectBodyPage = "div.reading-content, .wp-manga-chapter-img, .page-image"
	override val selectPage = "img"
	override val selectDesc = ".story, .summary, .description"

	override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		
		// محاولة البحث عن الفصول بطرق مختلفة
		val chapterElements = document.select(selectChapter).ifEmpty {
			document.select(".wp-manga-chapter, .chapter-item, a[href*=chapter]")
		}
		
		return chapterElements.mapChapters(reversed = true) { i, element ->
			// البحث عن الرابط
			val a = element.selectFirst("a") ?: element.takeIf { it.tagName() == "a" }
			val href = a?.attrAsRelativeUrlOrNull("href") ?: element.parseFailed("Link is missing")
			val link = href + stylePage
			
			// البحث عن التاريخ
			val dateText = element.selectFirst("a.c-new-tag")?.attr("title") 
				?: element.selectFirst(selectDate)?.text()
				?: element.selectFirst(".date")?.text()
			
			// البحث عن اسم الفصل
			val name = a?.selectFirst(".ch-title")?.text() 
				?: a?.ownText() 
				?: a?.text() 
				?: "الفصل ${i + 1}"

			MangaChapter(
				id = generateUid(href),
				url = link,
				title = name,
				number = i + 1f,
				volume = 0,
				branch = null,
				uploadDate = parseChapterDate(
					dateFormat,
					dateText,
				),
				scanlator = null,
				source = source,
			)
		}
	}
}
