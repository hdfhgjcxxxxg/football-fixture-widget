import Foundation

struct MatchInfo: Sendable, Hashable {
    var home: String
    var away: String
    var homeScore: Int?
    var awayScore: Int?
    var kickoff: Date
    var competition: String
    var eventID: Int
    var url: URL?

    var scoreText: String {
        if let homeScore, let awayScore { return "\(homeScore)-\(awayScore)" }
        return "-"
    }
}

struct TeamSnapshot: Sendable, Hashable, Identifiable {
    let id: Int
    var name: String
    var logoData: Data?
    var previous: MatchInfo?
    var next: MatchInfo?
    var form: [Character]

    static func placeholder(_ name: String, id: Int) -> TeamSnapshot {
        let now = Date()
        return TeamSnapshot(
            id: id,
            name: name,
            logoData: nil,
            previous: MatchInfo(home: name == "Japan" ? "Japan" : "Brighton", away: name == "Japan" ? "Uruguay" : "Arsenal", homeScore: 3, awayScore: name == "Japan" ? 1 : 0, kickoff: now.addingTimeInterval(-86400), competition: "Football", eventID: 0, url: nil),
            next: MatchInfo(home: name, away: name == "Japan" ? "Venezuela" : "Leeds", homeScore: nil, awayScore: nil, kickoff: now.addingTimeInterval(name == "Japan" ? 46 * 3600 : 335 * 3600), competition: "Football", eventID: 0, url: nil),
            form: Array("WDLWW")
        )
    }
}

actor FootballService {
    static let shared = FootballService()
    private let session: URLSession

    init() {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 12
        cfg.httpAdditionalHeaders = [
            "Accept": "application/json",
            "User-Agent": "Mozilla/5.0 FootballFixtureWidget/1.0",
            "Referer": "https://www.sofascore.com/"
        ]
        session = URLSession(configuration: cfg)
    }

    func snapshot(for teamName: String) async -> TeamSnapshot {
        do {
            let team = try await resolveTeam(named: teamName)
            async let previous = fetchEvent(teamID: team.id, direction: "last")
            async let next = fetchEvent(teamID: team.id, direction: "next")
            async let logo = fetchData(URL(string: "https://img.sofascore.com/api/v1/team/\(team.id)/image")!)
            let p = try? await previous
            let n = try? await next
            let d = try? await logo
            let form = await deriveForm(teamID: team.id)
            return TeamSnapshot(id: team.id, name: team.displayName, logoData: d, previous: p, next: n, form: form)
        } catch {
            return .placeholder(teamName, id: abs(teamName.hashValue % 100000) + 1)
        }
    }

    private func resolveTeam(named query: String) async throws -> (id: Int, displayName: String) {
        var components = URLComponents(string: "https://api.sofascore.com/api/v1/search/all")!
        components.queryItems = [URLQueryItem(name: "q", value: query)]
        let root = try await json(components.url!)
        var candidates: [(Int, String, Int)] = []
        collectTeams(root, query: query, into: &candidates)
        guard let best = candidates.sorted(by: { $0.2 > $1.2 }).first else { throw URLError(.cannotParseResponse) }
        return (best.0, best.1)
    }

    private func collectTeams(_ value: Any, query: String, into out: inout [(Int, String, Int)]) {
        if let dict = value as? [String: Any] {
            if let id = int(dict["id"]), let name = string(dict["name"]), isLikelyTeam(dict) {
                out.append((id, name, relevance(query, name)))
            }
            for child in dict.values { collectTeams(child, query: query, into: &out) }
        } else if let array = value as? [Any] {
            for child in array { collectTeams(child, query: query, into: &out) }
        }
    }

    private func isLikelyTeam(_ dict: [String: Any]) -> Bool {
        if let type = string(dict["type"]), type.lowercased().contains("team") { return true }
        if dict["teamColors"] != nil || dict["national"] != nil || dict["gender"] != nil { return true }
        if let sport = dict["sport"] as? [String: Any], string(sport["slug"]) == "football" { return true }
        return false
    }

    private func relevance(_ a: String, _ b: String) -> Int {
        let aa = normalize(a), bb = normalize(b)
        if aa == bb { return 1000 }
        if bb.hasPrefix(aa) { return 700 }
        if bb.contains(aa) { return 500 }
        return max(0, 200 - abs(bb.count - aa.count))
    }

    private func normalize(_ s: String) -> String {
        s.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: .current)
            .replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "-", with: "")
    }

    private func fetchEvent(teamID: Int, direction: String) async throws -> MatchInfo? {
        let url = URL(string: "https://api.sofascore.com/api/v1/team/\(teamID)/events/\(direction)/0")!
        let root = try await json(url)
        guard let dict = root as? [String: Any], let events = dict["events"] as? [[String: Any]], !events.isEmpty else { return nil }
        let event = direction == "last" ? events.first! : events.first!
        return parseEvent(event)
    }

    private func deriveForm(teamID: Int) async -> [Character] {
        guard let url = URL(string: "https://api.sofascore.com/api/v1/team/\(teamID)/events/last/0"),
              let root = try? await json(url) as? [String: Any],
              let events = root["events"] as? [[String: Any]] else { return [] }
        return Array(events.prefix(5).compactMap { event -> Character? in
            guard let home = event["homeTeam"] as? [String: Any], let away = event["awayTeam"] as? [String: Any],
                  let homeID = int(home["id"]), let awayID = int(away["id"]),
                  let hs = currentScore(event["homeScore"]), let as_ = currentScore(event["awayScore"]) else { return nil }
            let teamScore = homeID == teamID ? hs : (awayID == teamID ? as_ : nil)
            let oppScore = homeID == teamID ? as_ : (awayID == teamID ? hs : nil)
            guard let teamScore, let oppScore else { return nil }
            return teamScore > oppScore ? "W" : (teamScore == oppScore ? "D" : "L")
        })
    }

    private func parseEvent(_ event: [String: Any]) -> MatchInfo? {
        guard let home = event["homeTeam"] as? [String: Any],
              let away = event["awayTeam"] as? [String: Any],
              let homeName = string(home["name"]), let awayName = string(away["name"]),
              let ts = double(event["startTimestamp"]) else { return nil }
        let tournament = ((event["tournament"] as? [String: Any])?["name"] as? String) ?? "Football"
        let eid = int(event["id"]) ?? 0
        let slug = string(event["slug"]) ?? "match"
        let custom = string(event["customId"]) ?? ""
        let page: URL? = eid > 0 ? URL(string: custom.isEmpty ? "https://www.sofascore.com/football/match/\(slug)#id:\(eid)" : "https://www.sofascore.com/football/match/\(slug)/\(custom)#id:\(eid)") : nil
        return MatchInfo(home: homeName, away: awayName, homeScore: currentScore(event["homeScore"]), awayScore: currentScore(event["awayScore"]), kickoff: Date(timeIntervalSince1970: ts), competition: tournament, eventID: eid, url: page)
    }

    private func json(_ url: URL) async throws -> Any {
        var request = URLRequest(url: url)
        request.setValue("https://www.sofascore.com/", forHTTPHeaderField: "Referer")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw URLError(.badServerResponse) }
        return try JSONSerialization.jsonObject(with: data)
    }

    private func fetchData(_ url: URL) async throws -> Data {
        let (data, response) = try await session.data(from: url)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw URLError(.badServerResponse) }
        return data
    }

    private func string(_ any: Any?) -> String? {
        if let s = any as? String, !s.isEmpty { return s }
        return nil
    }
    private func int(_ any: Any?) -> Int? {
        if let n = any as? NSNumber { return n.intValue }
        if let s = any as? String { return Int(s) }
        return nil
    }
    private func double(_ any: Any?) -> Double? {
        if let n = any as? NSNumber { return n.doubleValue }
        if let s = any as? String { return Double(s) }
        return nil
    }
    private func currentScore(_ any: Any?) -> Int? {
        guard let d = any as? [String: Any] else { return nil }
        return int(d["current"]) ?? int(d["display"])
    }
}
