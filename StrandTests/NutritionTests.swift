import XCTest
@testable import Strand

final class NutritionTests: XCTestCase {
    func testPhotoEstimatesMatchSharedOracle() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let fixtures = root.appendingPathComponent("Tools/nutrition-oracle")
        let cases = try JSONSerialization.jsonObject(with: Data(contentsOf: fixtures.appendingPathComponent("estimates.json"))) as! [[String: String]]
        let expected = try String(contentsOf: fixtures.appendingPathComponent("swift-oracle.txt"), encoding: .utf8)
        let actual = cases.map { item -> String in
            let name = item["case"]!
            do {
                let meal = try NutritionMeal.parseEstimate(item["input"]!)
                return "\(name):\(meal.name)|\(meal.portion)|\(meal.calories)|\(meal.protein)|\(meal.carbs)|\(meal.fat)"
            } catch { return "\(name):rejected" }
        }.joined(separator: "\n") + "\n"
        XCTAssertEqual(actual, expected)
    }

    @MainActor func testMealJournalPersistsEditsAndDeletion() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = NutritionStore(directory: directory)
        store.load()
        var meal = NutritionMeal(); meal.name = "Lunch"; meal.calories = 600
        try store.save(meal, jpeg: Data([1, 2, 3]))
        meal = try XCTUnwrap(store.meals.first)
        meal.calories = 700
        try store.save(meal, jpeg: nil)
        let reopened = NutritionStore(directory: directory)
        reopened.load()
        XCTAssertEqual(reopened.meals.count, 1)
        XCTAssertEqual(reopened.meals.first?.calories, 700)
        XCTAssertTrue(FileManager.default.fileExists(atPath: reopened.photoURL(meal).path))
        try reopened.delete(meal)
        reopened.load()
        XCTAssertTrue(reopened.meals.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: reopened.photoURL(meal).path))
    }

    @MainActor func testCorruptJournalCannotBeOverwritten() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("meals-v1.json")
        let corrupt = Data("broken".utf8)
        try corrupt.write(to: file)
        let store = NutritionStore(directory: directory); store.load()
        var meal = NutritionMeal(); meal.name = "Lunch"
        XCTAssertThrowsError(try store.save(meal, jpeg: nil))
        XCTAssertEqual(try Data(contentsOf: file), corrupt)
    }
}
