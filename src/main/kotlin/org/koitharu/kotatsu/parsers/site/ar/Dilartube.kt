package org.koitharu.kotatsu.parsers.site.ar

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.LegacyPagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("DILAR", "Dilar.tube", "ar")
internal class Dilar(context: MangaLoaderContext) :
	LegacyPagedMangaParser(context, MangaParserSource.DILAR, 24) {

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.NEWEST
	)
	
	override val configKeyDomain = ConfigKey.Domain("dilar.tube")
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.ABANDONED,
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
			append("/api/mangas?")
			
			// إضافة البحث
			if (!filter.query.isNullOrEmpty()) {
				append("search=")
				append(filter.query.urlEncoded())
				append("&")
			}
			
			// إضافة التاجات
			if (filter.tags.isNotEmpty()) {
				append("genres=")
				append(filter.tags.joinToString(",") { it.key })
				append("&")
			}
			
			// إضافة الحالة
			if (filter.states.isNotEmpty()) {
				val state = filter.states.oneOrThrowIfMany()
				append("status=")
				append(
					when (state) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						MangaState.ABANDONED -> "dropped"
						MangaState.PAUSED -> "hiatus"
						else -> "ongoing"
					}
				)
				append("&")
			}
			
			// إضافة نوع المحتوى - تم حذف هذا الجزء لعدم توفر contentTypes في النسخة المستخدمة
			
			// إضافة الترتيب
			append("sort=")
			append(
				when (order) {
					SortOrder.POPULARITY -> "popular"
					SortOrder.UPDATED -> "updated"
					SortOrder.NEWEST -> "created"
					SortOrder.ALPHABETICAL -> "name"
					else -> "updated"
				}
			)
			
			// إضافة رقم الصفحة
			append("&page=")
			append(page)
			append("&per_page=24")
		}

		val response = webClient.httpGet(url)
		val json = response.parseJson()
		
		// التحقق من وجود البيانات
		val mangaArray = json.optJSONArray("data") ?: json.optJSONArray("mangas") ?: return emptyList()
		
		return mangaArray.mapJSON { mangaJson ->
			val id = mangaJson.getString("id")
			val slug = mangaJson.getString("slug")
			val href = "/mangas/$slug"
			
			Manga(
				id = generateUid(href),
				title = mangaJson.getString("title"),
				altTitles = mangaJson.optJSONArray("alternative_titles")?.let { altArray ->
					val titles = mutableSetOf<String>()
					for (i in 0 until altArray.length()) {
						val title = altArray.optString(i)
						if (title.isNotBlank()) {
							titles.add(title)
						}
					}
					titles
				} ?: emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = mangaJson.optDouble("rating", -1.0).let { if (it > 0) it.toFloat() / 10f else RATING_UNKNOWN },
				contentRating = if (mangaJson.optBoolean("is_adult", false)) ContentRating.ADULT else ContentRating.SAFE,
				coverUrl = mangaJson.optString("poster")?.let { 
					if (it.startsWith("http")) it else "https://$domain/storage/$it"
				},
				tags = mangaJson.optJSONArray("genres")?.mapJSON { genreJson ->
					MangaTag(
						key = genreJson.getString("id"),
						title = genreJson.getString("name"),
						source = source,
					)
				}?.toSet() ?: emptySet(),
				state = when (mangaJson.optString("status")) {
					"ongoing" -> MangaState.ONGOING
					"completed" -> MangaState.FINISHED
					"dropped" -> MangaState.ABANDONED
					"hiatus" -> MangaState.PAUSED
					else -> null
				},
				authors = emptySet(), // تم تبسيط هذا الجزء
				source = source,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		return try {
			val response = webClient.httpGet("https://$domain/api/genres")
			val json = response.parseJson()
			val genresArray = json.optJSONArray("data") ?: json.optJSONArray("genres") ?: return emptySet()
			
			genresArray.mapJSON { genreJson ->
				MangaTag(
					key = genreJson.getString("id"),
					title = genreJson.getString("name"),
					source = source,
				)
			}.toSet()
		} catch (e: Exception) {
			// التاجات الافتراضية في حالة فشل التحميل
			setOf(
				MangaTag("1", "أكشن", source),
				MangaTag("2", "مغامرة", source),
				MangaTag("3", "كوميدي", source),
				MangaTag("4", "دراما", source),
				MangaTag("5", "خيال", source),
				MangaTag("6", "رومانسي", source),
				MangaTag("7", "خارق للطبيعة", source),
				MangaTag("8", "مدرسة", source),
				MangaTag("9", "شريحة من الحياة", source),
				MangaTag("10", "إثارة", source),
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val mangaSlug = manga.url.substringAfterLast("/")
		val chaptersDeferred = async { loadChapters(mangaSlug) }
		
		val response = webClient.httpGet("https://$domain/api/mangas/$mangaSlug")
		val json = response.parseJson()
		val mangaData = json.optJSONObject("data") ?: json
		
		manga.copy(
			description = mangaData.optString("summary").takeIf { it.isNotBlank() }
				?: mangaData.optString("description").takeIf { it.isNotBlank() },
			tags = mangaData.optJSONArray("genres")?.mapJSON { genreJson ->
				MangaTag(
					key = genreJson.getString("id"),
					title = genreJson.getString("name"),
					source = source,
				)
			}?.toSet() ?: manga.tags,
			rating = mangaData.optDouble("rating", -1.0).let { 
				if (it > 0) it.toFloat() / 10f else manga.rating 
			},
			contentRating = if (mangaData.optBoolean("is_adult", false)) 
				ContentRating.ADULT else ContentRating.SAFE,
			state = when (mangaData.optString("status")) {
				"ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				"dropped" -> MangaState.ABANDONED
				"hiatus" -> MangaState.PAUSED
				else -> manga.state
			},
			authors = emptySet(), // تم تبسيط هذا الجزء
			chapters = chaptersDeferred.await(),
		)
	}

	private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", sourceLocale)

	private suspend fun loadChapters(mangaSlug: String): List<MangaChapter> {
		return try {
			val response = webClient.httpGet("https://$domain/api/mangas/$mangaSlug/releases")
			val json = response.parseJson()
			val releasesArray = json.optJSONArray("releases") 
				?: json.optJSONArray("data") 
				?: json.optJSONArray("chapters")
				?: return emptyList()
			
			releasesArray.mapJSON { releaseJson ->
				// تخطي الفصول المدفوعة إذا كانت موجودة
				if (releaseJson.optBoolean("is_monetized", false)) {
					return@mapJSON null
				}
				
				val chapterId = releaseJson.getString("id")
				val chapterSlug = releaseJson.optString("slug")
				val href = "/mangas/$mangaSlug/releases/$chapterId"
				
				val title = releaseJson.optString("title").takeIf { it.isNotBlank() }
					?: "الفصل ${releaseJson.optString("chapter_number", chapterId)}"
				
				val chapterNumber = releaseJson.optString("chapter_number")?.toFloatOrNull()
					?: releaseJson.optInt("number", -1).takeIf { it > 0 }?.toFloat()
					?: 1f
				
				val dateString = releaseJson.optString("created_at")
					?: releaseJson.optString("published_at")
				val uploadDate = if (dateString?.isNotEmpty() == true) {
					dateFormat.tryParse(dateString)
				} else {
					0L
				}
				
				MangaChapter(
					id = generateUid(href),
					url = href,
					title = title,
					number = chapterNumber,
					volume = releaseJson.optInt("volume", 0),
					branch = null,
					uploadDate = uploadDate,
					scanlator = releaseJson.optJSONObject("team")?.optString("name"),
					source = source,
				)
			}.filterNotNull().reversed() // عكس الترتيب للحصول على الأحدث أولاً
		} catch (e: Exception) {
			emptyList()
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		return try {
			val response = webClient.httpGet(chapter.url.toAbsoluteUrl(domain))
			val json = response.parseJson()
			
			// محاولة العثور على الصور في استجابة API
			val imagesArray = json.optJSONArray("images") 
				?: json.optJSONArray("pages")
				?: json.optJSONObject("data")?.optJSONArray("images")
				?: json.optJSONObject("release")?.optJSONArray("images")
			
			if (imagesArray != null) {
				return imagesArray.mapJSON { imageJson ->
					val imageUrl = when {
						imageJson is String -> imageJson
						else -> {
							val jsonObj = imageJson as JSONObject
							jsonObj.optString("url") 
								?: jsonObj.optString("image")
								?: jsonObj.optString("src")
								?: jsonObj.toString()
						}
					}
					
					val fullImageUrl = if (imageUrl.startsWith("http")) {
						imageUrl
					} else {
						"https://$domain/storage/$imageUrl"
					}
					
					MangaPage(
						id = generateUid(fullImageUrl),
						url = fullImageUrl,
						preview = null,
						source = source,
					)
				}
			}
			
			// إذا لم نجد الصور في API، نحاول استخراجها من HTML
			val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
			
			// البحث عن الصور في JavaScript أو عناصر HTML
			val scriptElements = doc.select("script:containsData(images), script:containsData(pages)")
			for (script in scriptElements) {
				val scriptContent = script.data()
				
				// محاولة استخراج array الصور من JavaScript
				val imagesMatch = Regex("""images\s*:\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL)
					.find(scriptContent)
				
				if (imagesMatch != null) {
					try {
						val imagesJsonArray = JSONArray(imagesMatch.groupValues[1])
						return imagesJsonArray.mapJSON { imageUrl ->
							val fullUrl = if (imageUrl.toString().startsWith("http")) {
								imageUrl.toString()
							} else {
								val cleanUrl = imageUrl.toString().trim('"')
								if (cleanUrl.isNotEmpty()) {
									"https://$domain/storage/$cleanUrl"
								} else {
									null
								}
							}
							
							if (fullUrl?.isNotEmpty() == true) {
								MangaPage(
									id = generateUid(fullUrl),
									url = fullUrl,
									preview = null,
									source = source,
								)
							} else {
								null
							}
						}.filterNotNull()
					} catch (e: Exception) {
						// تجاهل الأخطاء والمحاولة مع العنصر التالي
					}
				}
			}
			
			// كبديل أخير، البحث عن الصور في HTML
			val htmlImages = doc.select("img[data-src], img[src*='/storage/']").mapNotNull { img ->
				val imageUrl = img.attr("data-src").ifEmpty { img.src() }
				if (imageUrl.isNotBlank()) {
					val fullImageUrl = imageUrl.toAbsoluteUrl(domain)
					MangaPage(
						id = generateUid(fullImageUrl),
						url = fullImageUrl,
						preview = null,
						source = source,
					)
				} else {
					null
				}
			}
			
			return htmlImages.takeIf { it.isNotEmpty() } ?: emptyList()
			
		} catch (e: Exception) {
			emptyList()
		}
	}
}
