package org.koitharu.kotatsu.parsers.site.mmrcms.ar

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mmrcms.MmrcmsParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("ONMA", "Onma", "ar")
internal class Onma(context: MangaLoaderContext) :
	MmrcmsParser(context, MangaParserSource.ONMA, "onma.top") {

	override val sourceLocale: Locale = Locale.ENGLISH
	override val selectState = "h3:contains(الحالة) .text"
	override val selectAlt = "h3:contains(أسماء أخرى) .text"
	override val selectAut = "h3:contains(المؤلف) .text"
	override val selectTag = "h3:contains(التصنيفات) .text"


	override fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("div.chapter-container").map { div ->
			val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(div.host ?: domain),
				coverUrl = div.selectFirst("img")?.src(),
				title = div.selectFirstOrThrow("h5.media-heading").text().orEmpty(),
				altTitles = emptySet(),
				rating = div.selectFirstOrThrow("span").ownText().toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val body = doc.body().selectFirstOrThrow("div.panel-body")
		val chaptersDeferred = async { getChapters(doc) }
		val desc = doc.selectFirst(selectDesc)?.text().orEmpty()
		val stateDiv = body.selectFirst(selectState)
		val state = stateDiv?.let {
			when (it.text()) {
				in ongoing -> MangaState.ONGOING
				in finished -> MangaState.FINISHED
				else -> null
			}
		}
		val alt = doc.body().selectFirst(selectAlt)?.textOrNull()
		val author = doc.body().selectFirst(selectAut)?.textOrNull()
		val tags = doc.body().selectFirst(selectTag)?.select("a") ?: emptySet()
		manga.copy(
			tags = tags.mapToSet { a ->
				MangaTag(
					key = a.attr("href").removeSuffix('/').substringAfterLast('/'),
					title = a.text().toTitleCase(sourceLocale),
					source = source,
				)
			},
			authors = setOfNotNull(author),
			description = desc,
			altTitles = setOfNotNull(alt),
			state = state,
			chapters = chaptersDeferred.await(),
		)
	}
}
