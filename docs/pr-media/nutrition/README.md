# Nutrition PR preview

Captured from the running Android Full Debug app on an Android 9 emulator at 720 × 1280. The normal MainActivity and production Nutrition screen were used; no capture host or mocked UI is included.

The walkthrough shows opening the editor, a selected food photo, manually entering calories/macros, saving, and updated daily totals. It is accelerated 2–3× with idle/picker intervals removed. The GIF is a smaller preview of the MP4.

The displayed 180 kcal / 2 g protein / 42 g carbohydrates / 1 g fat are synthetic, manually entered demo values. No live AI response was captured; no provider key was configured. Camera capture and live-provider behavior still require device validation. These previews cover Android, not Apple UI.

Food photo: [OpenCV fruits.jpg sample](https://github.com/opencv/opencv/blob/4.x/samples/data/fruits.jpg), from the OpenCV repository ([license](https://github.com/opencv/opencv/blob/4.x/LICENSE)).

## iOS simulator preview

The iOS daily-log screenshot was captured from the production SwiftUI Nutrition view on an iPhone 17 Pro simulator, using the existing DEBUG-only `--demo-screen nutrition` launcher. The simulator contains the same synthetic manual meal and attributed food photo as the Android preview. No live AI response is shown.

The reproducible capture workflow is `.github/workflows/ios-nutrition-preview.yml`; it creates a temporary UI-test target in the generated Xcode project, seeds a disposable simulator, and exports XCTest screenshots. The UI capture also verifies that editing loads the selected meal and enables photo analysis.
