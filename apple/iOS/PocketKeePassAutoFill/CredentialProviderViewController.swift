import AuthenticationServices
import SwiftUI

/// iOS's way into the AutoFill extension. iOS shows it when the user picks PocketKeePass for a
/// password field (the key above the keyboard, or Safari's "Passwords" menu).
///
/// PocketKeePass doesn't put its entries into iOS's list of saved logins
/// (`ASCredentialIdentityStore`), so iOS never asks for a password without showing this screen,
/// and nothing about the database's entries is stored outside it.
final class CredentialProviderViewController: ASCredentialProviderViewController {
    private var model: AutoFillModel?

    override func viewDidLoad() {
        super.viewDidLoad()
        Preferences.register()
    }

    override func prepareCredentialList(for serviceIdentifiers: [ASCredentialServiceIdentifier]) {
        show(serviceIdentifiers)
    }

    /// Only asked for logins in iOS's list, which PocketKeePass never adds to.
    override func provideCredentialWithoutUserInteraction(for credentialRequest: any ASCredentialRequest) {
        extensionContext.cancelRequest(withError: ASExtensionError(.userInteractionRequired))
    }

    override func prepareInterfaceToProvideCredential(for credentialRequest: any ASCredentialRequest) {
        show([credentialRequest.credentialIdentity.serviceIdentifier])
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        // However the sheet went away, the database doesn't stay open behind it.
        model?.end(.cancel)
    }

    private func show(_ identifiers: [ASCredentialServiceIdentifier]) {
        let model = AutoFillModel(identifiers: identifiers) { [weak self] outcome in
            self?.finish(outcome)
        }
        self.model = model
        let host = UIHostingController(rootView: AutoFillView(model: model))
        addChild(host)
        host.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        host.didMove(toParent: self)
    }

    private func finish(_ outcome: AutoFillModel.Outcome) {
        switch outcome {
        case .fill(let user, let password):
            extensionContext.completeRequest(withSelectedCredential: ASPasswordCredential(user: user, password: password))
        case .cancel:
            extensionContext.cancelRequest(withError: ASExtensionError(.userCanceled))
        }
    }
}
