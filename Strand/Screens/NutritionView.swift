import SwiftUI
import StrandDesign
import UniformTypeIdentifiers
import ImageIO
#if os(iOS)
import PhotosUI
import UIKit
#endif

@MainActor struct NutritionView: View {
    @StateObject private var journal = NutritionStore()
    @State private var day = Date()
    @State private var editing: NutritionMeal?
    @State private var showEditor = false
    @State private var deleting: NutritionMeal?

    private var meals: [NutritionMeal] { journal.meals.filter { Calendar.current.isDate($0.date, inSameDayAs: day) } }

    var body: some View {
        ScreenScaffold(title: "Nutrition", subtitle: "Your meals, photos, and daily nutrition") {
            NoopCard {
                VStack(alignment: .leading, spacing: NoopMetrics.gap) {
                    DatePicker("Day", selection: $day, displayedComponents: .date)
                    Text("\(meals.reduce(0) { $0 + $1.calories }, specifier: "%.0f") kcal")
                        .font(StrandFont.title1)
                    Text("Protein \(meals.reduce(0) { $0 + $1.protein }, specifier: "%.1f") g · Carbs \(meals.reduce(0) { $0 + $1.carbs }, specifier: "%.1f") g · Fat \(meals.reduce(0) { $0 + $1.fat }, specifier: "%.1f") g")
                        .font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
                    Button { editing = nil; showEditor = true } label: { Label("Log a meal", systemImage: "plus") }
                }
            }
            if meals.isEmpty {
                Text("No meals logged for this day. Add a photo or enter a meal manually.")
                    .font(StrandFont.body).foregroundStyle(StrandPalette.textSecondary)
            }
            ForEach(meals) { meal in
                NoopCard {
                    VStack(alignment: .leading, spacing: NoopMetrics.gap) {
                        if let image = NutritionPhoto.preview(url: journal.photoURL(meal)) {
                            Image(decorative: image, scale: 1).resizable().scaledToFit()
                                .accessibilityLabel(Text(meal.name))
                        }
                        Text(meal.name).font(StrandFont.headline)
                        Text(meal.portion).font(StrandFont.subhead)
                        Text("\(meal.calories, specifier: "%.0f") kcal")
                            .font(StrandFont.headline)
                        Text("Protein \(meal.protein, specifier: "%.1f") g · Carbs \(meal.carbs, specifier: "%.1f") g · Fat \(meal.fat, specifier: "%.1f") g")
                            .font(StrandFont.subhead)
                        Text(meal.date, style: .time).font(StrandFont.caption)
                        if !meal.model.isEmpty {
                            Text("AI estimate · \(meal.model) · Reviewed by you")
                                .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                        }
                        if !meal.notes.isEmpty { Text(meal.notes).font(StrandFont.footnote) }
                        HStack {
                            Button("Edit") { editing = meal; showEditor = true }
                            Button("Delete", role: .destructive) { deleting = meal }
                        }
                    }
                }
            }
            Text("Photo estimates can miss ingredients and serving sizes. Review before saving. Meals stay on this device; they are separate from nutrition CSV imports and are not included in NOOP backups yet.")
                .font(StrandFont.footnote).foregroundStyle(StrandPalette.textSecondary)
        }
        .task { journal.load() }
        .sheet(isPresented: $showEditor) {
            NutritionMealEditor(journal: journal, initial: editing, day: day)
        }
        .alert("Nutrition", isPresented: Binding(get: { journal.error != nil }, set: { if !$0 { journal.error = nil } })) {
            Button("OK", role: .cancel) { journal.error = nil }
        } message: { Text(journal.error ?? "") }
        .confirmationDialog("Delete this meal and its photo?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), presenting: deleting) { meal in
            Button("Delete", role: .destructive) {
                do { try journal.delete(meal) } catch { journal.error = error.localizedDescription }
                deleting = nil
            }
        }
    }
}

@MainActor private struct NutritionMealEditor: View {
    @EnvironmentObject private var app: AppModel
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var journal: NutritionStore
    @State private var meal: NutritionMeal
    @State private var calories: String
    @State private var protein: String
    @State private var carbs: String
    @State private var fat: String
    @State private var jpeg: Data?
    @State private var error: String?
    @State private var busy = false
    @State private var showFile = false
    @State private var analysisTask: Task<Void, Never>?
    #if os(iOS)
    @State private var selection: PhotosPickerItem?
    @State private var showCamera = false
    #endif

    init(journal: NutritionStore, initial: NutritionMeal?, day: Date) {
        self.journal = journal
        var draft = initial ?? NutritionMeal()
        if initial == nil {
            let calendar = Calendar.current
            let time = calendar.dateComponents([.hour, .minute], from: Date())
            draft.timestamp = (calendar.date(bySettingHour: time.hour ?? 12, minute: time.minute ?? 0,
                                            second: 0, of: day) ?? day).timeIntervalSince1970 * 1000
        }
        _meal = State(initialValue: draft)
        _calories = State(initialValue: String(draft.calories))
        _protein = State(initialValue: String(draft.protein))
        _carbs = State(initialValue: String(draft.carbs))
        _fat = State(initialValue: String(draft.fat))
    }

    private var validMeal: NutritionMeal? {
        func number(_ text: String) -> Double? { Double(text.replacingOccurrences(of: ",", with: ".")) }
        guard let c = number(calories), let p = number(protein), let carb = number(carbs), let f = number(fat) else { return nil }
        var result = meal
        result.calories = c; result.protein = p; result.carbs = carb; result.fat = f
        return result.isValid ? result : nil
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Food photo") {
                    if let data = jpeg, let image = NutritionPhoto.preview(data: data) {
                        Image(decorative: image, scale: 1).resizable().scaledToFit()
                    } else if !meal.photo.isEmpty, let image = NutritionPhoto.preview(url: journal.photoURL(meal)) {
                        Image(decorative: image, scale: 1).resizable().scaledToFit()
                    }
                    #if os(iOS)
                    PhotosPicker("Choose photo", selection: $selection, matching: .images)
                    if UIImagePickerController.isSourceTypeAvailable(.camera) {
                        Button("Take photo") { showCamera = true }
                    }
                    #else
                    Button("Choose photo") { showFile = true }
                    #endif
                    Text("Analyze sends this photo and your meal notes to \(app.coach.provider.displayName) using \(app.coach.model). Choose a vision-capable model in Coach settings. No strap data is sent.")
                        .font(StrandFont.footnote).foregroundStyle(StrandPalette.textSecondary)
                    Button("Analyze photo") { analyze() }
                        .disabled(busy || (jpeg == nil && meal.photo.isEmpty))
                    if busy { ProgressView("Analyzing…") }
                }
                Section("Review meal") {
                    LabeledContent("Food") {
                        TextField("Food", text: $meal.name).multilineTextAlignment(.trailing)
                    }
                    LabeledContent("Portion / foods") {
                        TextField("Portion / foods", text: $meal.portion).multilineTextAlignment(.trailing)
                    }
                    TextField("Meal notes / assumptions", text: $meal.notes, axis: .vertical)
                    LabeledContent("Calories (kcal)") {
                        TextField("Calories (kcal)", text: $calories).multilineTextAlignment(.trailing)
                    }
                    LabeledContent("Protein (g)") {
                        TextField("Protein (g)", text: $protein).multilineTextAlignment(.trailing)
                    }
                    LabeledContent("Carbs (g)") {
                        TextField("Carbs (g)", text: $carbs).multilineTextAlignment(.trailing)
                    }
                    LabeledContent("Fat (g)") {
                        TextField("Fat (g)", text: $fat).multilineTextAlignment(.trailing)
                    }
                    DatePicker("Eaten at", selection: Binding(get: { meal.date }, set: { meal.timestamp = $0.timeIntervalSince1970 * 1000 }))
                    Text("Estimates are approximate. Correct the foods, portion, calories, and macros before saving.")
                        .font(StrandFont.footnote)
                }
                if let error { Text(error).font(StrandFont.footnote).foregroundStyle(StrandPalette.statusWarning) }
            }
            .font(StrandFont.body)
            .disabled(busy)
            .navigationTitle("Log a meal")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { analysisTask?.cancel(); dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        guard let validMeal else { return }
                        do { try journal.save(validMeal, jpeg: jpeg); dismiss() }
                        catch { self.error = error.localizedDescription }
                    }.disabled(busy || validMeal == nil)
                }
            }
        }
        .fileImporter(isPresented: $showFile, allowedContentTypes: [.image]) { result in
            do {
                let url = try result.get()
                let access = url.startAccessingSecurityScopedResource()
                defer { if access { url.stopAccessingSecurityScopedResource() } }
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard size <= 30 * 1024 * 1024 else { throw NutritionError.message("Choose a valid image smaller than 30 MB.") }
                try setPhoto(Data(contentsOf: url))
            } catch { self.error = error.localizedDescription }
        }
        #if os(iOS)
        .onChange(of: selection) { _, item in
            Task {
                do { if let data = try await item?.loadTransferable(type: Data.self) { try setPhoto(data) } }
                catch { self.error = error.localizedDescription }
            }
        }
        .sheet(isPresented: $showCamera) {
            NutritionCamera { image in
                showCamera = false
                do {
                    guard let data = image.jpegData(compressionQuality: 0.85) else { throw NutritionError.invalidEstimate }
                    try setPhoto(data)
                } catch { self.error = error.localizedDescription }
            }
        }
        #endif
        .onDisappear { analysisTask?.cancel() }
    }

    private func setPhoto(_ data: Data) throws {
        jpeg = try NutritionPhoto.normalize(data)
        meal.name = ""; meal.portion = ""; meal.provider = ""; meal.model = ""
        calories = "0"; protein = "0"; carbs = "0"; fat = "0"
        error = nil
    }

    private func analyze() {
        guard let data = jpeg ?? (try? Data(contentsOf: journal.photoURL(meal))) else { return }
        busy = true; error = nil
        let provider = app.coach.provider, model = app.coach.model
        analysisTask = Task { @MainActor in
            defer { busy = false }
            do {
                var estimate = try await NutritionAnalysis.analyze(jpeg: data, notes: meal.notes, provider: provider, model: model)
                try Task.checkCancellation()
                estimate.id = meal.id; estimate.timestamp = meal.timestamp; estimate.photo = meal.photo
                meal = estimate
                calories = String(meal.calories); protein = String(meal.protein)
                carbs = String(meal.carbs); fat = String(meal.fat)
            } catch is CancellationError { }
            catch { self.error = error.localizedDescription }
        }
    }
}

// Decode with an ImageIO thumbnail to bound memory and apply EXIF orientation, then re-encode
// JPEG to remove GPS/EXIF metadata before saving or sending to the chosen AI provider.
enum NutritionPhoto {
    static func preview(data: Data) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        return CGImageSourceCreateThumbnailAtIndex(source, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: 1280,
            kCGImageSourceCreateThumbnailWithTransform: true
        ] as CFDictionary)
    }
    static func preview(url: URL) -> CGImage? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return preview(data: data)
    }
    static func normalize(_ data: Data) throws -> Data {
        guard data.count <= 30 * 1024 * 1024, let image = preview(data: data) else {
            throw NutritionError.message("Choose a valid image smaller than 30 MB.")
        }
        let output = NSMutableData()
        guard let target = CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil) else {
            throw NutritionError.invalidEstimate
        }
        CGImageDestinationAddImage(target, image, [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary)
        guard CGImageDestinationFinalize(target) else { throw NutritionError.invalidEstimate }
        return output as Data
    }
}

#if os(iOS)
private struct NutritionCamera: UIViewControllerRepresentable {
    @Environment(\.dismiss) var dismiss
    var onPhoto: (UIImage) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera; picker.delegate = context.coordinator
        return picker
    }
    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) { }
    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: NutritionCamera
        init(_ parent: NutritionCamera) { self.parent = parent }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage { parent.onPhoto(image) }
            else { parent.dismiss() }
        }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
    }
}
#endif
