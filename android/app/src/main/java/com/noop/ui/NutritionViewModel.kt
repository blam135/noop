package com.noop.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noop.nutrition.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class NutritionViewModel(application: Application) : AndroidViewModel(application) {
    private val store by lazy { NutritionStore(application) }
    var meals by mutableStateOf<List<NutritionMeal>>(emptyList()); private set
    var day by mutableStateOf(LocalDate.now())
    var draft by mutableStateOf<NutritionMeal?>(null)
    var calories by mutableStateOf("0")
    var protein by mutableStateOf("0")
    var carbs by mutableStateOf("0")
    var fat by mutableStateOf("0")
    var jpeg by mutableStateOf<ByteArray?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false); private set

    init { reload() }
    fun reload() {
        viewModelScope.launch {
            try { meals = withContext(Dispatchers.IO) { store.load() }; loaded = true }
            catch (e: Exception) { error = e.localizedMessage }
        }
    }
    fun edit(meal: NutritionMeal? = null) {
        error = null; jpeg = null
        draft = meal ?: NutritionMeal(timestamp = day.atTime(LocalTime.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().toDouble())
        calories = draft!!.calories.toString(); protein = draft!!.protein.toString()
        carbs = draft!!.carbs.toString(); fat = draft!!.fat.toString()
    }
    fun validMeal(): NutritionMeal? {
        val value = draft ?: return null
        return value.copy(calories = calories.replace(',', '.').toDoubleOrNull() ?: return null, protein = protein.replace(',', '.').toDoubleOrNull() ?: return null,
            carbs = carbs.replace(',', '.').toDoubleOrNull() ?: return null, fat = fat.replace(',', '.').toDoubleOrNull() ?: return null).takeIf { it.isValid }
    }
    fun photoFile(meal: NutritionMeal) = store.photoFile(meal)
    fun loadPhoto(uri: Uri, temporaryFile: java.io.File? = null) {
        busy = true; error = null
        viewModelScope.launch {
            try {
                jpeg = withContext(Dispatchers.IO) { NutritionPhoto.load(getApplication(), uri) }
                draft = draft?.copy(name = "", portion = "", provider = "", model = "")
                calories = "0"; protein = "0"; carbs = "0"; fat = "0"
            } catch (e: Exception) { error = e.localizedMessage }
            finally {
                if (temporaryFile != null) withContext(Dispatchers.IO) {
                    val allowed = java.io.File(getApplication<Application>().cacheDir, "nutrition").canonicalFile
                    if (temporaryFile.parentFile?.canonicalFile == allowed) temporaryFile.delete()
                }
                busy = false
            }
        }
    }
    fun analyze() {
        val current = draft ?: return
        val settings = NutritionAnalysis.settings(getApplication())
        val currentJpeg = jpeg
        busy = true; error = null
        viewModelScope.launch {
            try {
                val image = currentJpeg ?: withContext(Dispatchers.IO) { store.photoFile(current).readBytes() }
                val estimate = NutritionAnalysis.analyze(image, current.notes, settings)
                draft = estimate.copy(id = current.id, timestamp = current.timestamp, photo = current.photo)
                calories = estimate.calories.toString(); protein = estimate.protein.toString()
                carbs = estimate.carbs.toString(); fat = estimate.fat.toString()
            } catch (e: Exception) { error = e.localizedMessage }
            finally { busy = false }
        }
    }
    fun save() {
        val value = validMeal() ?: return
        val image = jpeg
        busy = true; error = null
        viewModelScope.launch {
            try { meals = withContext(Dispatchers.IO) { store.save(value, image) }; draft = null; jpeg = null }
            catch (e: Exception) { error = e.localizedMessage }
            finally { busy = false }
        }
    }
    fun delete(meal: NutritionMeal) {
        busy = true
        viewModelScope.launch {
            try { meals = withContext(Dispatchers.IO) { store.delete(meal) } }
            catch (e: Exception) { error = e.localizedMessage }
            finally { busy = false }
        }
    }
}
