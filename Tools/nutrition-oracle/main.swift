import Foundation

// Standalone parser and disk-journal checks. See docs/NUTRITION.md for build commands.
if CommandLine.arguments.dropFirst().first == "--check-journal" {
    func check(_ condition: Bool) { precondition(condition) }
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("nutrition-journal-test-" + UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let journal = NutritionJournal(directory: directory)
    check(try journal.load().isEmpty)
    var meal = NutritionMeal(); meal.name = "Lunch"; meal.calories = 600
    meal = try journal.save(meal, jpeg: Data([1, 2, 3]))[0]
    let oldPhoto = journal.photoURL(meal)
    meal.calories = 700
    meal = try journal.save(meal, jpeg: nil)[0]
    let reopened = NutritionJournal(directory: directory)
    check(try reopened.load().count == 1)
    check(try reopened.load()[0].calories == 700)
    check(FileManager.default.fileExists(atPath: oldPhoto.path))
    meal = try reopened.save(meal, jpeg: Data([4, 5, 6]))[0]
    check(!FileManager.default.fileExists(atPath: oldPhoto.path))
    let newPhoto = reopened.photoURL(meal)
    check(try Data(contentsOf: newPhoto) == Data([4, 5, 6]))
    check(try reopened.delete(meal).isEmpty)
    check(!FileManager.default.fileExists(atPath: newPhoto.path))
    let file = directory.appendingPathComponent("meals-v1.json")
    try Data("broken".utf8).write(to: file)
    var blocked = false
    do { _ = try reopened.save(meal, jpeg: nil) } catch { blocked = true }
    check(blocked)
    check(try String(contentsOf: file, encoding: .utf8) == "broken")
    print("Swift journal checks passed: reload, edit, photo replacement, delete, corrupt-journal protection.")
} else {
    let data = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
    let cases = try JSONSerialization.jsonObject(with: data) as! [[String: Any]]
    for item in cases {
        let name = item["case"] as! String
        do {
            let meal = try NutritionMeal.parseEstimate(item["input"] as! String)
            print("\(name):\(meal.name)|\(meal.portion)|\(meal.calories)|\(meal.protein)|\(meal.carbs)|\(meal.fat)")
        } catch { print("\(name):rejected") }
    }
}
