package com.noop.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noop.R
import com.noop.nutrition.NutritionAnalysis
import com.noop.nutrition.NutritionMeal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

@Composable
fun NutritionScreen(vm: NutritionViewModel = viewModel()) {
    val context = LocalContext.current
    val date = vm.day
    val meals = vm.meals.filter { Instant.ofEpochMilli(it.timestamp.toLong()).atZone(ZoneId.systemDefault()).toLocalDate() == date }
    var deleting by remember { mutableStateOf<NutritionMeal?>(null) }
    ScreenScaffold(title = uiString(R.string.nav_nutrition), subtitle = uiString(R.string.nutrition_subtitle)) {
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                TextButton(onClick = {
                    DatePickerDialog(context, { _, y, m, d -> vm.day = LocalDate.of(y, m + 1, d) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                }) { Text(date.toString()) }
                Text(uiString(R.string.nutrition_kcal, meals.sumOf { it.calories }), style = NoopType.title1, color = Palette.textPrimary)
                Text(uiString(R.string.nutrition_macro_totals, meals.sumOf { it.protein }, meals.sumOf { it.carbs }, meals.sumOf { it.fat }), style = NoopType.subhead, color = Palette.textSecondary)
                Button(onClick = { vm.edit() }, enabled = vm.loaded && !vm.busy) { Text(uiString(R.string.nutrition_log_meal)) }
            }
        }
        if (!vm.loaded && vm.error == null) CircularProgressIndicator()
        if (vm.loaded && meals.isEmpty()) Text(uiString(R.string.nutrition_empty), style = NoopType.body, color = Palette.textSecondary)
        meals.forEach { meal ->
            key(meal.id) {
                NoopCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                        NutritionPhotoPreview(null, vm.photoFile(meal), meal.name)
                        Text(meal.name, style = NoopType.headline, color = Palette.textPrimary)
                        Text(meal.portion, style = NoopType.subhead, color = Palette.textSecondary)
                        Text(uiString(R.string.nutrition_kcal, meal.calories), style = NoopType.headline, color = Palette.textPrimary)
                        Text(uiString(R.string.nutrition_macro_totals, meal.protein, meal.carbs, meal.fat), style = NoopType.subhead, color = Palette.textSecondary)
                        Text(Instant.ofEpochMilli(meal.timestamp.toLong()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)), style = NoopType.caption, color = Palette.textSecondary)
                        if (meal.model.isNotBlank()) Text(uiString(R.string.nutrition_reviewed, meal.model), style = NoopType.caption, color = Palette.textSecondary)
                        if (meal.notes.isNotBlank()) Text(meal.notes, style = NoopType.caption, color = Palette.textSecondary)
                        Row {
                            TextButton(onClick = { vm.edit(meal) }, enabled = !vm.busy) { Text(uiString(R.string.nutrition_edit)) }
                            TextButton(onClick = { deleting = meal }, enabled = !vm.busy) { Text(uiString(R.string.nutrition_delete)) }
                        }
                    }
                }
            }
        }
        Text(uiString(R.string.nutrition_local_notice), style = NoopType.caption, color = Palette.textSecondary)
        if (vm.draft == null) vm.error?.let {
            Text(it, style = NoopType.caption, color = Palette.statusWarning)
            if (!vm.loaded) TextButton(onClick = { vm.error = null; vm.reload() }) { Text(uiString(R.string.nutrition_retry)) }
        }
    }
    deleting?.let { meal ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(uiString(R.string.nutrition_delete_confirm)) },
            confirmButton = { TextButton(onClick = { deleting = null; vm.delete(meal) }) { Text(uiString(R.string.nutrition_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(uiString(R.string.nutrition_cancel)) } })
    }
    if (vm.draft != null) NutritionEditor(vm)
}

@Composable
private fun NutritionPhotoPreview(jpeg: ByteArray?, file: File, description: String) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, jpeg, file.path, file.lastModified()) {
        value = withContext(Dispatchers.IO) {
            if (jpeg != null) BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            else if (file.isFile) BitmapFactory.decodeFile(file.path) else null
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), contentDescription = description, modifier = Modifier.fillMaxWidth()) }
}

@Composable
private fun NutritionEditor(vm: NutritionViewModel) {
    val context = LocalContext.current
    val meal = vm.draft ?: return
    val settings = NutritionAnalysis.settings(context)
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) vm.loadPhoto(uri) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        cameraPath?.let { path ->
            val file = File(path)
            if (success) vm.loadPhoto(Uri.fromFile(file), temporaryFile = file)
            else file.delete()
        }
        cameraPath = null
    }
    Dialog(onDismissRequest = { if (!vm.busy) vm.draft = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Palette.surfaceBase, modifier = Modifier.fillMaxSize()) {
            ScreenScaffold(title = uiString(R.string.nutrition_log_meal)) {
                NoopCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                        NutritionPhotoPreview(vm.jpeg, vm.photoFile(meal), meal.name)
                        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                            TextButton(onClick = { photoPicker.launch("image/*") }, enabled = !vm.busy) { Text(uiString(R.string.nutrition_choose_photo)) }
                            TextButton(onClick = {
                                try {
                                    val dir = File(context.cacheDir, "nutrition").also { it.mkdirs() }
                                    dir.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86400000 }?.forEach { it.delete() }
                                    val file = File(dir, UUID.randomUUID().toString() + ".jpg")
                                    cameraPath = file.path
                                    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                                    camera.launch(uri)
                                } catch (e: Exception) { vm.error = e.localizedMessage }
                            }, enabled = !vm.busy) { Text(uiString(R.string.nutrition_take_photo)) }
                        }
                        Text(uiString(R.string.nutrition_send_notice, settings.provider.displayName, settings.model), style = NoopType.caption, color = Palette.textSecondary)
                        Button(onClick = { vm.analyze() }, enabled = !vm.busy && (vm.jpeg != null || meal.photo.isNotEmpty())) { Text(uiString(R.string.nutrition_analyze)) }
                        if (vm.busy) CircularProgressIndicator()
                        NutritionField(uiString(R.string.nutrition_food), meal.name, !vm.busy) { vm.draft = meal.copy(name = it) }
                        NutritionField(uiString(R.string.nutrition_portion), meal.portion, !vm.busy) { vm.draft = meal.copy(portion = it) }
                        NutritionField(uiString(R.string.nutrition_notes), meal.notes, !vm.busy) { vm.draft = meal.copy(notes = it) }
                        NutritionField(uiString(R.string.nutrition_calories), vm.calories, !vm.busy, true) { vm.calories = it }
                        NutritionField(uiString(R.string.nutrition_protein), vm.protein, !vm.busy, true) { vm.protein = it }
                        NutritionField(uiString(R.string.nutrition_carbs), vm.carbs, !vm.busy, true) { vm.carbs = it }
                        NutritionField(uiString(R.string.nutrition_fat), vm.fat, !vm.busy, true) { vm.fat = it }
                        val eaten = Instant.ofEpochMilli(meal.timestamp.toLong()).atZone(ZoneId.systemDefault())
                        TextButton(onClick = {
                            DatePickerDialog(context, { _, y, m, d ->
                                TimePickerDialog(context, { _, h, min ->
                                    val timestamp = LocalDate.of(y, m + 1, d).atTime(h, min).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().toDouble()
                                    vm.draft = vm.draft?.copy(timestamp = timestamp)
                                }, eaten.hour, eaten.minute, true).show()
                            }, eaten.year, eaten.monthValue - 1, eaten.dayOfMonth).show()
                        }, enabled = !vm.busy) { Text(uiString(R.string.nutrition_eaten_at, eaten.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)))) }
                        Text(uiString(R.string.nutrition_estimate_notice), style = NoopType.caption, color = Palette.textSecondary)
                        vm.error?.let { Text(it, style = NoopType.caption, color = Palette.statusWarning) }
                        Row {
                            TextButton(onClick = { vm.draft = null }, enabled = !vm.busy) { Text(uiString(R.string.nutrition_cancel)) }
                            Button(onClick = { vm.save() }, enabled = !vm.busy && vm.validMeal() != null) { Text(uiString(R.string.nutrition_save)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NutritionField(label: String, value: String, enabled: Boolean, numeric: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        enabled = enabled, textStyle = NoopType.body,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text))
}
