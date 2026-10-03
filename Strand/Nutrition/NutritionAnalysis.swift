import Foundation

// Uses the coach's selected provider/model and provider-owned Keychain credential. No health context.
enum NutritionAnalysis {
    // Match Android: do not forward a meal photo or credential to a redirect destination.
    static let session = URLSession(configuration: .ephemeral, delegate: NutritionRedirectGuard(), delegateQueue: nil)
    static func analyze(jpeg: Data, notes: String, provider: AIProvider, model: String,
                        session: URLSession = NutritionAnalysis.session) async throws -> NutritionMeal {
        let stored = AIKeyStore.read()
        let owner = AIKeyStore.ownerProvider
        let key = (owner == provider.rawValue || (owner == nil && provider != .custom)) ? stored : nil
        guard provider == .custom || key != nil else { throw AICoachError.noKey }
        guard !model.isEmpty else { throw NutritionError.message("Select a vision-capable model in Coach settings.") }
        let image = jpeg.base64EncodedString()
        let text = "Meal notes: " + String(notes.prefix(4000))
        var req: URLRequest
        var body: [String: Any]
        switch provider {
        case .openAI, .custom:
            if provider == .custom { try AIProvider.guardCustomBaseURL() }
            req = URLRequest(url: provider.endpoint)
            if provider == .custom { AIProvider.applyCustomAuthHeader(key ?? "", to: &req) }
            else { req.setValue("Bearer \(key!)", forHTTPHeaderField: "Authorization") }
            body = ["model": model, (provider == .custom ? "max_tokens" : "max_completion_tokens"): 4096, "messages": [
                ["role": "system", "content": NutritionMeal.prompt],
                ["role": "user", "content": [
                    ["type": "text", "text": text],
                    ["type": "image_url", "image_url": ["url": "data:image/jpeg;base64," + image]]
                ]]
            ]]
        case .anthropic:
            req = URLRequest(url: provider.endpoint)
            req.setValue(key!, forHTTPHeaderField: "x-api-key")
            req.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
            body = ["model": model, "max_tokens": 4096, "system": NutritionMeal.prompt,
                    "messages": [["role": "user", "content": [
                        ["type": "image", "source": ["type": "base64", "media_type": "image/jpeg", "data": image]],
                        ["type": "text", "text": text]
                    ]]]]
        case .gemini:
            guard let url = URL(string: provider.endpoint.absoluteString + "/" + model + ":generateContent") else {
                throw NutritionError.invalidEstimate
            }
            req = URLRequest(url: url)
            req.setValue(key!, forHTTPHeaderField: "x-goog-api-key")
            body = ["system_instruction": ["parts": [["text": NutritionMeal.prompt]]],
                    "contents": [["role": "user", "parts": [
                        ["inline_data": ["mime_type": "image/jpeg", "data": image]], ["text": text]
                    ]]], "generationConfig": ["maxOutputTokens": 4096]]
        }
        req.httpMethod = "POST"; req.timeoutInterval = 90
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let obj = try await performRequest(req, session: session)
        let reply: String?
        switch provider {
        case .openAI, .custom:
            let choice = (obj["choices"] as? [[String: Any]])?.first
            guard choice?["finish_reason"] as? String != "length" else { throw NutritionError.invalidEstimate }
            reply = (choice?["message"] as? [String: Any])?["content"] as? String
        case .anthropic:
            guard obj["stop_reason"] as? String != "max_tokens" else { throw NutritionError.invalidEstimate }
            reply = (obj["content"] as? [[String: Any]])?.compactMap { $0["text"] as? String }.joined()
        case .gemini:
            let candidate = (obj["candidates"] as? [[String: Any]])?.first
            guard candidate?["finishReason"] as? String == "STOP" else { throw NutritionError.invalidEstimate }
            reply = ((candidate?["content"] as? [String: Any])?["parts"] as? [[String: Any]])?
                .filter { ($0["thought"] as? Bool) != true }.compactMap { $0["text"] as? String }.joined()
        }
        guard let reply, !reply.isEmpty else { throw NutritionError.invalidEstimate }
        var meal = try NutritionMeal.parseEstimate(reply)
        meal.provider = provider.rawValue; meal.model = model
        return meal
    }
}

private final class NutritionRedirectGuard: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
