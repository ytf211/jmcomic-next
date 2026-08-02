package com.par9uet.jm.cache

import com.par9uet.jm.database.model.DownloadComic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ComicDownloadCacheTest {
    @Test
    fun chapterCacheNameUsesChapterIdWhenNamesAreBlank() {
        val firstName = getChapterCacheName(downloadTask(id = 101, chapterName = ""))
        val secondName = getChapterCacheName(downloadTask(id = 202, chapterName = ""))

        assertNotEquals(firstName, secondName)
        assertEquals("chapter_101", firstName)
        assertEquals("chapter_202", secondName)
    }

    @Test
    fun chapterCacheNameUsesChapterIdWhenNamesMatch() {
        val firstName = getChapterCacheName(downloadTask(id = 101, chapterName = "第 1 章"))
        val secondName = getChapterCacheName(downloadTask(id = 202, chapterName = "第 1 章"))

        assertNotEquals(firstName, secondName)
    }

    @Test
    fun comicCacheNameUsesGroupId() {
        val firstGroup = getComicCacheName(
            downloadTask(id = 101, chapterName = "", groupId = 11, groupName = "旧标题")
        )
        val secondGroup = getComicCacheName(
            downloadTask(id = 202, chapterName = "", groupId = 22, groupName = "其他漫画")
        )
        val sameGroup = getComicCacheName(
            downloadTask(id = 303, chapterName = "", groupId = 11, groupName = "新标题")
        )

        assertNotEquals(firstGroup, secondGroup)
        assertEquals(firstGroup, sameGroup)
        assertEquals("comic_11", firstGroup)
    }

    private fun downloadTask(
        id: Int,
        chapterName: String,
        groupId: Int = 1,
        groupName: String = "测试漫画",
    ) = DownloadComic(
        id = id,
        name = "测试漫画",
        authorList = emptyList(),
        coverPath = "",
        zipPath = "",
        progress = 0f,
        status = "pending",
        createTime = 0L,
        groupId = groupId,
        groupName = groupName,
        chapterName = chapterName,
    )
}
