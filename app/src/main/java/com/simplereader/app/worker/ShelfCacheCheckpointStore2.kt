package com.simplereader.app.worker

import android.content.Context

object ShelfCacheCheckpointStore2 {
    private const val PREFS = "shelf_cache_checkpoint"
    data class Snapshot(val completedBookIds:Set<Long>, val skippedBookIds:Set<Long>)
    fun load(context: Context, workId: String): Snapshot {
        val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        return Snapshot(
            prefs.getStringSet("$workId.completed", emptySet()).orEmpty().mapNotNull{it.toLongOrNull()}.toSet(),
            prefs.getStringSet("$workId.skipped", emptySet()).orEmpty().mapNotNull{it.toLongOrNull()}.toSet()
        )
    }
    fun markCompleted(context: Context, workId: String, bookId: Long)=mark(context,workId,"completed",bookId)
    fun markSkipped(context: Context, workId: String, bookId: Long)=mark(context,workId,"skipped",bookId)
    fun clear(context: Context, workId: String){context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove("$workId.completed").remove("$workId.skipped").apply()}
    private fun mark(context: Context, workId: String, kind:String, bookId:Long){
        val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE); val key="$workId.$kind"; val ids=prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet(); ids+=bookId.toString(); prefs.edit().putStringSet(key,ids).apply()
    }
}
