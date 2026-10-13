import AppIntents
import SwiftUI
import WidgetKit
#if canImport(UIKit)
import UIKit
#endif

enum FixtureFontStyle: String, AppEnum {
    case system
    case nothing
    case rounded

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "フォント"
    static let caseDisplayRepresentations: [FixtureFontStyle: DisplayRepresentation] = [
        .system: "標準",
        .nothing: "Nothing風 Mono",
        .rounded: "Rounded"
    ]
}

struct FixtureWidgetIntent: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "Football Fixture"
    static let description = IntentDescription("Android版に近い2チーム表示を設定します。")

    @Parameter(title: "チーム1") var team1: String?
    @Parameter(title: "チーム2") var team2: String?
    @Parameter(title: "文字サイズ 1〜100", inclusiveRange: (1, 100)) var textSize: Int?
    @Parameter(title: "フォント") var fontStyle: FixtureFontStyle?

    init() {
        team1 = "Arsenal"
        team2 = "Japan"
        textSize = 50
        fontStyle = .nothing
    }
}

struct FixtureEntry: TimelineEntry {
    let date: Date
    let configuration: FixtureWidgetIntent
    let teams: [TeamSnapshot]
}

struct FixtureProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> FixtureEntry {
        FixtureEntry(date: .now, configuration: FixtureWidgetIntent(), teams: [.placeholder("Arsenal", id: 1), .placeholder("Japan", id: 2)])
    }

    func snapshot(for configuration: FixtureWidgetIntent, in context: Context) async -> FixtureEntry {
        let teams = await load(configuration)
        return FixtureEntry(date: .now, configuration: configuration, teams: teams)
    }

    func timeline(for configuration: FixtureWidgetIntent, in context: Context) async -> Timeline<FixtureEntry> {
        let teams = await load(configuration)
        let entry = FixtureEntry(date: .now, configuration: configuration, teams: teams)
        return Timeline(entries: [entry], policy: .after(Date().addingTimeInterval(15 * 60)))
    }

    private func load(_ configuration: FixtureWidgetIntent) async -> [TeamSnapshot] {
        async let a = FootballService.shared.snapshot(for: configuration.team1.flatMap { $0.isEmpty ? nil : $0 } ?? "Arsenal")
        async let b = FootballService.shared.snapshot(for: configuration.team2.flatMap { $0.isEmpty ? nil : $0 } ?? "Japan")
        return await [a, b]
    }
}

struct FootballFixtureWidgetView: View {
    @Environment(\.widgetRenderingMode) private var renderingMode
    let entry: FixtureEntry

    var body: some View {
        VStack(spacing: 7) {
            HStack {
                VStack(alignment: .leading, spacing: 0) {
                    Text("NEXT MATCHES").font(font(15).weight(.heavy)).lineLimit(1)
                    Text("長押しで編集").font(font(9)).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "arrow.clockwise")
                    .font(.system(size: 18 * scale, weight: .bold))
                    .frame(width: 34, height: 34)
                    .background(.white.opacity(renderingMode == .fullColor ? 0.06 : 0.0), in: Circle())
                    .widgetAccentable()
            }

            ForEach(entry.teams.prefix(2)) { team in
                WidgetTeamRow(team: team, scale: scale, style: (entry.configuration.fontStyle ?? .nothing), renderingMode: renderingMode)
            }

            HStack {
                Spacer()
                Text("結合  ・  更新 \(entry.date.formatted(date: .numeric, time: .shortened))")
                    .font(.system(size: 7.5 * scale, design: .monospaced))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(10)
        .containerBackground(for: .widget) {
            if renderingMode == .fullColor { Color.black.opacity(0.90) }
            else { Color.clear }
        }
    }

    private var scale: CGFloat {
        let v = min(100, max(1, (entry.configuration.textSize ?? 50)))
        return 0.62 + CGFloat(v) / 100.0 * 0.76
    }

    private func font(_ size: CGFloat) -> Font { widgetFont(size: size * scale, style: (entry.configuration.fontStyle ?? .nothing)) }
}

private struct WidgetTeamRow: View {
    let team: TeamSnapshot
    let scale: CGFloat
    let style: FixtureFontStyle
    let renderingMode: WidgetRenderingMode

    var body: some View {
        Link(destination: team.next?.url ?? URL(string: "https://www.sofascore.com")!) {
            HStack(spacing: 9) {
                WidgetLogo(data: team.logoData, fullColor: renderingMode == .fullColor)
                    .frame(width: 44, height: 44)
                VStack(alignment: .leading, spacing: 1) {
                    Text(team.name).font(font(12.5).weight(.bold)).lineLimit(1)
                    if let p = team.previous {
                        Text("前 \(p.home) \(p.scoreText) \(p.away)").font(font(9.5).weight(.semibold)).lineLimit(1)
                    }
                    if let n = team.next {
                        Text("次節 \(n.home == team.name ? "vs \(n.away)" : "@ \(n.home)")").font(font(9.5).weight(.semibold)).lineLimit(1)
                    }
                    Text("直近5 " + team.form.map(String.init).joined(separator: "-")).font(font(7.5)).foregroundStyle(.secondary).lineLimit(1)
                    if let n = team.next {
                        Text("次節 \(n.kickoff.formatted(date: .numeric, time: .shortened))").font(font(8)).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                Spacer(minLength: 2)
                if let n = team.next {
                    VStack(alignment: .trailing, spacing: 1) {
                        Text("KICKOFF IN").font(font(7).weight(.bold)).foregroundStyle(.secondary)
                        Text(timerInterval: Date.now...max(n.kickoff, .now), countsDown: true)
                            .font(font(13).weight(.bold)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.72)
                        Text("SofaScore ↗").font(font(7)).foregroundStyle(.secondary)
                    }
                    .frame(minWidth: 78, alignment: .trailing)
                }
            }
            .padding(.horizontal, 9)
            .padding(.vertical, 6)
            .background(renderingMode == .fullColor ? Color.white.opacity(0.10) : Color.clear, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.plain)
        .widgetAccentable()
    }

    private func font(_ size: CGFloat) -> Font { widgetFont(size: size * scale, style: style) }
}

private struct WidgetLogo: View {
    let data: Data?
    let fullColor: Bool
    var body: some View {
        Group {
            #if canImport(UIKit)
            if let data, let image = UIImage(data: data) { Image(uiImage: image).resizable().widgetAccentedRenderingMode(fullColor ? .fullColor : .accented).scaledToFit() }
            else { Image(systemName: "shield.fill").resizable().scaledToFit().padding(7) }
            #else
            Image(systemName: "shield.fill")
            #endif
        }
    }
}

private func widgetFont(size: CGFloat, style: FixtureFontStyle) -> Font {
    switch style {
    case .system: return .system(size: size)
    case .nothing: return .system(size: size, design: .monospaced)
    case .rounded: return .system(size: size, design: .rounded)
    }
}

struct FootballFixtureWidget: Widget {
    let kind = "FootballFixtureWidget"
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: kind, intent: FixtureWidgetIntent.self, provider: FixtureProvider()) { entry in
            FootballFixtureWidgetView(entry: entry)
        }
        .configurationDisplayName("NEXT MATCHES")
        .description("前試合・次節・直近5・キックオフまでのカウントダウンを表示します。")
        .supportedFamilies([.systemMedium, .systemLarge])
        .contentMarginsDisabled()
    }
}

@main
struct FootballFixtureWidgetBundle: WidgetBundle {
    var body: some Widget { FootballFixtureWidget() }
}
