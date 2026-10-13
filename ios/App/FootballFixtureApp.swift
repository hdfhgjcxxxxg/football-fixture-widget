import SwiftUI
import UIKit

@main
struct FootballFixtureApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

struct ContentView: View {
    @State private var team1 = "Arsenal"
    @State private var team2 = "Japan"
    @State private var textSize = 50.0
    @State private var useNothingStyle = true
    @State private var snapshots: [TeamSnapshot] = [.placeholder("Arsenal", id: 1), .placeholder("Japan", id: 2)]
    @State private var loading = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    preview
                    settings
                }
                .padding(16)
            }
            .navigationTitle("Football Fixture")
            .task { await refresh() }
        }
    }

    private var preview: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("NEXT MATCHES").font(.headline.weight(.heavy))
                    Text("iOS / Liquid Glass").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Button { Task { await refresh() } } label: {
                    Image(systemName: loading ? "hourglass" : "arrow.clockwise")
                        .font(.title3.bold()).frame(width: 38, height: 38)
                }
                .buttonStyle(.plain)
                .glassEffect(.regular, in: .circle)
                .disabled(loading)
            }
            ForEach(snapshots) { team in
                AppTeamRow(team: team, scale: sizeScale, nothing: useNothingStyle)
            }
            HStack {
                Spacer()
                Text("結合  ・  更新 \(Date().formatted(date: .numeric, time: .shortened))")
                    .font(.system(size: 10 * sizeScale, design: .monospaced))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(14)
        .glassEffect(.regular, in: .rect(cornerRadius: 28))
    }

    private var settings: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("設定").font(.headline)
            TextField("チーム1", text: $team1).textFieldStyle(.roundedBorder)
            TextField("チーム2", text: $team2).textFieldStyle(.roundedBorder)
            Toggle("Nothing風モノスペース", isOn: $useNothingStyle)
            VStack(alignment: .leading) {
                HStack { Text("文字サイズ"); Spacer(); Text("\(Int(textSize))") }
                Slider(value: $textSize, in: 1...100, step: 1)
            }
            Text("ホーム画面ウィジェットは長押し →「ウィジェットを編集」でチーム名・文字サイズ・フォントを個別設定できます。")
                .font(.footnote).foregroundStyle(.secondary)
        }
        .padding(16)
        .glassEffect(.regular, in: .rect(cornerRadius: 24))
    }

    private var sizeScale: CGFloat { 0.62 + CGFloat(textSize / 100.0) * 0.76 }

    @MainActor
    private func refresh() async {
        guard !loading else { return }
        loading = true
        async let a = FootballService.shared.snapshot(for: team1)
        async let b = FootballService.shared.snapshot(for: team2)
        snapshots = await [a, b]
        loading = false
    }
}

private struct AppTeamRow: View {
    let team: TeamSnapshot
    let scale: CGFloat
    let nothing: Bool

    var body: some View {
        HStack(spacing: 10) {
            TeamLogo(data: team.logoData).frame(width: 54, height: 54)
            VStack(alignment: .leading, spacing: 2) {
                Text(team.name).font(font(16).weight(.bold))
                if let p = team.previous {
                    Text("前 \(p.home) \(p.scoreText) \(p.away)").font(font(12).weight(.semibold)).lineLimit(1)
                }
                if let n = team.next {
                    Text("次節 \(n.home == team.name ? "vs \(n.away)" : "@ \(n.home)")").font(font(12).weight(.semibold)).lineLimit(1)
                }
                Text(formText).font(font(9)).foregroundStyle(.secondary).lineLimit(1)
                if let n = team.next {
                    Text("次節 \(n.kickoff.formatted(date: .numeric, time: .shortened))").font(font(9)).foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 4)
            if let n = team.next {
                VStack(alignment: .trailing, spacing: 2) {
                    Text("KICKOFF IN").font(font(8).weight(.bold)).foregroundStyle(.secondary)
                    Text(timerInterval: Date.now...max(n.kickoff, .now), countsDown: true)
                        .font(font(14).weight(.bold)).monospacedDigit()
                    Text("SofaScore ↗").font(font(8)).foregroundStyle(.secondary)
                }
            }
        }
        .padding(12)
        .background(.white.opacity(0.075), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private var formText: String { "直近5 " + team.form.map(String.init).joined(separator: "-") }
    private func font(_ size: CGFloat) -> Font { .system(size: size * scale, design: nothing ? .monospaced : .default) }
}

struct TeamLogo: View {
    let data: Data?
    var body: some View {
        Group {
            #if canImport(UIKit)
            if let data, let image = UIImage(data: data) { Image(uiImage: image).resizable().scaledToFit() }
            else { Image(systemName: "shield.fill").resizable().scaledToFit().padding(8).foregroundStyle(.secondary) }
            #else
            Image(systemName: "shield.fill")
            #endif
        }
    }
}
