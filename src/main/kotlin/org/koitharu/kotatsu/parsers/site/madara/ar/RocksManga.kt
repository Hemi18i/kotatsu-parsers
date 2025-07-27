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
	
	// إعدادات أساسية للموقع
	override val datePattern = "d MMMM yyyy"
	override val listUrl = ""
	override val tagPrefix = "manga-genre/"
	
	// selectors محدثة بناءً على الكود القديم والموقع الحالي
	override val selectChapter = "ul#chapter-list li.chapter-item, li.wp-manga-chapter"
	override val selectDate = ".ch-post-time, .chapter-release-date"
	override val selectBodyPage = "div.reading-content, .text-left"
	override val selectPage = "img"
	override val selectDesc = ".story, .summary__content"
	
	// تحسين loadChapters للتعامل مع تنسيق الموقع الجديد
	override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		
		// جرب أولاً النمط الجديد ثم القديم
		var chapterElements = document.select("li.wp-manga-chapter")
		if (chapterElements.isEmpty()) {
			chapterElements = document.select("ul#chapter-list li.chapter-item")
		}
		
		return chapterElements.mapChapters(reversed = true) { i, li ->
			val a = li.selectFirst("a")
			val href = a?.attrAsRelativeUrlOrNull("href") ?: li.parseFailed("Link is missing")
			val link = href + stylePage
			
			// البحث عن التاريخ في أماكن مختلفة
			val dateText = li.selectFirst("a.c-new-tag")?.attr("title") 
				?: li.selectFirst(selectDate)?.text()
				?: li.selectFirst(".chapter-release-date")?.text()
			
			// البحث عن العنوان في أماكن مختلفة
			val name = a.selectFirst(".ch-title")?.text() 
				?: a.selectFirst("span")?.text()
				?: a.ownText()
			
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
