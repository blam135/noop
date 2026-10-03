import Foundation
import Combine

// Independent local meal journal: does not overwrite imported daily nutrition totals.
@MainActor final class NutritionStore: ObservableObject {
    @Published private(set) var meals: [NutritionMeal] = []
    @Published var error: String?
    private let journal: NutritionJournal
    private var loaded = false

    init(directory: URL? = nil) {
        journal = NutritionJournal(directory: directory ?? FileManager.default.urls(for: .applicationSupportDirectory,
                                                                                    in: .userDomainMask)[0]
            .appendingPathComponent("Nutrition", isDirectory: true))
    }

    func load() {
        do { meals = try journal.load(); loaded = true }
        catch { loaded = false; self.error = error.localizedDescription }
    }

    func save(_ meal: NutritionMeal, jpeg: Data?) throws {
        guard loaded else { throw NutritionError.message("The meal journal is unavailable.") }
        meals = try journal.save(meal, jpeg: jpeg)
    }

    func delete(_ meal: NutritionMeal) throws {
        guard loaded else { throw NutritionError.message("The meal journal is unavailable.") }
        meals = try journal.delete(meal)
    }

    func photoURL(_ meal: NutritionMeal) -> URL { journal.photoURL(meal) }
}
