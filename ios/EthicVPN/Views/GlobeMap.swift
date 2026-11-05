import MapKit
import SwiftUI

/// Satellite globe with two pins: where the phone really is and the selected server. While
/// connected a line joins them, glowing packets run along it towards the server, and the camera
/// turns to the server, where sites now see you.
struct GlobeMap: UIViewRepresentable {
    var home: GeoIP.Location?
    var server: GeoIP.Location?
    var serverName: String
    var connected: Bool

    /// Far enough out that MapKit draws the whole globe.
    private static let distance: CLLocationDistance = 9_000_000

    func makeUIView(context: Context) -> MKMapView {
        let map = MKMapView()
        map.mapType = .hybridFlyover
        map.showsCompass = false
        map.showsScale = false
        map.pointOfInterestFilter = .excludingAll
        map.delegate = context.coordinator
        map.backgroundColor = .black
        map.camera = MKMapCamera(
            lookingAtCenter: CLLocationCoordinate2D(latitude: 55.75, longitude: 37.62),
            fromDistance: Self.distance, pitch: 0, heading: 0
        )
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        let c = context.coordinator
        c.connected = connected

        var pins: [Pin] = []
        if let home { pins.append(Pin(kind: .home, location: home, title: connected ? String(localized: "Реальный адрес") : String(localized: "Вы здесь"))) }
        if let server { pins.append(Pin(kind: .server, location: server, title: connected ? String(localized: "Вы сейчас здесь") : serverName)) }
        if pins != c.pins {
            map.removeAnnotations(c.pins)
            map.addAnnotations(pins)
            c.pins = pins
        }

        // The route is redrawn only when it changes, so its flowing animation is not restarted.
        let route = connected ? home.flatMap { h in server.map { (h, $0) } } : nil
        let routeKey = route.map { "\($0.0.latitude),\($0.0.longitude)>\($0.1.latitude),\($0.1.longitude)" }
        if routeKey != c.routeKey {
            c.routeKey = routeKey
            map.removeOverlays(map.overlays)
            if case let (from, to)? = route {
                var points = [from.coordinate, to.coordinate]
                map.addOverlay(MKGeodesicPolyline(coordinates: &points, count: 2))
                c.startPackets(on: map, from: from.coordinate, to: to.coordinate)
            } else {
                c.stopPackets(on: map)
            }
        }

        // Turn the globe only when the place to look at changes, not on every redraw.
        let target = (connected ? server ?? home : home ?? server)?.coordinate
        if let target, c.target.map({ $0.latitude != target.latitude || $0.longitude != target.longitude }) ?? true {
            c.target = target
            // Look a little south of the point, so it sits in the upper half above the panel.
            let center = CLLocationCoordinate2D(latitude: max(-80, target.latitude - 14), longitude: target.longitude)
            let camera = MKMapCamera(lookingAtCenter: center, fromDistance: Self.distance, pitch: 0, heading: 0)
            map.setCamera(camera, animated: true)
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, MKMapViewDelegate {
        var pins: [Pin] = []
        var target: CLLocationCoordinate2D?
        var connected = false
        var routeKey: String?
        private var displayLink: CADisplayLink?
        private var packets: [Packet] = []
        private var route: (CLLocationCoordinate2D, CLLocationCoordinate2D)?
        private var started = CACurrentMediaTime()

        /// Comets on the route: a bright head and a fading tail, spaced evenly.
        private static let comets = 3
        private static let tail = 4
        /// Seconds for a packet to travel from the phone to the server.
        private static let trip: CFTimeInterval = 4.5

        deinit { displayLink?.invalidate() }

        func startPackets(on map: MKMapView, from: CLLocationCoordinate2D, to: CLLocationCoordinate2D) {
            stopPackets(on: map)
            route = (from, to)
            started = CACurrentMediaTime()
            for _ in 0..<Self.comets {
                for t in 0...Self.tail {
                    packets.append(Packet(coordinate: from, rank: t))
                }
            }
            map.addAnnotations(packets)
            let link = CADisplayLink(target: self, selector: #selector(step))
            link.preferredFramesPerSecond = 30
            link.add(to: .main, forMode: .common)
            displayLink = link
        }

        func stopPackets(on map: MKMapView) {
            displayLink?.invalidate()
            displayLink = nil
            map.removeAnnotations(packets)
            packets = []
            route = nil
        }

        /// Moves each comet a little further from the phone towards the server.
        @objc private func step() {
            guard case let (from, to)? = route else { return }
            let progress = ((CACurrentMediaTime() - started) / Self.trip).truncatingRemainder(dividingBy: 1)
            for (i, packet) in packets.enumerated() {
                let comet = i / (Self.tail + 1)
                let f = progress + Double(comet) / Double(Self.comets) - Double(packet.rank) * 0.025
                packet.coordinate = Self.greatCircle(from, to, f - floor(f))
            }
        }

        /// The point a fraction `f` of the way along the great circle, the path the route line follows.
        private static func greatCircle(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D, _ f: Double) -> CLLocationCoordinate2D {
            func vec(_ c: CLLocationCoordinate2D) -> (Double, Double, Double) {
                let la = c.latitude * .pi / 180, lo = c.longitude * .pi / 180
                return (cos(la) * cos(lo), cos(la) * sin(lo), sin(la))
            }
            let va = vec(a), vb = vec(b)
            let omega = acos(max(-1, min(1, va.0 * vb.0 + va.1 * vb.1 + va.2 * vb.2)))
            guard omega > 1e-6 else { return a }
            let ka = sin((1 - f) * omega) / sin(omega), kb = sin(f * omega) / sin(omega)
            let x = ka * va.0 + kb * vb.0, y = ka * va.1 + kb * vb.1, z = ka * va.2 + kb * vb.2
            return CLLocationCoordinate2D(latitude: atan2(z, (x * x + y * y).squareRoot()) * 180 / .pi, longitude: atan2(y, x) * 180 / .pi)
        }

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            if let packet = annotation as? Packet { return PacketView(packet: packet) }
            guard let pin = annotation as? Pin else { return nil }
            let view = MKMarkerAnnotationView(annotation: pin, reuseIdentifier: nil)
            view.glyphText = pin.location.flag
            view.titleVisibility = .visible
            view.displayPriority = .required
            switch pin.kind {
            case .home: view.markerTintColor = connected ? .systemGray : UIColor(Brand.cyan)
            case .server: view.markerTintColor = connected ? UIColor(Brand.green) : .systemGray
            }
            return view
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            // A faint solid track; the packets moving along it carry the motion.
            let renderer = MKPolylineRenderer(overlay: overlay)
            renderer.strokeColor = UIColor(Brand.cyan).withAlphaComponent(0.45)
            renderer.lineWidth = 3
            renderer.lineCap = .round
            return renderer
        }
    }

    /// One dot of a comet; rank 0 is the head, higher ranks trail behind and fade.
    final class Packet: NSObject, MKAnnotation {
        @objc dynamic var coordinate: CLLocationCoordinate2D
        let rank: Int

        init(coordinate: CLLocationCoordinate2D, rank: Int) {
            self.coordinate = coordinate
            self.rank = rank
        }
    }

    final class PacketView: MKAnnotationView {
        init(packet: Packet) {
            super.init(annotation: packet, reuseIdentifier: nil)
            let size: CGFloat = packet.rank == 0 ? 12 : 9 - CGFloat(packet.rank) * 1.5
            frame = CGRect(x: 0, y: 0, width: size, height: size)
            backgroundColor = packet.rank == 0 ? .white : UIColor(Brand.cyan)
            alpha = packet.rank == 0 ? 1 : 0.85 - CGFloat(packet.rank) * 0.17
            layer.cornerRadius = size / 2
            // The head glows in the accent colour.
            layer.shadowColor = UIColor(Brand.cyan).cgColor
            layer.shadowRadius = packet.rank == 0 ? 8 : 3
            layer.shadowOpacity = 1
            layer.shadowOffset = .zero
            displayPriority = .required
            collisionMode = .none
            isUserInteractionEnabled = false
            zPriority = .min
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) { fatalError() }
    }

    final class Pin: NSObject, MKAnnotation {
        enum Kind { case home, server }
        let kind: Kind
        let location: GeoIP.Location
        let title: String?

        init(kind: Kind, location: GeoIP.Location, title: String) {
            self.kind = kind
            self.location = location
            self.title = title
        }

        var coordinate: CLLocationCoordinate2D { location.coordinate }

        override func isEqual(_ object: Any?) -> Bool {
            guard let other = object as? Pin else { return false }
            return kind == other.kind && location == other.location && title == other.title
        }

        override var hash: Int { title?.hashValue ?? 0 }
    }
}

extension GeoIP.Location {
    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: latitude, longitude: longitude) }
}
