package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("MANGATIME", "MangaTime", "ar")
internal class MangaTime(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGATIME, "mangatime.org") {
	
	override val datePattern = "d MMMM، yyyy"
	
	// إضافة معالج للأخطاء
	override val isMultipleTagsSupported = false
	
	// تحديد User-Agent مخصص
	override val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
	
	// تحديد المسارات الصحيحة
	override val mangaSubdirectory = "manga"
	override val listUrl = "manga/"
	
	// إعداد الترميز
	override val charset: String = "UTF-8"
	
	// تحديد المُحددات (selectors) المطلوبة
	override val mangaDetailsSelectorStatus = "div.summary-content"
	override val mangaDetailsSelectorDescription = "div.summary__content p, div.description-summary p"
	override val mangaDetailsSelectorTag = "div.genres-content a"
	override val mangaDetailsSelectorAuthor = "div.author-content a"
	
	// إعداد صفحات البحث
	override val searchUrl = "page/{page}/?s={query}&post_type=wp-manga"
	
	// معالج مخصص للأخطاء
	override suspend fun getDetails(manga: org.koitharu.kotatsu.parsers.model.Manga): org.koitharu.kotatsu.parsers.model.Manga {
		return try {
			super.getDetails(manga)
		} catch (e: Exception) {
			// إذا فشل في الحصول على التفاصيل، إرجاع المانجا الأساسية
			manga.copy(
				description = "وصف غير متوفر",
				isNsfw = false
			)
		}
	}
	
	// معالج مخصص للفصول
	override suspend fun getPages(chapter: org.koitharu.kotatsu.parsers.model.MangaChapter): List<org.koitharu.kotatsu.parsers.model.MangaPage> {
		return try {
			super.getPages(chapter)
		} catch (e: Exception) {
			// إذا فشل في الحصول على الصفحات، إرجاع قائمة فارغة
			emptyList()
		}
	}
	
	// إعداد الكوكيز إذا لزم الأمر
	override suspend fun init() {
		super.init()
		// يمكن إضافة إعدادات إضافية هنا إذا لزم الأمر
	}
}

// إصدار مبسط في حالة استمرار المشاكل:
/*
@MangaSourceParser("MANGATIME", "MangaTime", "ar")
internal class MangaTimeSimple(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGATIME, "mangatime.org") {
	
	override val datePattern = "d MMMM، yyyy"
	override val isMultipleTagsSupported = false
	override val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
}
*/
