import Foundation

// Foundation-only disk journal, independently executable without SwiftUI or the strap database.
final class NutritionJournal {
    let directory: URL
    init(directory: URL) { self.directory = directory }
    private var file: URL { directory.appendingPathComponent("meals-v1.json") }

    func load() throws -> [NutritionMeal] {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        guard FileManager.default.fileExists(atPath: file.path) else { return [] }
        let meals = try JSONDecoder().decode([NutritionMeal].self, from: Data(contentsOf: file))
        guard meals.allSatisfy(\.isValid) else { throw NutritionError.invalidEstimate }
        return meals
    }

    func save(_ meal: NutritionMeal, jpeg: Data?) throws -> [NutritionMeal] {
        guard meal.isValid else { throw NutritionError.invalidEstimate }
        let current = try load() // Never replace an unreadable journal.
        var saved = meal
        if let jpeg {
            saved.photo = saved.id + "-" + UUID().uuidString.lowercased() + ".jpg"
            try jpeg.write(to: photoURL(saved), options: .atomic)
        }
        var next = current.filter { $0.id != saved.id }
        next.append(saved)
        next.sort { $0.timestamp > $1.timestamp }
        do { try JSONEncoder().encode(next).write(to: file, options: .atomic) }
        catch {
            if jpeg != nil { try? FileManager.default.removeItem(at: photoURL(saved)) }
            throw error
        }
        if jpeg != nil, let previous = current.first(where: { $0.id == saved.id }), !previous.photo.isEmpty {
            try? FileManager.default.removeItem(at: photoURL(previous))
        }
        return next
    }

    func delete(_ meal: NutritionMeal) throws -> [NutritionMeal] {
        let next = try load().filter { $0.id != meal.id }
        try JSONEncoder().encode(next).write(to: file, options: .atomic)
        if !meal.photo.isEmpty { try? FileManager.default.removeItem(at: photoURL(meal)) }
        return next
    }

    func photoURL(_ meal: NutritionMeal) -> URL {
        directory.appendingPathComponent(URL(fileURLWithPath: meal.photo).lastPathComponent)
    }
}
