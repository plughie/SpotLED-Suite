import Foundation

enum PhotoWorkKind: Hashable {
    case loading
    case decoding
}

/// Owns cancellers for replaceable photo work in the foreground screen.
final class PhotoTaskRegistry {
    private var cancellers: [PhotoWorkKind: () -> Void] = [:]

    func replace(_ kind: PhotoWorkKind, cancel: @escaping () -> Void) {
        self.cancel(kind)
        cancellers[kind] = cancel
    }

    func cancel(_ kind: PhotoWorkKind) {
        let cancel = cancellers.removeValue(forKey: kind)
        cancel?()
    }

    func cancelAll() {
        let current = Array(cancellers.values)
        cancellers.removeAll()
        current.forEach { $0() }
    }
}
