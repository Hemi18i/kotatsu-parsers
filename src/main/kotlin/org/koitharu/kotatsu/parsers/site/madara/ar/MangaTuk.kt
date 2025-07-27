package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("MANGATUK", "MangaTuk", "ar")
internal class MangaTuk(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGATUK, "mangatuk.com") {
	
	override val datePattern = "d MMMM، yyyy"
}
