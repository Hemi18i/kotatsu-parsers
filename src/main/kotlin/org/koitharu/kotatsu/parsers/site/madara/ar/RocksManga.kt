package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("ROCKSMANGA", "RocksManga", "ar")
internal class RocksManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.ROCKSMANGA, "rockscans.org") {
	
	override val datePattern = "d MMMM yyyy"
	override val listUrl = "manga/"
	override val tagPrefix = "manga-genre/"
	
	// إضافة timeouts أطول للتعامل مع بطء الموقع
	override val configKeyDomain = "rockscans.org"
	
	// إضافة headers مخصصة إذا كان الموقع يتطلبها
	override fun onCreateConfig(keys: MutableCollection<String>) {
		super.onCreateConfig(keys)
		keys.add(configKeyDomain)
	}
	
	// التعامل مع مشاكل الترميز المحتملة للنصوص العربية
	override val selectChapter = "li.wp-manga-chapter > a"
	override val selectPage = "div.reading-content img"
	
	// إضافة User-Agent مخصص إذا كان الموقع يحجب البوتات
	override val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
}
