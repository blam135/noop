# Nutrition photo journal

Nutrition is available in the Mac sidebar and under **More → Body → Nutrition** on iPhone and
Android. Choose a day to see its meals and calorie, protein, carbohydrate, and fat totals.
**Log a meal** supports manual entry without a network connection, choosing an existing photo,
or taking a photo with the phone camera. Mac uses an image-file picker.

## Optional photo analysis

Configure the provider and model in Coach settings first. **Analyze photo** sends only the selected
food image and the draft's meal notes to that provider, using the same provider-owned API credential
and selected model as the Coach. It does not read or transmit strap data, conversation history,
or previously saved meals. Nothing is uploaded by selecting or taking a photo.

OpenAI, Anthropic, Gemini, and Custom OpenAI-compatible servers have explicit image request formats.
The selected model must support vision; an unsupported model produces a provider error rather than
silently ignoring the image. Custom servers use the existing HTTPS/private-network URL guard and
support keyless local models. Neither client follows redirects for these requests.

The result identifies the foods and estimated portion, provides meal calories and macros, and
explains uncertainty. It is an editable draft, not an automatic diary entry. Review or correct it
before saving. Calories and macros describe the entire meal, not a measured quantity per 100 grams.
Replacing a photo clears the previous foods and nutrient values, so an old estimate cannot silently
be saved against a different photo. Notes remain available as context for the new analysis.

Images are oriented, scaled to a maximum dimension of 1280 pixels, and re-encoded as JPEG without
GPS/EXIF metadata before being stored or sent. Input images are limited to 30 MB. No-food, incomplete,
truncated, nonnumeric, negative, and implausibly large responses are rejected. Model estimates can
miss oils, sauces, ingredients, and portion sizes; they are not medical or dietary prescriptions.

## Storage and limits

Meals are stored in a private, versioned `Nutrition/meals-v1.json` journal with relative JPEG photo
names. Writes are atomic; an unreadable journal is never replaced with an empty one. Editing keeps
the entry's identity; deleting removes its contribution to daily totals and its saved photo. Photo
replacement commits a new photo reference before removing the old image. Camera temporary files
are removed after normalization on Android.

Both platforms use the same JSON keys and units. `timestamp` is Unix milliseconds; `calories` is
kcal; `protein`, `carbs`, and `fat` are grams. `provider` uses the Swift canonical values `openAI`,
`anthropic`, `gemini`, and `custom`. The model and uncertainty notes remain with reviewed estimates.
Manual entries have empty provider/model values. Dates and daily grouping follow the device's local
time zone.

This meal journal is separate from imported `nutrition-csv` metric series. It does not overwrite or
sum those imported daily totals, feed recovery/strain calculations, or enter Explore/Compare yet.
Meals and photos are not included in `.noopbak` backup or self-hosted push. The Nutrition screen
states this limitation; retain another copy of important records before uninstalling the app.

## Validation

The actual Swift parser generates `Tools/nutrition-oracle/swift-oracle.txt` from the shared JSON
fixtures. Swift and Kotlin tests compare against this oracle. To regenerate it:

```sh
swiftc Strand/Nutrition/NutritionMeal.swift Strand/Nutrition/NutritionJournal.swift \
  Tools/nutrition-oracle/main.swift -o /tmp/nutrition-oracle
/tmp/nutrition-oracle Tools/nutrition-oracle/estimates.json > Tools/nutrition-oracle/swift-oracle.txt
/tmp/nutrition-oracle --check-journal
```

Android unit tests additionally cover provider-specific image payloads, truncated responses, Gemini
thought filtering, saved-entry round trips, reload/edit/delete, photo replacement, and corrupt-journal
protection. `StrandTests/NutritionTests.swift` covers the Apple parser and observable storage wrapper.
Phone camera and photo-library interactions still require device testing, and Apple app builds
require Xcode on macOS.
