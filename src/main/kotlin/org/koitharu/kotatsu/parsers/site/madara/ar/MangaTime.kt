package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("MANGATIME", "MangaTime", "ar")
internal class MangaTime(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGATIME, "mangatime.org") {
	
	override val datePattern = "d MMMM، yyyy"
	override val isTagsExclusionSupported = false
	override val listUrl = "manga/"
	
	// تخصيصات للمحتوى العربي والكوري المختلط
	override val selectMangaListImg = "img"
	override val selectChapterDate = "span.chapter-release-date i"
	override val selectChapterUrl = "li.wp-manga-chapter > a"
}
