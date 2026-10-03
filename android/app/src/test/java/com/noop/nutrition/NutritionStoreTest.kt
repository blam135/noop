package com.noop.nutrition

import android.app.Application
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class NutritionStoreTest {
    private lateinit var app: Application
    @Before fun reset() {
        app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "Nutrition").deleteRecursively()
    }

    @Test fun savedMealSurvivesReloadAndCanBeEditedAndDeleted() {
        val store = NutritionStore(app)
        val saved = store.save(NutritionMeal(name = "Lunch", calories = 600.0), byteArrayOf(1, 2, 3)).single()
        store.save(saved.copy(calories = 700.0), null)
        val reopened = NutritionStore(app)
        assertEquals(700.0, reopened.load().single().calories, 0.0)
        assertTrue(reopened.photoFile(saved).exists())
        reopened.delete(saved)
        assertTrue(reopened.load().isEmpty())
        assertFalse(reopened.photoFile(saved).exists())
    }

    @Test fun replacingPhotoDeletesOldPhotoOnlyAfterMealCommits() {
        val store = NutritionStore(app)
        val original = store.save(NutritionMeal(name = "Lunch"), byteArrayOf(1)).single()
        val replacement = store.save(original, byteArrayOf(2)).single()
        assertNotEquals(original.photo, replacement.photo)
        assertFalse(store.photoFile(original).exists())
        assertTrue(store.photoFile(replacement).exists())
    }

    @Test fun corruptJournalCannotBeOverwritten() {
        val store = NutritionStore(app)
        val journal = File(app.filesDir, "Nutrition/meals-v1.json")
        journal.writeText("broken")
        assertThrows(Exception::class.java) { store.save(NutritionMeal(name = "Lunch"), null) }
        assertEquals("broken", journal.readText())
    }
}
