package com.example.adminmemo

/** 클라우드·파일 덮어쓰기 뒤에 메모리 캐시를 비운다. */
object StoreCaches {
    fun invalidateProgress() {
        RecallStore.invalidateCache()
        DailyQuestStore.invalidateCache()
        ExamChanceStore.invalidateCache()
        CardStore.invalidateMemoryCache()
        SkillPulseStore.invalidateCache()
        StudyWeekStore.invalidateCache()
    }

    fun invalidateCards() {
        CardStore.invalidateMergedCache()
    }
}
