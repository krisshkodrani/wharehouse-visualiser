# Dependency risk register

Reviewed 2026-08-15 for v0.1.0.

`npm audit --omit=dev` reports zero findings. The complete development tree reports seven transitive findings (four high, three moderate) under UI5 CLI 4.0.62 through `@ui5/project`, `pacote`, and Sigstore packages. npm's proposed remediation is UI5 CLI 3.0.0, an out-of-maintenance downgrade that increases rather than reduces supply-chain risk.

These packages execute only during dependency installation and frontend builds; they are absent from the shipped browser application and runtime containers. The project therefore records a time-bounded development-tool exception instead of applying a forced downgrade. Mitigations are a committed lockfile, clean ephemeral CI runners, production-only audit as a release gate, GitHub dependency review, CodeQL, SBOM/provenance generation, and weekly Dependabot review. Reassess on every UI5 CLI update and no later than the next minor release.
