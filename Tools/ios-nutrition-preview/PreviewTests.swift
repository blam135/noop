import XCTest

final class NutritionPreviewTests: XCTestCase {
    func testCaptureProductionNutritionViews() throws {
        let app = XCUIApplication(bundleIdentifier: "com.noopapp.noop")
        app.launchArguments = ["--demo-screen", "nutrition", "-theme.appearance", "light"]
        app.launch()
        XCTAssertTrue(app.buttons["Log a meal"].waitForExistence(timeout: 30))
        capture("ios-daily-log")
        let edit = app.buttons["Edit"]
        for _ in 0..<5 where !edit.isHittable { app.swipeUp() }
        XCTAssertTrue(edit.waitForExistence(timeout: 10))
        edit.tap()
        XCTAssertTrue(app.buttons["Analyze photo"].waitForExistence(timeout: 15))
        XCTAssertEqual(app.textFields["Food"].value as? String, "Fruit bowl")
        XCTAssertTrue(app.buttons["Analyze photo"].isEnabled)
        capture("ios-photo-editor")
    }

    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
