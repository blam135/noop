import SwiftUI
import Charts
import Foundation
import StrandDesign
import StrandAnalytics
import WhoopStore

/// Running history and training volume over the existing workout capture and storage lifecycle.
struct RunsView: View {
    @EnvironmentObject private var repo: Repository
    @EnvironmentObject private var model: AppModel
    @AppStorage(UnitPrefs.systemKey) private var unitSystemRaw = UnitSystem.metric.rawValue
    @State private var allRows: [WorkoutRow]
    @State private var routes: [String: WorkoutRoute] = [:]
    @State private var loaded: Bool
    @State private var range: RunRange = .month
    @State private var showLiveWorkout = false
    @State private var detail: RunDetailTarget?
    private let usesPreviewRows: Bool

    private var unitSystem: UnitSystem { UnitSystem(rawValue: unitSystemRaw) ?? .metric }

    private enum RunRange: Int, CaseIterable {
        case week = 7, month = 30, quarter = 90

        var label: String {
            switch self {
            case .week: return String(localized: "7D")
            case .month: return String(localized: "30D")
            case .quarter: return String(localized: "90D")
            }
        }
    }

    private struct RunDetailTarget: Identifiable {
        let row: WorkoutRow
        let id = UUID()
    }

    private struct WeeklyDistance: Identifiable {
        let start: Date
        var meters: Double = 0
        var id: Date { start }
    }

    init(previewRows: [WorkoutRow]? = nil) {
        _allRows = State(initialValue: previewRows ?? [])
        _loaded = State(initialValue: previewRows != nil)
        usesPreviewRows = previewRows != nil
    }

    var body: some View {
        let now = Date()
        let cutoff = rangeStart(at: now)
        let rows = allRows.filter {
            RunAnalysis.isRunningSport($0.sport)
                && Date(timeIntervalSince1970: Double($0.startTs)) >= cutoff
                && Date(timeIntervalSince1970: Double($0.startTs)) <= now
        }.sorted { $0.startTs > $1.startTs }
        let summary = RunAnalysis.summary(rows.map(session))

        ScreenScaffold(title: "Runs", subtitle: "Record your route. Understand your running.",
                       onRefresh: { await reload() }, lazy: true,
                       topBackground: liquidScaffoldSky()) {
            runAction
            SegmentedPillControl(RunRange.allCases, selection: $range,
                                 fillsAvailableWidth: true) { $0.label }

            if !loaded {
                ComingSoon(what: "Loading your runs…")
            } else if rows.isEmpty {
                emptyState
            } else {
                summarySection(summary, rows: rows)
                weeklyChart(weeklyDistances(rows: rows, from: cutoff, through: now))
                recentRuns(rows)
            }
        }
        .task(id: repo.refreshSeq) { await reload() }
        .sheet(item: $detail) { target in
            NavigationStack {
                WorkoutDetailView(row: target.row)
                    .environmentObject(repo)
            }
            #if os(iOS)
            .noopSheetPresentation(largeFirst: true)
            #else
            .frame(minWidth: NoopMetrics.detailSheetMinWidth,
                   minHeight: NoopMetrics.detailSheetMinHeight)
            #endif
        }
        .sheet(isPresented: $showLiveWorkout, onDismiss: {
            Task { await reload() }
        }) {
            LiveWorkoutView(onClose: { showLiveWorkout = false })
                .environmentObject(model)
                .environmentObject(repo)
                .environmentObject(model.live)
        }
    }

    private var runAction: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space2) {
            NoopButton(model.activeWorkout == nil ? "Start run" : "View active workout",
                       systemImage: model.activeWorkout == nil ? "figure.run" : "timer",
                       fullWidth: true) {
                // Any existing session remains owned by AppModel; opening this hub never replaces it.
                if model.activeWorkout == nil { model.startWorkout(sport: "Running") }
                showLiveWorkout = true
            }
            Text("Outdoor runs record GPS when you allow location access. Heart rate is added when a sensor is connected.")
                .font(StrandFont.footnote)
                .foregroundStyle(StrandPalette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var emptyState: some View {
        NoopCard(tint: StrandPalette.effortColor) {
            VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                Label("No runs in this period", systemImage: "figure.run")
                    .font(StrandFont.title2)
                    .foregroundStyle(StrandPalette.textPrimary)
                Text("Start a run to record your route, distance, and pace. Your running history stays on this device.")
                    .font(StrandFont.body)
                    .foregroundStyle(StrandPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func summarySection(_ summary: RunAnalysis.Summary, rows: [WorkoutRow]) -> some View {
        VStack(alignment: .leading, spacing: NoopMetrics.gap) {
            SectionHeader("Running overview")
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())],
                      spacing: NoopMetrics.gap) {
                StatTile(label: "Runs", value: String(summary.count),
                         accent: StrandPalette.effortBright)
                StatTile(label: "Distance", value: rows.contains { validPositive($0.distanceM) }
                         ? UnitFormatter.distanceFromKilometers(summary.totalDistanceM / 1000, system: unitSystem)
                         : "—", accent: StrandPalette.metricCyan)
                StatTile(label: "Active time", value: rows.contains { validPositive($0.durationS) }
                         ? durationLabel(summary.totalDurationS) : "—",
                         accent: StrandPalette.textPrimary)
                StatTile(label: "Avg pace",
                         value: UnitFormatter.paceFromSecPerKm(summary.paceSecPerKm, system: unitSystem),
                         accent: StrandPalette.effortBright)
            }
            Text("Average pace uses runs with both recorded distance and active time.")
                .font(StrandFont.footnote)
                .foregroundStyle(StrandPalette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func weeklyChart(_ weeks: [WeeklyDistance]) -> some View {
        let unit = UnitFormatter.distanceUnit(unitSystem)
        let upperBound = max(1, (weeks.map { chartDistance($0.meters) }.max() ?? 0) * 1.1)
        return ChartCard(title: "Weekly distance", subtitle: String(localized: "Distance per calendar week"),
                         trailing: unit, tint: StrandPalette.effortColor) {
            Chart(weeks) { week in
                BarMark(x: .value(String(localized: "Week"), week.start, unit: .weekOfYear),
                        y: .value(String(localized: "Distance"), chartDistance(week.meters)))
                    .foregroundStyle(StrandPalette.effortBright)
                    .accessibilityLabel(week.start.formatted(.dateTime.month(.abbreviated).day()))
                    .accessibilityValue(UnitFormatter.distanceFromKilometers(week.meters / 1000, system: unitSystem))
            }
            .chartYScale(domain: 0...upperBound)
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 4)) { _ in
                    AxisValueLabel(format: .dateTime.month(.abbreviated).day())
                        .foregroundStyle(StrandPalette.textTertiary)
                }
            }
            .chartYAxis {
                AxisMarks(position: .leading, values: .automatic(desiredCount: 4)) { _ in
                    AxisGridLine().foregroundStyle(StrandPalette.hairline)
                    AxisValueLabel().foregroundStyle(StrandPalette.textTertiary)
                }
            }
            .accessibilityLabel("Weekly running distance")
        }
    }

    private func recentRuns(_ rows: [WorkoutRow]) -> some View {
        VStack(alignment: .leading, spacing: NoopMetrics.gap) {
            SectionHeader("Recent runs")
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                Button { detail = RunDetailTarget(row: row) } label: {
                    NoopCard(tint: StrandPalette.effortColor) {
                        VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                            HStack(spacing: NoopMetrics.space2) {
                                VStack(alignment: .leading, spacing: NoopMetrics.space1) {
                                    Text(WorkoutSource.displaySport(row.sport))
                                        .font(StrandFont.body)
                                        .foregroundStyle(StrandPalette.textPrimary)
                                    Text(Date(timeIntervalSince1970: Double(row.startTs))
                                        .formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day().hour().minute()))
                                        .font(StrandFont.footnote)
                                        .foregroundStyle(StrandPalette.textTertiary)
                                }
                                Spacer(minLength: NoopMetrics.space2)
                                Image(systemName: "chevron.right")
                                    .font(StrandFont.footnote)
                                    .foregroundStyle(StrandPalette.textTertiary)
                            }
                            HStack(alignment: .top, spacing: NoopMetrics.space3) {
                                runMetric("Distance", value: distanceLabel(row.distanceM))
                                runMetric("Active time", value: durationLabel(row.durationS))
                                runMetric("Avg pace", value: UnitFormatter.paceFromSecPerKm(
                                    RunAnalysis.summary([session(row)]).paceSecPerKm, system: unitSystem))
                            }
                        }
                    }
                }
                .buttonStyle(.plain)
                .accessibilityHint("Open run analysis and route")
            }
        }
    }

    private func runMetric(_ title: LocalizedStringKey, value: String) -> some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space1) {
            Text(title).font(StrandFont.footnote).foregroundStyle(StrandPalette.textTertiary)
            Text(value).font(StrandFont.captionNumber).foregroundStyle(StrandPalette.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func reload() async {
        guard !usesPreviewRows else { return }
        let rows = await repo.workoutRows(days: 90)
        guard !Task.isCancelled else { return }
        // Decode the route side-store once per refresh, keeping per-row rendering in memory.
        routes = RouteStore.loadMap()
        allRows = rows
        loaded = true
    }

    private func session(_ row: WorkoutRow) -> RunAnalysis.Session {
        let key = RouteStore.key(startTs: row.startTs, sport: row.sport)
        let recordedDuration = routes[key]?.samples?.last?.elapsedS
        return RunAnalysis.Session(startTs: row.startTs, distanceM: row.distanceM,
                                   durationS: row.durationS,
                                   paceDurationS: validPositive(recordedDuration) ? recordedDuration : nil)
    }

    private func rangeStart(at now: Date) -> Date {
        let calendar = Calendar.current
        return calendar.date(byAdding: .day, value: -(range.rawValue - 1),
                             to: calendar.startOfDay(for: now)) ?? now
    }

    private func weeklyDistances(rows: [WorkoutRow], from start: Date, through end: Date) -> [WeeklyDistance] {
        let calendar = Calendar.current
        guard let firstWeek = calendar.dateInterval(of: .weekOfYear, for: start)?.start,
              let lastWeek = calendar.dateInterval(of: .weekOfYear, for: end)?.start else { return [] }
        var totals: [Date: Double] = [:]
        for row in rows {
            guard let distance = row.distanceM, validPositive(distance),
                  let week = calendar.dateInterval(of: .weekOfYear,
                    for: Date(timeIntervalSince1970: Double(row.startTs)))?.start else { continue }
            totals[week, default: 0] += distance
        }
        var weeks: [WeeklyDistance] = []
        var cursor = firstWeek
        while cursor <= lastWeek {
            weeks.append(WeeklyDistance(start: cursor, meters: totals[cursor] ?? 0))
            guard let next = calendar.date(byAdding: .weekOfYear, value: 1, to: cursor), next > cursor else { break }
            cursor = next
        }
        return weeks
    }

    private func chartDistance(_ meters: Double) -> Double {
        unitSystem == .imperial ? UnitFormatter.kmToMiles(meters / 1000) : meters / 1000
    }

    private func validPositive(_ value: Double?) -> Bool {
        guard let value else { return false }
        return value.isFinite && value > 0
    }

    private func distanceLabel(_ meters: Double?) -> String {
        guard let meters, validPositive(meters) else { return "—" }
        return UnitFormatter.distanceFromMeters(meters, system: unitSystem)
    }

    private func durationLabel(_ seconds: Double?) -> String {
        guard let seconds, validPositive(seconds) else { return "—" }
        let formatter = DateComponentsFormatter()
        formatter.allowedUnits = seconds < 60 ? [.second] : [.hour, .minute]
        formatter.unitsStyle = .abbreviated
        formatter.maximumUnitCount = 2
        return formatter.string(from: seconds) ?? "—"
    }
}

#if DEBUG
#Preview("Runs") {
    let now = Int(Date().timeIntervalSince1970)
    RunsView(previewRows: [
        WorkoutRow(startTs: now - 86_400, endTs: now - 86_400 + 1800,
                   sport: "Running", source: "manual", durationS: 1800,
                   energyKcal: nil, avgHr: 145, maxHr: 162, strain: nil, distanceM: 5000,
                   zonesJSON: nil, notes: nil, steps: nil),
        WorkoutRow(startTs: now - 8 * 86_400, endTs: now - 8 * 86_400 + 2520,
                   sport: "Running", source: "manual", durationS: 2520,
                   energyKcal: nil, avgHr: 142, maxHr: 158, strain: nil, distanceM: 7000,
                   zonesJSON: nil, notes: nil, steps: nil),
    ])
        .environmentObject(Repository(deviceId: "preview"))
        .environmentObject(AppModel())
        .preferredColorScheme(.dark)
}

#Preview("Runs — empty") {
    RunsView(previewRows: [])
        .environmentObject(Repository(deviceId: "preview"))
        .environmentObject(AppModel())
        .preferredColorScheme(.dark)
}
#endif
