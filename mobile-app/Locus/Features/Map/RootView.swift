import SwiftUI
import NetworkExtension
import MapKit

// GeoPort mobile MVP, adapted from Locus (MIT). See MOBILE-README.md.
struct RootView: View {
    @EnvironmentObject private var session: SpoofSession
    @EnvironmentObject private var pairing: PairingStore
    @State private var showSettings = false
    @State private var latitude = "25.0330"
    @State private var longitude = "121.5654"
    @State private var camera: MapCameraPosition = .automatic
    @State private var query = ""
    @State private var results: [SavedPlace] = []
    @State private var searchMessage = ""
    @State private var searching = false
    @State private var activeSearch: MKLocalSearch?
    @State private var searchID = UUID()
    @State private var selectedName = ""
    @State private var satellite = false
    @State private var favoriteName = ""
    @State private var showFavorite = false
    @FocusState private var editing: Bool

    var body: some View {
        NavigationStack {
            ZStack(alignment: .top) {
                    MapReader { proxy in
                        let content = Map(position: $camera) {
                            if let pin = session.pin {
                                Marker(selectedName.isEmpty ? "選定位置" : selectedName, coordinate: pin)
                            }
                            if let simulated = session.simulated {
                                Annotation("模擬位置", coordinate: simulated) {
                                    Image(systemName: "location.circle.fill")
                                        .font(.title).foregroundStyle(.orange)
                                }
                            }
                        }
                        Group {
                            if satellite { content.mapStyle(.hybrid) }
                            else { content.mapStyle(.standard) }
                        }
                        .onTapGesture { point in
                            if let coordinate = proxy.convert(point, from: .local) {
                                choose(coordinate, name: "地圖選點", moveCamera: false)
                            }
                        }
                    }

                VStack {
            ScrollView {
                VStack(spacing: 16) {
                    HStack {
                        TextField("搜尋城市、地址或地標，例如東京車站", text: $query)
                            .textFieldStyle(.roundedBorder)
                            .submitLabel(.search)
                            .focused($editing)
                            .onSubmit { searchPlaces() }
                        Button(action: searchPlaces) {
                            Image(systemName: "magnifyingglass")
                        }.disabled(query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                    if searching { ProgressView("搜尋中…") }
                    if !searchMessage.isEmpty {
                        Text(searchMessage).font(.footnote).foregroundStyle(.secondary)
                    }
                    ForEach(results) { place in
                        Button {
                            choose(place.coordinate, name: place.name)
                            results = []
                            editing = false
                        } label: {
                            VStack(alignment: .leading) {
                                Text(place.name)
                                Text(String(format: "%.5f, %.5f", place.latitude, place.longitude))
                                    .font(.caption).foregroundStyle(.secondary)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                        }.buttonStyle(.bordered)
                    }
                    HStack {
                        Button { satellite.toggle() } label: {
                            Label("地圖樣式", systemImage: "map")
                        }
                        Spacer()
                        Button { showSettings = true } label: {
                            Label("設定", systemImage: "gearshape")
                        }
                    }
                }.padding(12)
            }
            .frame(maxHeight: results.isEmpty ? 125 : 280)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20))
            Spacer()
            VStack(spacing: 12) {
                    StatusBarView()
                    if !selectedName.isEmpty { Text(selectedName).font(.headline) }
                    HStack {
                    TextField("緯度 -90～90", text: $latitude)
                        .keyboardType(.numbersAndPunctuation)
                        .textFieldStyle(.roundedBorder)
                        .accessibilityLabel("緯度")
                    TextField("經度 -180～180", text: $longitude)
                        .keyboardType(.numbersAndPunctuation)
                        .textFieldStyle(.roundedBorder)
                        .accessibilityLabel("經度")
                    }
                    HStack {
                        Button("查看座標") {
                            if let coordinate = enteredCoordinate() { choose(coordinate, name: "座標位置") }
                        }
                        Button("收藏座標") {
                            if enteredCoordinate() != nil {
                                favoriteName = selectedName
                                showFavorite = true
                            }
                        }
                        Menu("已存地點") {
                            Section("收藏") {
                                ForEach(session.favorites) { place in
                                    Button(place.name) { choose(place.coordinate, name: place.name) }
                                }
                            }
                            Section("最近使用") {
                                ForEach(session.recents) { place in
                                    Button(place.name) { choose(place.coordinate, name: place.name) }
                                }
                            }
                        }.disabled(session.favorites.isEmpty && session.recents.isEmpty)
                    }.buttonStyle(.bordered)
                    if session.simulated != nil {
                        Button {
                            session.stop(pairing: pairing)
                        } label: {
                            Label("停止模擬 GPS", systemImage: "stop.fill")
                                .font(.title2.bold()).frame(maxWidth: .infinity).padding(.vertical, 12)
                        }
                        .buttonStyle(.borderedProminent).tint(.red)
                        .disabled(session.isBusy)
                        Button("更新至選定位置") {
                            guard let coordinate = enteredCoordinate() else { return }
                            session.teleport(to: coordinate, pairing: pairing)
                        }.disabled(session.isBusy)
                    } else {
                        Button {
                            guard pairing.hasPairingFile else { showSettings = true; return }
                            guard let coordinate = enteredCoordinate() else { return }
                            editing = false
                            session.teleport(to: coordinate, pairing: pairing)
                        } label: {
                            Label(pairing.hasPairingFile ? "開始模擬 GPS" : "設定配對並開始", systemImage: "play.fill")
                                .font(.title2.bold()).frame(maxWidth: .infinity).padding(.vertical, 12)
                        }
                        .buttonStyle(.borderedProminent).tint(.purple)
                        .disabled(session.isBusy)
                    }
                }.padding(16)
                 .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 24))
                }.padding(12)
            }
            .navigationBarHidden(true)
            .onAppear {
                if session.pin == nil, let coordinate = enteredCoordinate() {
                    choose(coordinate, name: "選定位置")
                }
            }
            .onDisappear {
                activeSearch?.cancel()
                searchID = UUID()
                searching = false
            }
            .alert("收藏地點", isPresented: $showFavorite) {
                TextField("地點名稱", text: $favoriteName)
                Button("儲存") {
                    if let coordinate = enteredCoordinate() {
                        let name = favoriteName.trimmingCharacters(in: .whitespacesAndNewlines)
                        session.addFavorite(name: name.isEmpty ? "\(latitude), \(longitude)" : name, coordinate: coordinate)
                    }
                }
                Button("取消", role: .cancel) {}
            }
            .toolbar { Button("配對與設定") { showSettings = true } }
            .sheet(isPresented: $showSettings) { SettingsView() }
            .alert("GeoPort Mobile", isPresented: Binding(
                get: { session.lastError != nil },
                set: { if !$0 { session.lastError = nil } }
            )) { Button("確定", role: .cancel) { session.lastError = nil } }
            message: { Text(session.lastError ?? "") }
        }
    }

    private func enteredCoordinate() -> CLLocationCoordinate2D? {
        let latText = latitude.trimmingCharacters(in: .whitespacesAndNewlines)
        let lngText = longitude.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let lat = Double(latText), let lng = Double(lngText),
              lat.isFinite, lng.isFinite,
              (-90...90).contains(lat), (-180...180).contains(lng) else {
            session.lastError = "請輸入有效經緯度：緯度 -90～90、經度 -180～180。"
            return nil
        }
        return CLLocationCoordinate2D(latitude: lat, longitude: lng)
    }

    private func choose(_ coordinate: CLLocationCoordinate2D, name: String, moveCamera: Bool = true) {
        guard CLLocationCoordinate2DIsValid(coordinate) else { return }
        session.pin = coordinate
        latitude = String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), coordinate.latitude)
        longitude = String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), coordinate.longitude)
        selectedName = name
        if moveCamera {
            camera = .region(MKCoordinateRegion(center: coordinate,
                span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)))
        }
    }

    private func searchPlaces() {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        activeSearch?.cancel()
        let id = UUID()
        searchID = id
        searching = true
        results = []
        searchMessage = ""
        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = text
        // No fixed Taipei region: let the query identify a city/country worldwide.
        let search = MKLocalSearch(request: request)
        activeSearch = search
        Task { @MainActor in
            do {
                let response = try await search.start()
                guard searchID == id else { return }
                var seen = Set<String>()
                results = response.mapItems.compactMap { item in
                    let coordinate = item.placemark.coordinate
                    guard CLLocationCoordinate2DIsValid(coordinate) else { return nil }
                    let place = SavedPlace(name: item.name ?? "搜尋結果",
                        latitude: coordinate.latitude, longitude: coordinate.longitude)
                    return seen.insert(place.id).inserted ? place : nil
                }
                searchMessage = results.isEmpty ? "找不到結果，請加上城市或國家名稱，或直接輸入座標。" : "選擇結果只會移動地圖；按開始或更新才會送到手機。"
            } catch {
                guard searchID == id else { return }
                searchMessage = "搜尋失敗：\(error.localizedDescription)。仍可直接輸入座標。"
            }
            if searchID == id { searching = false; activeSearch = nil }
        }
    }
}

struct StatusBarView: View {
    @EnvironmentObject private var session: SpoofSession
    @Environment(\.scenePhase) private var scenePhase

    @State private var tunnelConnected = LocalDevVPN.isConnected

    private enum Display {
        case notSpoofing
        case connectVPN
        case status(String)
    }

    private var display: Display {
        switch session.status {
        case .idle:
            return tunnelConnected ? .notSpoofing : .connectVPN
        case .connecting:
            return .status("連接中…")
        case .active:
            return .status("正在模擬定位")
        case .reconnecting:
            return .status("重新連接中…")
        case .dropped(let reason):
            return .status(reason.isEmpty ? "已中斷連線" : "Disconnected — \(reason)")
        }
    }

    private var color: Color {
        switch display {
        case .notSpoofing:
            return Color.primary.opacity(0.55)
        case .connectVPN:
            return LocusTheme.statusWarn
        case .status:
            switch session.status {
            case .active: return LocusTheme.statusGood
            case .connecting, .reconnecting: return LocusTheme.statusWarn
            case .dropped: return LocusTheme.statusBad
            case .idle: return Color.primary.opacity(0.55)
            }
        }
    }

    private var title: String {
        switch display {
        case .notSpoofing: return "尚未模擬定位"
        case .connectVPN: return "連接 LocalDevVPN"
        case .status(let text): return text
        }
    }

    var body: some View {
        Group {
            if case .connectVPN = display {
                Button(action: LocalDevVPN.openOrInstall) {
                    statusContent
                }
                .buttonStyle(.plain)
            } else {
                statusContent
            }
        }
        .onAppear { refreshTunnel() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { refreshTunnel() }
        }
        .onChange(of: session.status) { _, _ in
            refreshTunnel()
        }
        .onReceive(NotificationCenter.default.publisher(for: .NEVPNStatusDidChange)) { _ in
            // LocalDevVPN connection changes show up here even though we don’t own the VPN.
            refreshTunnel()
        }
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                refreshTunnel()
            }
        }
    }

    private var statusContent: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(color)
                .frame(width: 8, height: 8)
                .shadow(color: color.opacity(0.7), radius: 4)

            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)

            Spacer(minLength: 8)

            if case .connectVPN = display {
                Image(systemName: "lock.shield.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(LocusTheme.accent)
            } else if case .active = session.status, let sim = session.simulated {
                Text(String(format: "%.4f, %.4f", sim.latitude, sim.longitude))
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .locusGlass(.clear, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private func refreshTunnel() {
        tunnelConnected = LocalDevVPN.isConnected
    }
}

struct BottomControlsView: View {
    @EnvironmentObject private var session: SpoofSession
    @EnvironmentObject private var pairing: PairingStore
    @Binding var showSettings: Bool
    @Binding var showPlaces: Bool

    private let trayShape = RoundedRectangle(cornerRadius: 28, style: .continuous)

    var body: some View {
        VStack(spacing: 12) {
            if session.joystickActive {
                JoystickPad { vector in
                    session.updateJoystick(vector: vector)
                }
                .frame(width: 148, height: 148)
                .frame(maxWidth: .infinity, alignment: .trailing)
            }

            HStack(spacing: 8) {
                ForEach(TravelMode.allCases) { mode in
                    let selected = session.travelMode == mode
                    Button {
                        session.travelMode = mode
                    } label: {
                        Image(systemName: mode.icon)
                            .font(.body.weight(.semibold))
                            .foregroundStyle(selected ? .black : .primary)
                            .frame(width: 44, height: 40)
                            .background(
                                Capsule().fill(selected ? LocusTheme.accent : Color.primary.opacity(0.08))
                            )
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
                Spacer(minLength: 0)
            }

            HStack(spacing: 10) {
                trayIcon("gearshape.fill") { showSettings = true }
                trayIcon("star.fill") { showPlaces = true }

                Button {
                    if session.joystickActive {
                        session.stopJoystick()
                    } else {
                        session.startJoystick(pairing: pairing)
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "dot.circle.and.hand.point.up.left.fill")
                        Text(session.joystickActive ? "On" : "Joy")
                            .lineLimit(1)
                    }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(session.joystickActive ? .black : .primary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(
                        Capsule().fill(session.joystickActive ? LocusTheme.accentSecondary : Color.primary.opacity(0.08))
                    )
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)

                if session.isSpoofing {
                    Button {
                        session.stop(pairing: pairing)
                    } label: {
                        Text("Stop")
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(.white)
                            .frame(minWidth: 72)
                            .padding(.vertical, 12)
                            .padding(.horizontal, 8)
                            .background(Capsule().fill(LocusTheme.danger))
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                } else {
                    Button {
                        guard let pin = session.pin else {
                            session.lastError = "Tap the map to drop a pin first."
                            return
                        }
                        session.teleport(to: pin, pairing: pairing)
                    } label: {
                        Text("Teleport")
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(.black)
                            .frame(minWidth: 96)
                            .padding(.vertical, 12)
                            .padding(.horizontal, 10)
                            .background(Capsule().fill(LocusTheme.accent))
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(session.isBusy)
                }
            }
        }
        .padding(14)
        .locusGlass(.regular, in: trayShape)
        // Whole tray absorbs taps so near-misses don't fall through to the map.
        .contentShape(trayShape)
    }

    private func trayIcon(_ systemName: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.body.weight(.semibold))
                .foregroundStyle(.primary)
                .frame(width: 44, height: 44)
                .background(Circle().fill(Color.primary.opacity(0.08)))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
    }
}

struct IconButton: View {
    let systemName: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.body.weight(.semibold))
                .frame(width: 44, height: 44)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .locusGlass(.interactive, in: Circle())
        .foregroundStyle(.primary)
    }
}
