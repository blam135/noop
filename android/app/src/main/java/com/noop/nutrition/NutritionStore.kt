package com.noop.nutrition

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import java.io.File

// Atomic, private local journal, separate from imported nutrition CSV totals and the strap database.
class NutritionStore(context: Context) {
    private val directory = File(context.filesDir, "Nutrition").also { check(it.isDirectory || it.mkdirs()) }
    private val journal = AtomicFile(File(directory, "meals-v1.json"))

    @Synchronized fun load(): List<NutritionMeal> {
        // openRead restores a pending AtomicFile backup after an interrupted write.
        val text = try { journal.openRead().bufferedReader().use { it.readText() } }
            catch (e: java.io.FileNotFoundException) {
                if (journal.baseFile.exists()) throw e
                return emptyList()
            }
        val array = JSONArray(text)
        return (0 until array.length()).map { NutritionMeal.fromJson(array.getJSONObject(it)) }
    }

    @Synchronized fun save(meal: NutritionMeal, jpeg: ByteArray?): List<NutritionMeal> {
        require(meal.isValid)
        val current = load() // Never overwrite an unreadable journal.
        val saved = if (jpeg != null) meal.copy(photo = meal.id + "-" + java.util.UUID.randomUUID().toString() + ".jpg") else meal
        if (jpeg != null) write(AtomicFile(photoFile(saved)), jpeg)
        val next = (current.filterNot { it.id == saved.id } + saved).sortedByDescending { it.timestamp }
        try { persist(next) }
        catch (e: Exception) {
            if (jpeg != null) photoFile(saved).delete()
            throw e
        }
        if (jpeg != null) current.firstOrNull { it.id == saved.id }?.takeIf { it.photo.isNotEmpty() }?.let { photoFile(it).delete() }
        return next
    }

    @Synchronized fun delete(meal: NutritionMeal): List<NutritionMeal> {
        val next = load().filterNot { it.id == meal.id }
        persist(next)
        if (meal.photo.isNotEmpty()) photoFile(meal).delete()
        return next
    }

    fun photoFile(meal: NutritionMeal): File = File(directory, File(meal.photo).name)

    private fun persist(meals: List<NutritionMeal>) {
        val json = JSONArray()
        meals.forEach { json.put(it.toJson()) }
        write(journal, json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun write(file: AtomicFile, bytes: ByteArray) {
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
