package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koytharu.kotatsu.parsers.MangaLoaderContext
import org.koytharu.kotatsu.parsers.MangaSourceParser
import org.koytharu.kotatsu.parsers.model.MangaParserSource
import org.koytharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("MANGATIMETEST", "MangaTime Test", "ar")
internal class MangaTimeTest(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGATIMETEST, "mangatime.org")
